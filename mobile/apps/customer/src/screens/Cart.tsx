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

/** Cart & checkout: book directly at listed prices, or send the cart to the dedicated agent. */
export function CartScreen() {
  const nav = useNav();
  const { cart, refresh, error: cartError } = useCart();
  const [addressId, setAddressId] = useState<string | null>(null);
  const [when, setWhen] = useState('');
  const [note, setNote] = useState('');
  const [mode, setMode] = useState<'SELF' | 'AGENT_ASSISTED'>('SELF');
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
    const res = await act.run(() => api.post<{ id: string; request_number: string }[]>('/api/v1/customer/cart/checkout',
      { mode, address_id: addressId, preferred_start: w.iso, note: note || null }, key.current));
    if (res) {
      key.current = newIdempotencyKey();
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
              {mode === 'SELF' ? 'Booked at listed prices' : 'Sent to your agent'}
            </Text>
            <Text style={{ color: '#D7F2E1', textAlign: 'center' }}>
              {mode === 'SELF'
                ? 'Your agent is assigning verified partners and will confirm the slot. You pay once the partner is confirmed.'
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
              {cart.has_quote_items ? <Text style={styles.hint}>+ items priced by quotation (your agent confirms them)</Text> : null}
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
              ['SELF', '⚡ Book now at listed prices', 'You confirm these prices. Your agent assigns a verified partner and confirms the slot. Pay after the partner is confirmed.'],
              ['AGENT_ASSISTED', '🎧 Send to my agent', 'Your agent reviews the list, suggests better options or combos, and shares quotations before you pay.'],
            ] as const).map(([m, title, text]) => (
              <Pressable key={m} accessibilityRole="radio" accessibilityState={{ checked: mode === m }} onPress={() => setMode(m)}
                style={[styles.option, mode === m && styles.optionOn]}>
                <View style={[styles.radio, mode === m && styles.radioOn]}>{mode === m ? <View style={styles.radioDot} /> : null}</View>
                <View style={{ flex: 1 }}>
                  <Text style={{ fontWeight: '800', color: colors.text }}>{title}</Text>
                  <Text style={{ color: colors.textSecondary, fontSize: font.xs, marginTop: 2 }}>{text}</Text>
                </View>
              </Pressable>
            ))}
            {act.error ? <ErrorState message={act.error} /> : null}
            <Button title={mode === 'SELF' ? `Book now • ${money(cart.estimated_total)}${cart.has_quote_items ? ' + quotes' : ''}` : 'Send to my agent'}
              onPress={checkout} loading={act.busy} />
            <Notice tone="neutral" message="Prices are set by BUZZER partners and may vary for extra work. Nothing is charged until you approve." />
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
export function WriteToAgentScreen({ params }: { params: { categoryId?: string; categoryName?: string } }) {
  const nav = useNav();
  const agent = useQuery(() => api.get<any>('/api/v1/customer/agent'));
  const [message, setMessage] = useState('');
  const [addressId, setAddressId] = useState<string | null>(null);
  const [when, setWhen] = useState('');
  const act = useAction();
  const key = useRef(newIdempotencyKey()).current;
  const name = agent.data?.primary?.display_name ?? 'your agent';

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
      <TopBar title="Write to my agent" onBack={nav.pop} />
      <Screen>
        <LinearGradient colors={gradient.header} style={styles.agentHead}>
          <Text style={{ color: '#CFEBD9', fontSize: font.xs }}>Your request goes directly to</Text>
          <Text style={{ color: colors.white, fontSize: font.xl, fontWeight: '800' }}>{name}</Text>
          <Text style={{ color: '#CFEBD9', fontSize: font.xs }}>{params.categoryName ? `About: ${params.categoryName}` : 'Anything you need — errands, bookings, help at home'}</Text>
        </LinearGradient>
        <Card>
          <TextField label="What do you need?" value={message} onChangeText={setMessage} multiline maxLength={2000}
            placeholder="e.g. Need a tailor to alter 2 blazers by Friday, pickup from home" />
          <TextField label="When (optional)" placeholder="2026-11-12 10:30" value={when} onChangeText={setWhen} />
          <Label>Address (optional)</Label>
          <View style={{ marginTop: space.sm }}><AddressPicker value={addressId} onChange={setAddressId} /></View>
        </Card>
        {act.error ? <ErrorState message={act.error} /> : null}
        <Button title="Send to my agent" onPress={send} loading={act.busy} disabled={message.trim().length < 5} />
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

