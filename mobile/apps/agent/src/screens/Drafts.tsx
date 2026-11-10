import React, { useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import {
  ago, api, Badge, Button, Card, Chip, colors, dateTime, DynamicForm, ErrorState, font, GradientHeader, KeyValue, Loading, money, normaliseAnswers, Notice, radius,
  Row, Screen, space, StatusBadge, TextField, useAction, useNav, useOnRefocus, useQuery, validateForm,
} from '@buzzer/core';

/**
 * Agent drafts: prepare a quotation request, products from one shop or a service booking for a customer. The customer
 * approves it in their own app - an agent can never submit, accept or pay on the customer's behalf.
 */

type Kind = 'RFQ' | 'PURCHASE' | 'BOOKING';
type Item = { name: string; quantity: string; unit: string; brand: string };

const KIND_LABEL: Record<Kind, string> = { RFQ: '🧾 Quotation request', PURCHASE: '🛍️ Products from a shop', BOOKING: '🛠️ Service booking' };
const time12 = (iso: string) => new Date(iso).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });

export function PrepareDraftScreen({ params }: { params: { customerId: string; name: string; addresses: any[] } }) {
  const nav = useNav();
  const [kind, setKind] = useState<Kind>('RFQ');
  const [addressId, setAddressId] = useState<string | undefined>(params.addresses?.[0]?.id);
  const [note, setNote] = useState('');
  const act = useAction();
  const [done, setDone] = useState<string | null>(null);

  // RFQ
  const cats = useQuery(() => api.get<any[]>('/api/v1/catalogue/categories?vertical=MARKETPLACE'));
  const [categoryId, setCategoryId] = useState<string | undefined>();
  const [items, setItems] = useState<Item[]>([{ name: '', quantity: '1', unit: '', brand: '' }]);
  const [bulk, setBulk] = useState(false);

  // PURCHASE
  const [pq, setPq] = useState('');
  const products = useQuery(() => (pq.trim().length >= 2 ? api.get<any>(`/api/v1/marketplace/products?q=${encodeURIComponent(pq.trim())}&size=8`) : Promise.resolve(null)), [pq]);
  const [productId, setProductId] = useState<string | null>(null);
  const product = useQuery(() => (productId ? api.get<any>(`/api/v1/marketplace/products/${productId}`) : Promise.resolve(null)), [productId]);
  const [cart, setCart] = useState<{ listing_id: string; label: string; shop: string; price: number; quantity: number }[]>([]);

  // BOOKING
  const [sq, setSq] = useState('');
  const services = useQuery(() => (sq.trim().length >= 2 ? api.get<any>(`/api/v1/search?q=${encodeURIComponent(sq.trim())}`) : Promise.resolve(null)), [sq]);
  const [service, setService] = useState<any>(null);
  const slots = useQuery(() => (service && addressId ? api.get<any>(`/api/v1/agent/customers/${params.customerId}/services/${service.id}/slots?addressId=${addressId}`)
    : Promise.resolve(null)), [service?.id, addressId]);
  const [slot, setSlot] = useState<string | null>(null);
  const pros = useQuery(() => (slot && service && addressId
    ? api.get<any[]>(`/api/v1/agent/customers/${params.customerId}/services/${service.id}/providers?addressId=${addressId}&slot=${encodeURIComponent(slot)}`)
    : Promise.resolve([] as any[])), [slot]);
  const [pro, setPro] = useState<string | null>(null);
  const form = useQuery(() => (service ? api.get<any>(`/api/v1/catalogue/services/${service.id}`) : Promise.resolve(null)), [service?.id]);
  const [answers, setAnswers] = useState<Record<string, any>>({});
  const [formErrors, setFormErrors] = useState<Record<string, string>>({});
  useEffect(() => { setSlot(null); setPro(null); }, [service?.id, addressId]);

  const setItem = (i: number, patch: Partial<Item>) => setItems((l) => l.map((x, j) => (j === i ? { ...x, ...patch } : x)));
  const send = async () => {
    if (kind === 'BOOKING') {
      const e = validateForm(form.data?.form_schema ?? null, answers);
      setFormErrors(e);
      if (Object.keys(e).length) return;
    }
    const body: any = { kind, note: note.trim() || null };
    if (kind === 'RFQ') {
      body.rfq = { category_id: categoryId, address_id: addressId ?? null, fulfilment: addressId ? 'DELIVERY' : 'PICKUP', bulk,
        items: items.filter((x) => x.name.trim()).map((x) => ({ name: x.name.trim(), quantity: Number(x.quantity) || 1, unit: x.unit.trim() || null, brand: x.brand.trim() || null })) };
    } else if (kind === 'PURCHASE') {
      body.purchase = { items: cart.map((c) => ({ listing_id: c.listing_id, quantity: c.quantity })), address_id: addressId ?? null };
    } else {
      body.booking = { service_item_id: service?.id, partner_id: pro, slot_start: slot, address_id: addressId,
        answers: normaliseAnswers(form.data?.form_schema ?? null, answers) };
    }
    const r = await act.run(() => api.post<any>(`/api/v1/agent/customers/${params.customerId}/drafts`, body));
    if (r) setDone(`${r.draft_number} sent to ${params.name}: “${r.title}”. They approve it in their app.`);
  };
  const ready = kind === 'RFQ' ? !!categoryId && items.some((x) => x.name.trim().length >= 2)
    : kind === 'PURCHASE' ? cart.length > 0 : !!(service && slot && pro && addressId);

  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="Prepare for approval" subtitle={`For ${params.name}`} onBack={nav.pop} />
      <Screen>
        {done ? (
          <>
            <Notice tone="success" message={done} />
            <Button title="Back to customer" onPress={nav.pop} />
          </>
        ) : (
          <>
            <Notice tone="info" message="You prepare it; the customer approves it in their app. Nothing is submitted, ordered, booked or paid until they do." />
            <Row style={{ flexWrap: 'wrap' }}>{(Object.keys(KIND_LABEL) as Kind[]).map((k) => <Chip key={k} label={KIND_LABEL[k]} selected={kind === k} onPress={() => setKind(k)} />)}</Row>
            <Text style={styles.section}>Address</Text>
            <Row style={{ flexWrap: 'wrap' }}>
              {(params.addresses ?? []).map((a: any) => <Chip key={a.id} label={`${a.label}: ${a.locality_name ?? a.line1}`} selected={addressId === a.id} onPress={() => setAddressId(a.id)} />)}
              {kind !== 'BOOKING' ? <Chip label="No address (pickup)" selected={!addressId} onPress={() => setAddressId(undefined)} /> : null}
            </Row>

            {kind === 'RFQ' ? (
              <>
                <Text style={styles.section}>Category</Text>
                <Row style={{ flexWrap: 'wrap' }}>{(cats.data ?? []).map((c) => <Chip key={c.id} label={c.name} selected={categoryId === c.id} onPress={() => setCategoryId(c.id)} />)}</Row>
                {items.map((it, i) => (
                  <Card key={i}>
                    <TextField label={`Item ${i + 1}`} value={it.name} onChangeText={(t) => setItem(i, { name: t })} />
                    <Row>
                      <View style={{ flex: 1 }}><TextField label="Qty" value={it.quantity} keyboardType="number-pad" onChangeText={(t) => setItem(i, { quantity: t.replace(/[^0-9]/g, '') })} /></View>
                      <View style={{ flex: 1 }}><TextField label="Unit" value={it.unit} onChangeText={(t) => setItem(i, { unit: t })} /></View>
                      <View style={{ flex: 1 }}><TextField label="Brand" value={it.brand} onChangeText={(t) => setItem(i, { brand: t })} /></View>
                    </Row>
                  </Card>
                ))}
                <Row>
                  <Button title="＋ Item" small variant="outline" onPress={() => setItems((l) => [...l, { name: '', quantity: '1', unit: '', brand: '' }])} />
                  <Chip label="Bulk / wholesale" selected={bulk} onPress={() => setBulk(!bulk)} />
                </Row>
              </>
            ) : null}

            {kind === 'PURCHASE' ? (
              <>
                <TextField label="Find a product" value={pq} onChangeText={setPq} placeholder="e.g. MCB, cement, atta" />
                {(products.data?.items ?? []).map((p: any) => (
                  <Chip key={p.id} label={`${p.name} · from ${money(p.min_price)}`} selected={productId === p.id} onPress={() => setProductId(p.id)} />
                ))}
                {product.data ? (product.data.offers ?? []).map((o: any) => (
                  <Card key={o.listing_id}>
                    <Row style={{ justifyContent: 'space-between' }}>
                      <View style={{ flex: 1 }}>
                        <Text style={{ fontWeight: '700', color: colors.text }}>{o.shop_name}{o.variant_name !== 'Standard' ? ` · ${o.variant_name}` : ''}</Text>
                        <Text style={styles.small}>{money(o.price)}{Number(o.moq) > 1 ? ` · min ${o.moq}` : ''} · {o.stock_status.replace(/_/g, ' ').toLowerCase()}</Text>
                      </View>
                      <Button title="Add" small onPress={() => setCart((c) => [...c.filter((x) => x.listing_id !== o.listing_id),
                        { listing_id: o.listing_id, label: product.data.name, shop: o.shop_name, price: Number(o.price), quantity: Math.max(1, Number(o.moq) || 1) }])} />
                    </Row>
                  </Card>
                )) : null}
                {cart.length ? (
                  <Card>
                    <Text style={{ fontWeight: '800', color: colors.text }}>In this draft ({cart[0].shop})</Text>
                    {cart.map((c) => (
                      <Row key={c.listing_id} style={{ justifyContent: 'space-between' }}>
                        <Text style={{ color: colors.text, flex: 1 }}>{c.label}</Text>
                        <Button title="−" small variant="outline" onPress={() => setCart((l) => l.map((x) => (x.listing_id === c.listing_id ? { ...x, quantity: Math.max(1, x.quantity - 1) } : x)))} />
                        <Text style={{ minWidth: 26, textAlign: 'center', color: colors.text }}>{c.quantity}</Text>
                        <Button title="＋" small variant="outline" onPress={() => setCart((l) => l.map((x) => (x.listing_id === c.listing_id ? { ...x, quantity: x.quantity + 1 } : x)))} />
                      </Row>
                    ))}
                    {new Set(cart.map((c) => c.shop)).size > 1 ? <Notice tone="warning" message="A draft is from one shop only - remove the others." /> : null}
                  </Card>
                ) : null}
              </>
            ) : null}

            {kind === 'BOOKING' ? (
              <>
                <TextField label="Find a service" value={sq} onChangeText={setSq} placeholder="e.g. tap, AC service, cleaning" />
                {!addressId ? <Notice tone="warning" message="Add an address for the customer first (their home or where the work is) - bookings need it to find free professionals nearby." /> : null}
                {(services.data?.items ?? []).filter((s: any) => s.pricing_type && !['QUOTE_REQUIRED', 'API_PRICED'].includes(s.pricing_type) && s.vertical !== 'MARKETPLACE' && !s.is_other).slice(0, 8).map((s: any) => (
                  <Chip key={s.id} label={s.name} selected={service?.id === s.id} onPress={() => setService(s)} />
                ))}
                {service && slots.loading ? <Loading /> : null}
                {service && slots.data ? (
                  <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: space.sm }}>
                    {(slots.data.days ?? []).flatMap((d: any) => d.slots.map((s: any) => (
                      <Pressable key={s.start} onPress={() => setSlot(s.start)} style={[styles.slot, slot === s.start && styles.slotOn]} accessibilityRole="button">
                        <Text style={[styles.slotText, slot === s.start && { color: colors.white }]}>{d.label}</Text>
                        <Text style={[styles.slotText, slot === s.start && { color: colors.white }]}>{time12(s.start)} · {s.providers}</Text>
                      </Pressable>
                    )))}
                  </ScrollView>
                ) : null}
                {(pros.data ?? []).map((p: any) => (
                  <Card key={p.id} onPress={() => setPro(p.id)} style={pro === p.id ? { borderColor: colors.secondary, borderWidth: 2 } : undefined}>
                    <Row style={{ justifyContent: 'space-between' }}>
                      <Text style={{ fontWeight: '700', color: colors.text }}>{pro === p.id ? '✓ ' : ''}{p.display_name}</Text>
                      <Text style={{ fontWeight: '800', color: colors.forest }}>{money(p.total)}</Text>
                    </Row>
                    <Text style={styles.small}>{Number(p.rating_count) ? `★ ${Number(p.rating_avg).toFixed(1)}` : 'New'} · {p.jobs_completed} jobs done</Text>
                  </Card>
                ))}
                {pro && form.data ? (
                  <Card>
                    <DynamicForm schema={form.data.form_schema ?? null} values={answers} errors={formErrors} onChange={(k, v) => setAnswers((s) => ({ ...s, [k]: v }))} />
                  </Card>
                ) : null}
              </>
            ) : null}

            <TextField label="Message to the customer (optional)" value={note} onChangeText={setNote} multiline maxLength={1000} />
            {act.error ? <Notice tone="danger" message={act.error} /> : null}
            <Button title="Send to the customer for approval" onPress={send} loading={act.busy} disabled={!ready} />
          </>
        )}
      </Screen>
    </View>
  );
}

/** On a customer: drafts sent to them and the agents' activity log, with a quick note / follow-up logger. */
export function CustomerWorkPanel({ customerId }: { customerId: string }) {
  const drafts = useQuery(() => api.get<any[]>(`/api/v1/agent/drafts?customerId=${customerId}`), [customerId]);
  const log = useQuery(() => api.get<any[]>(`/api/v1/agent/activity?customerId=${customerId}&limit=20`), [customerId]);
  useOnRefocus(() => { drafts.refresh(); log.refresh(); });
  const [kind, setKind] = useState<'NOTE' | 'CALL' | 'VENDOR_FOLLOW_UP'>('NOTE');
  const [text, setText] = useState('');
  const act = useAction();
  return (
    <>
      <Text style={styles.section}>Waiting for the customer's approval</Text>
      {(drafts.data ?? []).length === 0 ? <Text style={styles.small}>Nothing prepared yet.</Text> : (drafts.data ?? []).slice(0, 10).map((d) => (
        <Card key={d.id}>
          <Row style={{ justifyContent: 'space-between' }}>
            <Text style={{ fontWeight: '700', color: colors.text, flex: 1 }}>{d.title}</Text>
            <StatusBadge status={d.status === 'SENT' ? 'PENDING' : d.status} />
          </Row>
          <Text style={styles.small}>{d.draft_number} · {ago(d.created_at)}{d.estimated_total != null ? ` · ${money(d.estimated_total)}` : ''}{d.request_number ? ` → ${d.request_number}` : ''}</Text>
          {d.decision_note ? <Text style={{ color: colors.text, fontSize: font.xs }}>Customer: “{d.decision_note}”</Text> : null}
          {d.status === 'SENT' ? <Button title="Withdraw" small variant="ghost" onPress={async () => { if (await act.run(() => api.post(`/api/v1/agent/drafts/${d.id}/cancel`, {}))) drafts.refresh(); }} /> : null}
        </Card>
      ))}
      <Text style={styles.section}>Activity log</Text>
      <Card>
        <Row style={{ flexWrap: 'wrap' }}>
          {([['NOTE', '📝 Note'], ['CALL', '📞 Call'], ['VENDOR_FOLLOW_UP', '🏪 Vendor follow-up']] as const).map(([k, l]) => <Chip key={k} label={l} selected={kind === k} onPress={() => setKind(k)} />)}
        </Row>
        <TextField value={text} onChangeText={setText} placeholder="What happened?" maxLength={500} />
        <Button title="Log it" small disabled={text.trim().length < 3} loading={act.busy} onPress={async () => {
          if (await act.run(() => api.post('/api/v1/agent/activity', { kind, summary: text.trim(), customer_id: customerId }))) { setText(''); log.refresh(); }
        }} />
        {act.error ? <Notice tone="danger" message={act.error} /> : null}
      </Card>
      {(log.data ?? []).map((x) => (
        <View key={x.id} style={styles.logRow}>
          <Text style={{ color: colors.text, fontSize: font.sm }}>{x.summary}</Text>
          <Text style={styles.small}>{x.kind.replace(/_/g, ' ').toLowerCase()} · {x.agent_name} · {dateTime(x.created_at)}</Text>
        </View>
      ))}
    </>
  );
}

/** Dashboard section: work queues across all verticals, SLA countdown, metrics and recent activity. */
export function Workspace({ onOpenTab }: { onOpenTab: (t: string) => void }) {
  const nav = useNav();
  const q = useQuery(() => api.get<any>('/api/v1/agent/workspace'));
  useOnRefocus(q.refresh);
  if (q.loading) return <Loading />;
  if (q.error || !q.data) return <ErrorState message={q.error ?? 'Not available'} onRetry={q.reload} />;
  const w = q.data;
  const queues: [string, number][] = [
    ['Pending RFQs', w.queues.pending_rfqs], ['Vendor follow-ups', w.queues.vendor_follow_ups], ['Active orders', w.queues.active_orders],
    ['Service bookings', w.queues.service_bookings], ['Tasks', w.queues.tasks], ['Waiting on customer', w.queues.waiting_on_customer],
    ['Drafts awaiting approval', w.queues.drafts_waiting], ['Escalated to me', w.queues.escalated_to_me], ['Completed (30 d)', w.queues.completed_30d],
  ];
  return (
    <>
      <Text style={styles.section}>My work across BUZZER</Text>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
        {queues.map(([label, v]) => (
          <Card key={label} onPress={() => onOpenTab('inbox')} style={{ flexGrow: 1, minWidth: '30%', alignItems: 'center', padding: space.md }}>
            <Text style={{ fontSize: font.xl, fontWeight: '900', color: label === 'Escalated to me' && Number(v) > 0 ? colors.danger : colors.forest }}>{String(v)}</Text>
            <Text style={{ color: colors.textSecondary, fontSize: font.xs, textAlign: 'center' }}>{label}</Text>
          </Card>
        ))}
      </View>
      <Text style={styles.section}>⏱️ First-response countdown</Text>
      {(w.sla_countdown ?? []).length === 0 ? <Text style={styles.small}>All caught up.</Text> : (w.sla_countdown ?? []).map((r: any) => (
        <Card key={r.id} onPress={() => nav.push('Request', { id: r.id })}>
          <Row style={{ justifyContent: 'space-between' }}>
            <Text style={{ fontWeight: '700', color: colors.text, flex: 1 }}>{r.customer_name} · #{r.request_number}</Text>
            <Badge label={Number(r.minutes_left) < 0 ? `${Math.abs(Number(r.minutes_left))} min over` : `${r.minutes_left} min left`}
              tone={Number(r.minutes_left) < 0 ? 'danger' : Number(r.minutes_left) < 15 ? 'warning' : 'info'} />
          </Row>
          <Text style={styles.small}>{r.vertical.toLowerCase()} · {r.priority.toLowerCase()}{r.escalated_to_me ? ' · escalated / covering' : ''}</Text>
        </Card>
      ))}
      <Card>
        <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>By business area</Text>
        {(w.by_vertical ?? []).map((v: any) => <KeyValue key={v.vertical} k={v.name} v={`${v.open} open · ${v.completed_30d} done (30 d)`} />)}
        <KeyValue k="SLA met (30 d)" v={w.metrics_30d.sla_met_percent != null ? `${w.metrics_30d.sla_met_percent}%` : '—'} />
        <KeyValue k="Drafts approved (30 d)" v={`${w.metrics_30d.drafts_approved} of ${w.metrics_30d.drafts_sent}`} />
      </Card>
      {(w.recent_activity ?? []).length ? (
        <Card>
          <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>Recent activity</Text>
          {w.recent_activity.map((x: any, i: number) => (
            <Text key={i} style={{ color: colors.text, fontSize: font.xs, paddingVertical: 2 }}>• {x.summary} <Text style={styles.small}>{ago(x.created_at)}</Text></Text>
          ))}
        </Card>
      ) : null}
    </>
  );
}

const styles = StyleSheet.create({
  section: { fontSize: font.lg, fontWeight: '800', color: colors.text, marginTop: space.sm },
  small: { color: colors.textSecondary, fontSize: font.xs },
  slot: { paddingVertical: 8, paddingHorizontal: 10, borderRadius: radius.md, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.white },
  slotOn: { backgroundColor: colors.emerald, borderColor: colors.emerald },
  slotText: { fontSize: font.xs, color: colors.text, fontWeight: '700' },
  logRow: { paddingVertical: 6, borderBottomWidth: 1, borderBottomColor: colors.border },
});
