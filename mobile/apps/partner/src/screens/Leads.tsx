import React, { useEffect, useMemo, useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import {
  api, AttachmentList, Badge, Button, Card, Chip, colors, ConfirmDialog, dateTime, EmptyState, ErrorState, font, GradientHeader, KeyValue, Loading, money, Notice,
  radius, Row, Screen, space, StatusBadge, TextField, Toggle, useAction, useNav, useOnRefocus, useQuery,
} from '@buzzer/core';

type Lead = {
  invitation_id: string; status: string; request_number: string; created_at: string; category_name: string; subcategory_name: string | null;
  service_name: string | null; locality_name: string | null; quoted_amount: number | null; quote_status: string | null; revision_note: string | null;
  handling_mode: string; item_count: number; line_items: string | null; answers: string | null; description: string | null; preferred_start: string | null;
  priority: string; booking_mode: string; estimated_total: number | null;
};

const needsAction = (l: Lead) => l.status === 'INVITED' || l.quote_status === 'REVISION_REQUESTED';

export function LeadsScreen() {
  const nav = useNav();
  const [tab, setTab] = useState<'ACTION' | 'QUOTED' | 'CLOSED' | 'ALL'>('ACTION');
  const q = useQuery(() => api.get<Lead[]>('/api/v1/partner/leads'));
  useOnRefocus(q.refresh);
  const all = q.data ?? [];
  const counts = {
    ACTION: all.filter(needsAction).length,
    QUOTED: all.filter((l) => l.status === 'QUOTED' && l.quote_status !== 'REVISION_REQUESTED').length,
    CLOSED: all.filter((l) => ['CLOSED', 'DECLINED', 'EXPIRED'].includes(l.status)).length,
    ALL: all.length,
  };
  const shown = all.filter((l) => tab === 'ALL' ? true : tab === 'ACTION' ? needsAction(l)
    : tab === 'QUOTED' ? l.status === 'QUOTED' && l.quote_status !== 'REVISION_REQUESTED' : ['CLOSED', 'DECLINED', 'EXPIRED'].includes(l.status));

  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="Leads" subtitle="Customers asking for your price — reply fast to win" />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        <Row style={{ flexWrap: 'wrap' }}>
          {([['ACTION', '🔔 Action needed'], ['QUOTED', 'Quoted'], ['CLOSED', 'Closed'], ['ALL', 'All']] as const).map(([k, label]) => (
            <Chip key={k} label={`${label} (${counts[k]})`} selected={tab === k} onPress={() => setTab(k)} />
          ))}
        </Row>
        {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : shown.length === 0 ? (
          <EmptyState title="No leads here" message="New quote requests from customers and agents appear here." />
        ) : shown.map((l) => {
          const isNew = l.status === 'INVITED';
          const revision = l.quote_status === 'REVISION_REQUESTED';
          return (
            <Card key={l.invitation_id} onPress={() => nav.push('LeadDetail', { lead: l })} accessibilityLabel={`Lead ${l.request_number}`}
              style={(isNew || revision) ? [styles.highlight, revision && { borderLeftColor: colors.warning }] : undefined}>
              <Row style={{ justifyContent: 'space-between' }}>
                <Text style={{ fontWeight: '800', color: colors.text, flex: 1 }}>{l.service_name || l.subcategory_name || l.category_name}</Text>
                {isNew ? <Badge label="NEW" tone="success" /> : revision ? <Badge label="Revise quote" tone="warning" /> : <StatusBadge status={l.status} />}
              </Row>
              <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>
                #{l.request_number} • {l.locality_name ?? 'Area shared after assignment'} • {dateTime(l.created_at)}
              </Text>
              <Row style={{ marginTop: 4, gap: space.sm }}>
                {l.item_count ? <Text style={{ color: colors.forest, fontWeight: '700', fontSize: font.sm }}>🧾 {l.item_count} item{l.item_count === 1 ? '' : 's'}</Text> : null}
                {l.handling_mode === 'AUTOMATED' ? <Text style={{ color: colors.textSecondary, fontSize: font.sm }}>⚡ Goes straight to the customer</Text> : null}
              </Row>
              {revision && l.revision_note ? <Text style={{ color: colors.warning, marginTop: 4 }}>Customer: “{l.revision_note}”</Text> : null}
              {l.quoted_amount && !revision ? <Text style={{ color: colors.emerald, marginTop: 4, fontWeight: '600' }}>Your quote: {money(l.quoted_amount)}</Text> : null}
            </Card>
          );
        })}
      </Screen>
    </View>
  );
}

export function LeadDetailScreen({ params }: { params: { lead: Lead } }) {
  const l = params.lead;
  const lines: any[] = l.line_items ? JSON.parse(l.line_items) : [];
  return Array.isArray(lines) && lines.length ? <ItemQuoteScreen lead={l} /> : <StandardQuoteScreen lead={l} />;
}

// ----- Item-by-item quote (cart requests, e.g. hardware shops) ---------------------------------------------

/** Price per unit before discount and GST. RFQ fields (brand, discount, GST, MOQ, lead time) are optional. */
type LineDraft = { available: boolean; price: string; note: string; brand?: string; disc?: string; tax?: string; moq?: string; lead?: string };
const num = (s?: string) => (s && Number(s) >= 0 ? Number(s) : 0);
const ETA = [['2H', 'Within 2 hours'], ['TODAY', 'Today'], ['TOMORROW', 'Tomorrow']] as const;

function etaDate(k: string): string | null {
  const d = new Date();
  if (k === '2H') return new Date(d.getTime() + 2 * 3600_000).toISOString();
  if (k === 'TODAY') { d.setHours(20, 0, 0, 0); return d.toISOString(); }
  if (k === 'TOMORROW') { d.setDate(d.getDate() + 1); d.setHours(13, 0, 0, 0); return d.toISOString(); }
  return null;
}

function ItemQuoteScreen({ lead }: { lead: Lead }) {
  const nav = useNav();
  const q = useQuery(() => api.get<any>(`/api/v1/partner/leads/${lead.invitation_id}`), [lead.invitation_id]);
  const act = useAction();
  const [draft, setDraft] = useState<Record<string, LineDraft>>({});
  const [delivery, setDelivery] = useState('');
  const [eta, setEta] = useState<string>('TODAY');
  const [note, setNote] = useState('');
  const [pod, setPod] = useState(true);
  const [terms, setTerms] = useState('');
  const [exclusions, setExclusions] = useState('');
  const [leadDays, setLeadDays] = useState('');
  const [done, setDone] = useState<string | null>(null);
  const [declineOpen, setDeclineOpen] = useState(false);
  const [reason, setReason] = useState('');
  const d = q.data;
  const mine = d?.my_quote;

  // Pre-fill from the previous quote (when revising) or the suggested prices.
  useEffect(() => {
    if (!d) return;
    const prev: Record<string, any> = {};
    (mine?.line_items ?? []).forEach((x: any) => (prev[x.key] = x));
    const next: Record<string, LineDraft> = {};
    (d.line_items ?? []).forEach((x: any) => {
      const p = prev[x.key];
      const price = p?.unit_price ?? x.suggested_price;
      next[x.key] = {
        available: p ? !!p.available : true, price: price == null ? '' : String(Number(price)), note: p?.note ?? '',
        brand: p?.brand ?? x.brand_preference ?? '', disc: p?.discount_percent ? String(Number(p.discount_percent)) : '',
        tax: p?.tax_rate ? String(Number(p.tax_rate)) : '', moq: p?.moq ? String(p.moq) : '', lead: p?.lead_time_days != null ? String(p.lead_time_days) : '',
      };
    });
    setDraft(next);
    if (mine) {
      setDelivery(Number(mine.delivery_fee) ? String(Number(mine.delivery_fee)) : '');
      setNote(mine.partner_note ?? '');
      setPod(!!mine.pay_on_delivery_allowed);
      setTerms(mine.terms ?? '');
      setExclusions(mine.exclusions ?? '');
      setLeadDays(mine.lead_time_days != null ? String(mine.lead_time_days) : '');
    }
  }, [d]);

  const totals = useMemo(() => {
    let subtotal = 0;
    let available = 0;
    let missingPrice = false;
    (d?.line_items ?? []).forEach((x: any) => {
      const v = draft[x.key];
      if (!v?.available) return;
      available++;
      const p = Number(v.price);
      if (!v.price || !(p > 0)) missingPrice = true;
      else {
        const gross = p * x.quantity;
        const net = gross - (gross * num(v.disc)) / 100;
        subtotal += net + (net * num(v.tax)) / 100;
      }
    });
    const del = Number(delivery) || 0;
    return { subtotal, available, missingPrice, total: subtotal + del };
  }, [d, draft, delivery]);

  const set = (k: string, patch: Partial<LineDraft>) => setDraft({ ...draft, [k]: { ...draft[k], ...patch } });

  const submit = async () => {
    const r = await act.run(() => api.post<any>(`/api/v1/partner/leads/${lead.invitation_id}/item-quote`, {
      lines: (d.line_items ?? []).map((x: any) => ({
        key: x.key, available: draft[x.key]?.available ?? false,
        unit_price: draft[x.key]?.available ? Number(draft[x.key].price) : null, note: draft[x.key]?.note || null,
        brand: draft[x.key]?.brand || null, discount_percent: num(draft[x.key]?.disc) || null, tax_percent: num(draft[x.key]?.tax) || null,
        moq: draft[x.key]?.moq ? Number(draft[x.key].moq) : null, lead_time_days: draft[x.key]?.lead ? Number(draft[x.key].lead) : null,
      })),
      delivery_fee: Number(delivery) || 0, estimated_arrival: etaDate(eta), partner_note: note || null, pay_on_delivery_allowed: pod,
      terms: terms.trim() || null, exclusions: exclusions.trim() || null, lead_time_days: leadDays ? Number(leadDays) : null,
    }));
    if (r) {
      setDone(r.status === 'SHARED'
        ? `${r.revision_no > 1 ? 'Revised quote' : 'Quote'} of ${money(r.total_amount)} sent straight to the customer. You'll be notified when they approve.`
        : `Quote of ${money(r.total_amount)} sent to the BUZZER agent handling this request.`);
      q.refresh();
    }
  };

  const title = lead.subcategory_name || lead.category_name;
  if (q.loading) return <View style={{ flex: 1 }}><GradientHeader title={title} onBack={nav.pop} /><Loading /></View>;
  if (!d) return <View style={{ flex: 1 }}><GradientHeader title={title} onBack={nav.pop} /><Screen><ErrorState message={q.error ?? 'Not found'} onRetry={q.reload} /></Screen></View>;

  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title={title} subtitle={`#${d.request_number} • ${d.line_items.length} items`} onBack={nav.pop} />
      <Screen footer={d.can_quote && !done ? (
        <View style={{ gap: 6 }}>
          <Row style={{ justifyContent: 'space-between' }}>
            <Text style={{ color: colors.textSecondary }}>{totals.available} of {d.line_items.length} available</Text>
            <Text style={{ fontWeight: '900', color: colors.forest, fontSize: font.lg }}>{money(totals.total)}</Text>
          </Row>
          <Button title={mine ? 'Send revised quote' : 'Send quote to customer'} loading={act.busy}
            disabled={totals.available === 0 || totals.missingPrice} onPress={submit} />
        </View>
      ) : undefined}>
        {mine?.status === 'REVISION_REQUESTED' ? (
          <Notice tone="warning" message={`Customer asked you to revise: “${mine.revision_note}”. Update the items below and send again.`} />
        ) : mine ? (
          <Notice tone="success" message={`Your quote v${mine.revision_no} (${money(mine.total_amount)}) is ${mine.status === 'SHARED' ? 'with the customer' : mine.status.toLowerCase()}. You can still revise it until they approve.`} />
        ) : null}
        {done ? <Notice tone="success" message={done} /> : null}

        <Card>
          <KeyValue k="Area" v={d.locality_name ?? '—'} />
          <KeyValue k="Needed by" v={d.preferred_start ? dateTime(d.preferred_start) : 'Flexible'} />
          {d.description?.includes('Note:') ? <Text style={{ color: colors.text, marginTop: 4 }}>📝 {d.description.split('Note:')[1].trim()}</Text> : null}
          {d.rfq ? (
            <Row style={{ flexWrap: 'wrap', marginTop: 4 }}>
              <Badge label={d.rfq.fulfilment === 'PICKUP' ? 'Customer picks up' : 'Deliver to customer'} tone="info" />
              {d.rfq.bulk ? <Badge label="Bulk / wholesale" tone="warning" /> : null}
              {d.rfq.needed_by ? <Badge label={`Needed by ${dateTime(d.rfq.needed_by)}`} tone="neutral" /> : null}
            </Row>
          ) : null}
          <Text style={{ color: colors.textSecondary, fontSize: font.xs, marginTop: 6 }}>Mark each item available or not and set your price. Prices from your approved price list are pre-filled.</Text>
        </Card>
        {(d.attachments ?? []).length ? (
          <Card>
            <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>📎 Photos & documents from the customer</Text>
            <AttachmentList items={d.attachments} showVisibility={false} />
          </Card>
        ) : null}

        {(d.line_items ?? []).map((x: any) => {
          const v = draft[x.key] ?? { available: true, price: '', note: '' };
          const gross = v.available && Number(v.price) > 0 ? Number(v.price) * x.quantity : null;
          const net = gross == null ? null : gross - (gross * num(v.disc)) / 100;
          const lineTotal = net == null ? null : net + (net * num(v.tax)) / 100;
          return (
            <Card key={x.key} style={!v.available ? { opacity: 0.75 } : undefined}>
              <Row style={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <View style={{ flex: 1 }}>
                  <Text style={{ fontWeight: '800', color: colors.text }}>{x.quantity} × {x.name}</Text>
                  {x.custom && d.request_type !== 'PRODUCT_RFQ' ? <Badge label="Extra item — not on menu" tone="info" /> : null}
                  {x.brand_preference ? <Text style={{ color: colors.text, fontSize: font.xs }}>Preferred brand: {x.brand_preference}</Text> : null}
                  {x.specs ? <Text style={{ color: colors.text, fontSize: font.xs }}>Specs: {x.specs}</Text> : null}
                  {x.customer_note ? <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>Customer: {x.customer_note}</Text> : null}
                  {x.listed_price != null ? <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>BUZZER listed {money(x.listed_price)}{x.unit ? ` ${x.unit}` : ''}{x.my_price != null ? ` • your price ${money(x.my_price)}` : ''}</Text> : null}
                </View>
                <Text style={{ fontWeight: '800', color: v.available ? colors.forest : colors.textSecondary }}>{lineTotal != null ? money(lineTotal) : v.available ? '—' : 'N/A'}</Text>
              </Row>
              <Row style={{ marginTop: space.sm, gap: space.sm }}>
                <Pressable accessibilityRole="button" onPress={() => set(x.key, { available: true })} style={[styles.toggle, v.available && styles.toggleOn]}>
                  <Text style={[styles.toggleText, v.available && { color: colors.white }]}>✓ Available</Text>
                </Pressable>
                <Pressable accessibilityRole="button" onPress={() => set(x.key, { available: false })} style={[styles.toggle, !v.available && styles.toggleOff]}>
                  <Text style={[styles.toggleText, !v.available && { color: colors.white }]}>✕ Not available</Text>
                </Pressable>
              </Row>
              {v.available ? (
                <Row style={{ marginTop: space.sm, gap: space.sm }}>
                  <Text style={{ fontWeight: '700', color: colors.text }}>₹</Text>
                  <TextInput value={v.price} onChangeText={(t) => set(x.key, { price: t.replace(/[^0-9.]/g, '') })} keyboardType="decimal-pad"
                    placeholder="Price per unit" placeholderTextColor={colors.textSecondary} style={styles.input} accessibilityLabel={`Price for ${x.name}`} />
                </Row>
              ) : null}
              {v.available ? (
                <>
                  <Row style={{ marginTop: 6, gap: 6 }}>
                    <TextInput value={v.brand} onChangeText={(t) => set(x.key, { brand: t })} placeholder="Brand offered" placeholderTextColor={colors.textSecondary}
                      style={[styles.input, { fontSize: font.sm }]} accessibilityLabel={`Brand for ${x.name}`} />
                    <TextInput value={v.disc} onChangeText={(t) => set(x.key, { disc: t.replace(/[^0-9.]/g, '') })} placeholder="Disc %" keyboardType="decimal-pad"
                      placeholderTextColor={colors.textSecondary} style={[styles.input, { fontSize: font.sm, maxWidth: 80 }]} accessibilityLabel={`Discount for ${x.name}`} />
                    <TextInput value={v.tax} onChangeText={(t) => set(x.key, { tax: t.replace(/[^0-9.]/g, '') })} placeholder="GST %" keyboardType="decimal-pad"
                      placeholderTextColor={colors.textSecondary} style={[styles.input, { fontSize: font.sm, maxWidth: 80 }]} accessibilityLabel={`GST for ${x.name}`} />
                  </Row>
                  {d.request_type === 'PRODUCT_RFQ' ? (
                    <Row style={{ marginTop: 6, gap: 6 }}>
                      <TextInput value={v.moq} onChangeText={(t) => set(x.key, { moq: t.replace(/[^0-9]/g, '') })} placeholder="Min. order qty" keyboardType="number-pad"
                        placeholderTextColor={colors.textSecondary} style={[styles.input, { fontSize: font.sm }]} accessibilityLabel={`Minimum order for ${x.name}`} />
                      <TextInput value={v.lead} onChangeText={(t) => set(x.key, { lead: t.replace(/[^0-9]/g, '') })} placeholder="Lead time (days)" keyboardType="number-pad"
                        placeholderTextColor={colors.textSecondary} style={[styles.input, { fontSize: font.sm }]} accessibilityLabel={`Lead time for ${x.name}`} />
                    </Row>
                  ) : null}
                </>
              ) : null}
              <TextInput value={v.note} onChangeText={(t) => set(x.key, { note: t })} placeholder={v.available ? 'Specs offered / note (optional)' : 'Alternative or reason (optional)'}
                placeholderTextColor={colors.textSecondary} style={[styles.input, { marginTop: 6, fontSize: font.sm }]} />
            </Card>
          );
        })}

        {d.can_quote ? (
          <Card>
            <Text style={{ fontWeight: '800', color: colors.text }}>Delivery</Text>
            <Row style={{ flexWrap: 'wrap', marginVertical: space.sm }}>
              {ETA.map(([k, label]) => <Chip key={k} label={label} selected={eta === k} onPress={() => setEta(k)} />)}
            </Row>
            <TextField label="Delivery charge ₹ (0 for free)" keyboardType="numeric" value={delivery} onChangeText={(t) => setDelivery(t.replace(/[^0-9.]/g, ''))} />
            <TextField label="Note for the customer (optional)" value={note} onChangeText={setNote} placeholder="e.g. All branded items, GST bill provided" />
            <Toggle label="Allow pay on delivery" value={pod} onChange={setPod} />
            {d.request_type === 'PRODUCT_RFQ' ? (
              <>
                <TextField label="Overall lead time (days, optional)" keyboardType="number-pad" value={leadDays} onChangeText={(t) => setLeadDays(t.replace(/[^0-9]/g, ''))} />
                <TextField label="Terms (optional)" value={terms} onChangeText={setTerms} placeholder="e.g. GST invoice, 50% advance for bulk" maxLength={1000} />
                <TextField label="Exclusions (optional)" value={exclusions} onChangeText={setExclusions} placeholder="e.g. Unloading not included" maxLength={1000} />
              </>
            ) : null}
            <Row style={{ justifyContent: 'space-between' }}>
              <Text style={{ color: colors.textSecondary }}>Items {money(totals.subtotal)} (after discount, incl. GST) + delivery {money(Number(delivery) || 0)}</Text>
            </Row>
          </Card>
        ) : <Notice tone="info" message={`This lead is ${lead.status.toLowerCase()}.`} />}
        {act.error ? <ErrorState message={act.error} /> : null}
        {d.can_quote && !mine ? <Button title="Decline this lead" variant="ghost" onPress={() => setDeclineOpen(true)} /> : null}
      </Screen>
      <ConfirmDialog visible={declineOpen} title="Decline lead?" danger confirmLabel="Decline" loading={act.busy} onCancel={() => setDeclineOpen(false)}
        onConfirm={async () => {
          const ok = await act.run(() => api.post(`/api/v1/partner/leads/${lead.invitation_id}/decline`, { reason }));
          setDeclineOpen(false);
          if (ok) nav.pop();
        }}>
        <TextField label="Reason (optional)" value={reason} onChangeText={setReason} placeholder="e.g. Out of stock" />
      </ConfirmDialog>
    </View>
  );
}

// ----- Standard quote (services with a requirement form) -----------------------------------------------------

function StandardQuoteScreen({ lead: l }: { lead: Lead }) {
  const nav = useNav();
  const detail = useQuery(() => api.get<any>(`/api/v1/partner/leads/${l.invitation_id}`), [l.invitation_id]);
  const answers = l.answers ? JSON.parse(l.answers) : {};
  const [f, setF] = useState({ scope_of_work: '', labour_amount: '', materials_amount: '', visit_fee: '', tax_amount: '', discount_amount: '',
    warranty: '', exclusions: '', cancellation_terms: '', pay_on_delivery_allowed: false, valid_hours: '48' });
  const [declineOpen, setDeclineOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [done, setDone] = useState<string | null>(null);
  const act = useAction();
  const n = (v: string) => (v.trim() === '' ? 0 : Number(v));
  const total = n(f.labour_amount) + n(f.materials_amount) + n(f.visit_fee) + n(f.tax_amount) - n(f.discount_amount);

  const submit = async () => {
    const validUntil = new Date(Date.now() + Math.max(1, n(f.valid_hours)) * 3600_000).toISOString();
    const r = await act.run(() => api.post<{ quotation_number: string; total_amount: number }>(`/api/v1/partner/leads/${l.invitation_id}/quote`, {
      scope_of_work: f.scope_of_work, labour_amount: n(f.labour_amount), materials_amount: n(f.materials_amount), visit_fee: n(f.visit_fee),
      tax_amount: n(f.tax_amount), discount_amount: n(f.discount_amount), warranty: f.warranty || null, exclusions: f.exclusions || null,
      cancellation_terms: f.cancellation_terms || null, pay_on_delivery_allowed: f.pay_on_delivery_allowed, valid_until: validUntil,
    }));
    if (r) setDone(`Quotation ${r.quotation_number} sent for ${money(r.total_amount)}. The agent will share it with the customer.`);
  };

  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title={l.service_name || l.subcategory_name || l.category_name} subtitle={`#${l.request_number}`} onBack={nav.pop} />
      <Screen>
        <Card>
          <KeyValue k="Category" v={`${l.category_name}${l.subcategory_name ? ' › ' + l.subcategory_name : ''}`} />
          <KeyValue k="Area" v={l.locality_name ?? '—'} />
          <KeyValue k="Preferred time" v={dateTime(l.preferred_start)} />
          <KeyValue k="Priority" v={l.priority} />
          {Object.entries(answers).map(([k, v]) => <KeyValue key={k} k={k.replace(/_/g, ' ')} v={Array.isArray(v) ? v.join(', ') : String(v)} />)}
          {l.description ? <Text style={{ marginTop: space.sm, color: colors.text }}>{l.description}</Text> : null}
          <Notice tone="neutral" message="Customer contact and full address are shared only after you are assigned and confirm the booking." />
        </Card>
        {(detail.data?.attachments ?? []).length ? (
          <Card>
            <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>📎 Photos & documents from the customer</Text>
            <AttachmentList items={detail.data.attachments} showVisibility={false} />
          </Card>
        ) : null}
        {done ? <Notice tone="success" message={done} /> : l.status !== 'INVITED' ? (
          <Notice tone="info" message={`This lead is ${l.status.toLowerCase()}.`} />
        ) : (
          <>
            <Card>
              <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>Your quotation</Text>
              <TextField label="Scope of work *" value={f.scope_of_work} onChangeText={(t) => setF({ ...f, scope_of_work: t })} multiline />
              <Row>
                <View style={{ flex: 1 }}><TextField label="Labour ₹" keyboardType="numeric" value={f.labour_amount} onChangeText={(t) => setF({ ...f, labour_amount: t })} /></View>
                <View style={{ flex: 1 }}><TextField label="Materials ₹" keyboardType="numeric" value={f.materials_amount} onChangeText={(t) => setF({ ...f, materials_amount: t })} /></View>
              </Row>
              <Row>
                <View style={{ flex: 1 }}><TextField label="Visit fee ₹" keyboardType="numeric" value={f.visit_fee} onChangeText={(t) => setF({ ...f, visit_fee: t })} /></View>
                <View style={{ flex: 1 }}><TextField label="Taxes ₹" keyboardType="numeric" value={f.tax_amount} onChangeText={(t) => setF({ ...f, tax_amount: t })} /></View>
              </Row>
              <Row>
                <View style={{ flex: 1 }}><TextField label="Discount ₹" keyboardType="numeric" value={f.discount_amount} onChangeText={(t) => setF({ ...f, discount_amount: t })} /></View>
                <View style={{ flex: 1 }}><TextField label="Valid for (hours)" keyboardType="numeric" value={f.valid_hours} onChangeText={(t) => setF({ ...f, valid_hours: t })} /></View>
              </Row>
              <TextField label="Warranty" value={f.warranty} onChangeText={(t) => setF({ ...f, warranty: t })} />
              <TextField label="Exclusions" value={f.exclusions} onChangeText={(t) => setF({ ...f, exclusions: t })} />
              <TextField label="Cancellation terms" value={f.cancellation_terms} onChangeText={(t) => setF({ ...f, cancellation_terms: t })} />
              <Toggle label="Allow pay on delivery" value={f.pay_on_delivery_allowed} onChange={(v) => setF({ ...f, pay_on_delivery_allowed: v })} />
              <Row style={{ justifyContent: 'space-between', marginTop: space.sm }}>
                <Text style={{ color: colors.textSecondary }}>Total (calculated by BUZZER)</Text>
                <Text style={{ fontSize: font.xl, fontWeight: '900', color: colors.forest }}>{money(total)}</Text>
              </Row>
            </Card>
            {act.error ? <ErrorState message={act.error} /> : null}
            <Button title="Send quotation" onPress={submit} loading={act.busy} disabled={f.scope_of_work.trim().length < 5 || total <= 0} />
            <Button title="Decline this lead" variant="ghost" onPress={() => setDeclineOpen(true)} />
          </>
        )}
      </Screen>
      <ConfirmDialog visible={declineOpen} title="Decline lead?" danger confirmLabel="Decline" loading={act.busy} onCancel={() => setDeclineOpen(false)}
        onConfirm={async () => {
          const ok = await act.run(() => api.post(`/api/v1/partner/leads/${l.invitation_id}/decline`, { reason }));
          setDeclineOpen(false);
          if (ok) nav.pop();
        }}>
        <TextField label="Reason (optional)" value={reason} onChangeText={setReason} />
      </ConfirmDialog>
    </View>
  );
}

const styles = StyleSheet.create({
  highlight: { borderLeftWidth: 5, borderLeftColor: colors.secondary, backgroundColor: colors.paleMint },
  toggle: { flex: 1, paddingVertical: 8, borderRadius: radius.pill, borderWidth: 1, borderColor: colors.border, alignItems: 'center', backgroundColor: colors.white },
  toggleOn: { backgroundColor: colors.emerald, borderColor: colors.emerald },
  toggleOff: { backgroundColor: colors.textSecondary, borderColor: colors.textSecondary },
  toggleText: { fontWeight: '700', color: colors.text, fontSize: font.sm },
  input: { flex: 1, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, paddingHorizontal: 10, paddingVertical: 8, color: colors.text, backgroundColor: colors.white },
});
