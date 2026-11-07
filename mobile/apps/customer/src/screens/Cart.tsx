import React, { useMemo, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import {
  api, Button, Card, Chip, colors, EmptyState, ErrorState, font, gradient, Label, Loading, money, newIdempotencyKey, Notice, Row,
  Screen, space, TextField, useAction, useNav, useOnRefocus, useQuery,
} from '@buzzer/core';
import { CartLine, useCart } from '../cart';
import { AddControl, PopularServiceRow, ServiceImage } from '../components/Catalog';
import { TopBar } from './Browse';

function parseWhen(text: string): { iso?: string; error?: string } {
  if (!text.trim()) return {};
  const d = new Date(text.trim().replace(' ', 'T'));
  if (Number.isNaN(d.getTime())) return { error: 'Use format YYYY-MM-DD HH:mm' };
  if (d.getTime() < Date.now()) return { error: 'Choose a future date & time' };
  return { iso: d.toISOString() };
}

function AddressPicker({ value, onChange }: { value: string | null; onChange: (id: string) => void }) {
  const nav = useNav();
  const addresses = useQuery(() => api.get<any[]>('/api/v1/customer/addresses'));
  useOnRefocus(addresses.reload);
  const selected = value ?? (addresses.data ?? []).find((a) => a.is_default)?.id ?? null;
  if (selected && selected !== value) setTimeout(() => onChange(selected), 0);
  return (
    <View style={{ gap: space.sm }}>
      {addresses.loading ? <Loading /> : (addresses.data ?? []).map((a) => (
        <Chip key={a.id} label={`${a.label}: ${a.line1}${a.locality_name ? ', ' + a.locality_name : ''}`} selected={selected === a.id} onPress={() => onChange(a.id)} />
      ))}
      <Button title="+ Add new address" variant="secondary" small onPress={() => nav.push('AddressForm', {})} />
    </View>
  );
}

type Extra = { name: string; quantity: string; note: string };

/** "+ Add an item that's not on the menu" for one part of the cart. Partners price these in their quote. */
function ExtraItems({ items, onChange }: { items: Extra[]; onChange: (v: Extra[]) => void }) {
  const [draft, setDraft] = useState<Extra>({ name: '', quantity: '1', note: '' });
  const [open, setOpen] = useState(false);
  return (
    <View style={{ marginTop: space.sm }}>
      {items.map((x, i) => (
        <Row key={i} style={{ justifyContent: 'space-between', paddingVertical: 4 }}>
          <Text style={{ color: colors.text, flex: 1 }}>➕ {x.quantity} × {x.name}{x.note ? ` — ${x.note}` : ''}</Text>
          <Pressable accessibilityRole="button" accessibilityLabel={`Remove ${x.name}`} onPress={() => onChange(items.filter((_, j) => j !== i))}>
            <Text style={{ color: colors.danger, fontWeight: '700' }}>Remove</Text>
          </Pressable>
        </Row>
      ))}
      {open ? (
        <View style={{ gap: 4, marginTop: 4 }}>
          <TextField label="Item name" value={draft.name} onChangeText={(t) => setDraft({ ...draft, name: t })} placeholder="e.g. Ceiling rose, white" />
          <Row>
            <View style={{ width: 90 }}><TextField label="Qty" keyboardType="numeric" value={draft.quantity} onChangeText={(t) => setDraft({ ...draft, quantity: t.replace(/[^0-9]/g, '') })} /></View>
            <View style={{ flex: 1 }}><TextField label="Brand / size (optional)" value={draft.note} onChangeText={(t) => setDraft({ ...draft, note: t })} /></View>
          </Row>
          <Row>
            <Button title="Add item" small disabled={draft.name.trim().length < 2} onPress={() => {
              onChange([...items, { name: draft.name.trim(), quantity: draft.quantity || '1', note: draft.note.trim() }]);
              setDraft({ name: '', quantity: '1', note: '' });
              setOpen(false);
            }} />
            <Button title="Cancel" small variant="ghost" onPress={() => setOpen(false)} />
          </Row>
        </View>
      ) : (
        <Pressable accessibilityRole="button" onPress={() => setOpen(true)} style={{ paddingVertical: 6 }}>
          <Text style={{ color: colors.emerald, fontWeight: '700' }}>+ Add an item that's not on the menu</Text>
        </Pressable>
      )}
    </View>
  );
}

/** Cart & checkout: get quotes straight from verified partners, or (subscribers) send the cart to the dedicated agent. */
export function CartScreen() {
  const nav = useNav();
  const { cart, refresh, error: cartError } = useCart();
  const [addressId, setAddressId] = useState<string | null>(null);
  const [when, setWhen] = useState('');
  const [note, setNote] = useState('');
  const [mode, setMode] = useState<'DIRECT_QUOTE' | 'AGENT_ASSISTED'>('DIRECT_QUOTE');
  const [extras, setExtras] = useState<Record<string, Extra[]>>({});
  const onboarding = useQuery(() => api.get<{ plan: string | null; agent: { display_name: string } | null }>('/api/v1/customer/onboarding'));
  const subscribed = onboarding.data?.plan === 'DEDICATED';
  const agentFirst = onboarding.data?.agent?.display_name?.split(' ')[0];
  const extraCount = Object.values(extras).reduce((n, l) => n + l.length, 0);
  const [done, setDone] = useState<{ id: string; request_number: string }[] | null>(null);
  const act = useAction();
  const key = useRef(newIdempotencyKey());
  useOnRefocus(refresh);

  const groups = useMemo(() => {
    const m = new Map<string, { name: string; items: CartLine[] }>();
    (cart?.items ?? []).forEach((l) => {
      const g = m.get(l.subcategory_id) ?? { name: `${l.category_name} › ${l.subcategory_name}`, items: [] };
      g.items.push(l);
      m.set(l.subcategory_id, g);
    });
    return [...m.values()];
  }, [cart]);

  const checkout = async () => {
    const w = parseWhen(when);
    if (w.error) {
      act.setError(w.error);
      return;
    }
    if (!addressId) {
      act.setError('Choose a service address');
      return;
    }
    const extra_items = Object.entries(extras).flatMap(([sub, list]) => list.map((x) => ({
      subcategory_id: sub, name: x.name, quantity: Number(x.quantity) || 1, note: x.note || null })));
    const res = await act.run(() => api.post<{ id: string; request_number: string }[]>('/api/v1/customer/cart/checkout',
      { mode, address_id: addressId, preferred_start: w.iso, note: note || null, extra_items }, key.current));
    if (res) {
      key.current = newIdempotencyKey();
      setExtras({});
      setDone(res);
      refresh();
    }
  };

  if (done) {
    return (
      <View style={{ flex: 1, backgroundColor: colors.paleMint }}>
        <TopBar title="Order placed" onBack={() => nav.reset('Tabs')} />
        <Screen>
          <LinearGradient colors={gradient.primary} style={styles.success}>
            <Text style={{ fontSize: 44 }}>✅</Text>
            <Text style={{ color: colors.white, fontSize: font.xl, fontWeight: '800', textAlign: 'center' }}>
              {mode === 'DIRECT_QUOTE' ? 'Asking verified partners' : 'Sent to your agent'}
            </Text>
            <Text style={{ color: '#D7F2E1', textAlign: 'center' }}>
              {mode === 'DIRECT_QUOTE'
                ? 'Your list went straight to verified partners near you. Each one confirms availability and price — compare the quotes, approve the best one and pay. We\'ll notify you as quotes arrive.'
                : 'Your agent will review your list, suggest options and share quotations for your approval.'}
            </Text>
          </LinearGradient>
          {done.map((r) => (
            <Card key={r.id} onPress={() => nav.replace('RequestDetail', { id: r.id })}>
              <Row style={{ justifyContent: 'space-between' }}>
                <Text style={{ fontWeight: '700', color: colors.text }}>Request #{r.request_number}</Text>
                <Text style={{ color: colors.emerald, fontWeight: '700' }}>Track ›</Text>
              </Row>
            </Card>
          ))}
          <Button title="Back to home" variant="outline" onPress={() => nav.reset('Tabs')} />
        </Screen>
      </View>
    );
  }

  return (
    <View style={{ flex: 1, backgroundColor: colors.paleMint }}>
      <TopBar title="My Cart" onBack={nav.pop} />
      <Screen>
        {cartError ? <ErrorState message={cartError} onRetry={refresh} /> : null}
        {!cart || cart.item_count === 0 ? (
          <Card>
            <EmptyState title="Your cart is empty" message="Add services from any category, or write to your agent for anything else."
              action="Browse categories" onAction={() => nav.push('Categories')} />
            <Button title="✍️ Write to my agent" variant="outline" onPress={() => nav.push('WriteToAgent', {})} />
          </Card>
        ) : (
          <>
            {groups.map((g) => (
              <Card key={g.name}>
                <Label>{g.name}</Label>
                {g.items.map((l) => (
                  <View key={l.service_item_id} style={styles.line}>
                    <ServiceImage uri={l.image_url} icon={l.icon ?? l.subcategory_icon} size={48} />
                    <View style={{ flex: 1 }}>
                      <Text style={{ fontWeight: '700', color: colors.text }} numberOfLines={2}>{l.name}</Text>
                      <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>
                        {l.unit_price == null ? 'Price on quote' : `${l.estimated ? 'from ' : ''}${money(l.unit_price)}${l.unit ? ' ' + l.unit : ''}`}
                      </Text>
                      <Text style={{ color: colors.emerald, fontWeight: '800' }}>{l.line_total == null ? 'Quote' : money(l.line_total)}</Text>
                    </View>
                    <AddControl id={l.service_item_id} compact />
                  </View>
                ))}
                <ExtraItems items={extras[g.items[0].subcategory_id] ?? []}
                  onChange={(v) => setExtras({ ...extras, [g.items[0].subcategory_id]: v })} />
              </Card>
            ))}

            <Card>
              <Row style={{ justifyContent: 'space-between' }}>
                <Text style={{ color: colors.textSecondary }}>Items</Text><Text style={{ fontWeight: '700', color: colors.text }}>{cart.item_count}</Text>
              </Row>
              {Number(cart.savings) > 0 ? (
                <Row style={{ justifyContent: 'space-between' }}>
                  <Text style={{ color: colors.emerald }}>Deal savings</Text><Text style={{ fontWeight: '700', color: colors.emerald }}>− {money(cart.savings)}</Text>
                </Row>
              ) : null}
              <Row style={{ justifyContent: 'space-between', marginTop: 4 }}>
                <Text style={{ fontWeight: '800', color: colors.text, fontSize: font.lg }}>{cart.has_estimates ? 'Estimated total' : 'Total'}</Text>
                <Text style={{ fontWeight: '900', color: colors.forest, fontSize: font.xl }}>{money(cart.estimated_total)}</Text>
              </Row>
              {cart.has_quote_items || extraCount ? <Text style={styles.hint}>+ {extraCount ? `${extraCount} extra item(s) and ` : ''}items priced by quotation</Text> : null}
              {cart.has_estimates ? <Text style={styles.hint}>Some items are "starting from" — the final amount is confirmed before work starts.</Text> : null}
              {cart.request_count > 1 ? <Text style={styles.hint}>This cart becomes {cart.request_count} bookings — one per service type, each with the right partner.</Text> : null}
            </Card>

            <Card>
              <Label>Service address</Label>
              <View style={{ marginTop: space.sm }}><AddressPicker value={addressId} onChange={setAddressId} /></View>
              <View style={{ marginTop: space.md }}>
                <TextField label="Preferred date & time (optional)" placeholder="2026-11-12 10:30" value={when} onChangeText={setWhen} />
                <TextField label="Note for your agent / partner (optional)" value={note} onChangeText={setNote} multiline placeholder="Gate code, parking, timing…" />
              </View>
            </Card>

            <Text style={{ fontSize: font.lg, fontWeight: '800', color: colors.text }}>How would you like to proceed?</Text>
            {([
              ['DIRECT_QUOTE', '🏪 Get quotes from verified partners', 'Goes straight to verified shops & service partners near you. They confirm availability and price for every item (including extras) — you compare, approve and pay. No waiting for anyone in between.'],
              ['AGENT_ASSISTED', `🎧 Send to ${agentFirst ? agentFirst + ' (my agent)' : 'my agent'}`, subscribed
                ? 'Your dedicated agent reviews the list, suggests better options or combos, and shares quotations before you pay.'
                : 'For subscribers with a dedicated agent. Subscribe free to get your own agent who handles everything for you.'],
            ] as const).map(([m, title, text]) => {
              const disabled = m === 'AGENT_ASSISTED' && !subscribed;
              return (
                <Pressable key={m} accessibilityRole="radio" accessibilityState={{ checked: mode === m, disabled }} onPress={() => (disabled ? null : setMode(m))}
                  style={[styles.option, mode === m && styles.optionOn, disabled && { opacity: 0.6 }]}>
                  <View style={[styles.radio, mode === m && styles.radioOn]}>{mode === m ? <View style={styles.radioDot} /> : null}</View>
                  <View style={{ flex: 1 }}>
                    <Text style={{ fontWeight: '800', color: colors.text }}>{title}</Text>
                    <Text style={{ color: colors.textSecondary, fontSize: font.xs, marginTop: 2 }}>{text}</Text>
                    {disabled ? (
                      <Pressable accessibilityRole="button" onPress={() => nav.push('SubscribeAgent')} style={{ marginTop: 6 }}>
                        <Text style={{ color: colors.emerald, fontWeight: '800' }}>⭐ Subscribe for a dedicated agent ›</Text>
                      </Pressable>
                    ) : null}
                  </View>
                </Pressable>
              );
            })}
            {act.error ? <ErrorState message={act.error} /> : null}
            <Button title={mode === 'DIRECT_QUOTE' ? `Get quotes • ${cart.item_count + extraCount} item(s)` : 'Send to my agent'}
              onPress={checkout} loading={act.busy} />
            <Notice tone="neutral" message="Listed prices are a guide — partners confirm the final price per item. Nothing is charged until you approve a quote." />
          </>
        )}
      </Screen>
    </View>
  );
}

export function WishlistScreen() {
  const nav = useNav();
  const q = useQuery(() => api.get<any[]>('/api/v1/customer/wishlist'));
  const { wishlist } = useCart();
  useOnRefocus(q.reload);
  const items = (q.data ?? []).filter((s) => wishlist.has(s.id));
  return (
    <View style={{ flex: 1, backgroundColor: colors.paleMint }}>
      <TopBar title="My Wishlist" onBack={nav.pop} />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : items.length === 0 ? (
          <Card><EmptyState title="No saved services yet" message="Tap ♡ on any service to save it here for later." action="Browse categories" onAction={() => nav.push('Categories')} /></Card>
        ) : items.map((s) => (
          <PopularServiceRow key={s.id} s={s}
            onPress={() => nav.push('Category', { categoryId: s.category_id, title: s.category_name, subcategoryId: s.subcategory_id })}
            onRequest={() => nav.push('NewRequest', { serviceItemId: s.id, title: s.name })} />
        ))}
      </Screen>
    </View>
  );
}

/** Free-text request that goes straight to the customer's dedicated agent. */
export function WriteToAgentScreen({ params }: { params: { categoryId?: string; categoryName?: string; prefill?: string; kind?: 'TASK' } }) {
  const nav = useNav();
  const agent = useQuery(() => api.get<any>('/api/v1/customer/agent'));
  const [message, setMessage] = useState(params.prefill ?? '');
  const [addressId, setAddressId] = useState<string | null>(null);
  const [when, setWhen] = useState('');
  const act = useAction();
  const key = useRef(newIdempotencyKey()).current;
  const name = agent.data?.primary?.display_name ?? 'the BUZZER team';
  const isTask = params.kind === 'TASK';

  const send = async () => {
    const w = parseWhen(when);
    if (w.error) {
      act.setError(w.error);
      return;
    }
    const r = await act.run(() => api.post<{ id: string }>('/api/v1/customer/agent-request', {
      message, category_id: params.categoryId ?? null, address_id: addressId, preferred_start: w.iso,
    }, key));
    if (r) nav.replace('RequestDetail', { id: r.id, justCreated: 'sent to ' + name });
  };

  return (
    <View style={{ flex: 1, backgroundColor: colors.paleMint }}>
      <TopBar title={isTask ? 'Request a task' : agent.data?.primary ? 'Write to my agent' : 'Ask for help'} onBack={nav.pop} />
      <Screen>
        <LinearGradient colors={gradient.header} style={styles.agentHead}>
          <Text style={{ color: '#CFEBD9', fontSize: font.xs }}>Your request goes directly to</Text>
          <Text style={{ color: colors.white, fontSize: font.xl, fontWeight: '800' }}>{name}</Text>
          <Text style={{ color: '#CFEBD9', fontSize: font.xs }}>{params.categoryName ? `About: ${params.categoryName}` : isTask ? 'Pickups, drops, collections and errands' : 'Anything you need — errands, bookings, help at home'}</Text>
        </LinearGradient>
        <Card>
          <TextField label={isTask ? 'What should we pick up / deliver / get done?' : 'What do you need?'} value={message} onChangeText={setMessage} multiline maxLength={2000}
            placeholder={isTask ? 'e.g. Pick up a document from my office in Hitech City and deliver it to Banjara Hills by 4 pm' : 'e.g. Need a tailor to alter 2 blazers by Friday, pickup from home'} />
          <TextField label="When (optional)" placeholder="2026-11-12 10:30" value={when} onChangeText={setWhen} />
          <Label>Address (optional)</Label>
          <View style={{ marginTop: space.sm }}><AddressPicker value={addressId} onChange={setAddressId} /></View>
        </Card>
        {act.error ? <ErrorState message={act.error} /> : null}
        <Button title={agent.data?.primary ? `Send to ${name.split(' ')[0]}` : 'Send to the BUZZER team'} onPress={send} loading={act.busy} disabled={message.trim().length < 5} />
      </Screen>
    </View>
  );
}

const styles = StyleSheet.create({
  line: { flexDirection: 'row', alignItems: 'center', gap: space.md, paddingVertical: space.sm, borderBottomWidth: 1, borderBottomColor: colors.border },
  hint: { color: colors.textSecondary, fontSize: font.xs, marginTop: 4 },
  option: { flexDirection: 'row', gap: space.md, alignItems: 'flex-start', backgroundColor: colors.white, borderRadius: 18, padding: space.md, borderWidth: 1.5,
    borderColor: colors.border },
  optionOn: { borderColor: colors.emerald, backgroundColor: colors.lightGreen },
  radio: { width: 22, height: 22, borderRadius: 11, borderWidth: 2, borderColor: colors.border, alignItems: 'center', justifyContent: 'center', marginTop: 2 },
  radioOn: { borderColor: colors.emerald },
  radioDot: { width: 10, height: 10, borderRadius: 5, backgroundColor: colors.emerald },
  success: { borderRadius: 24, padding: space.xl, alignItems: 'center', gap: space.sm },
  agentHead: { borderRadius: 20, padding: space.lg, gap: 2 },
});

