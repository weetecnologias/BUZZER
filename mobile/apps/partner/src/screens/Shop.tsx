import React, { useState } from 'react';
import { Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import * as DocumentPicker from 'expo-document-picker';
import {
  ago, api, API_URL, Badge, Button, callPhone, Card, Chip, colors, ConfirmDialog, dateTime, EmptyState, ErrorState, font, GradientHeader, KeyValue,
  Loading, money, Notice, Row, Screen, space, StatusBadge, statusLabel, TextField, Timeline, Toggle, useAction, useNav, useOnRefocus, useQuery,
} from '@buzzer/core';

/**
 * Shop mode of the partner app: orders (confirm, prepare, hand over with the customer's code), products & stock
 * (listings, propose new products, Excel upload) and the shop profile (partner types, locations, pickup / delivery).
 */

const GROUPS = [['NEW', 'New'], ['ACTIVE', 'In progress'], ['DONE', 'Done']] as const;
const SECTIONS = [['orders', '📦 Orders'], ['products', '🏷️ Products'], ['shop', '🏪 My shop']] as const;

export function ShopHomeScreen() {
  const [section, setSection] = useState<'orders' | 'products' | 'shop'>('orders');
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="My shop" subtitle="Orders · products & stock · locations" />
      <Row style={{ paddingHorizontal: space.lg, paddingTop: space.sm }}>
        {SECTIONS.map(([k, l]) => <Chip key={k} label={l} selected={section === k} onPress={() => setSection(k)} />)}
      </Row>
      {section === 'orders' ? <OrdersList /> : section === 'products' ? <ProductsList /> : <MyShop />}
    </View>
  );
}

// ----- Orders ------------------------------------------------------------------------------------------------

function OrdersList() {
  const nav = useNav();
  const [group, setGroup] = useState<'NEW' | 'ACTIVE' | 'DONE'>('NEW');
  const q = useQuery(() => api.get<any[]>(`/api/v1/partner/orders?group=${group}`), [group]);
  useOnRefocus(q.refresh);
  return (
    <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
      <Row>{GROUPS.map(([k, l]) => <Chip key={k} label={l} selected={group === k} onPress={() => setGroup(k)} />)}</Row>
      {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : (q.data ?? []).length === 0 ? (
        <EmptyState title={group === 'NEW' ? 'No new orders' : group === 'ACTIVE' ? 'Nothing in progress' : 'No past orders'}
          message="Orders from customers who buy your listed products, or accept your quotes, appear here." />
      ) : (q.data ?? []).map((o) => (
        <Card key={o.id} onPress={() => nav.push('ShopOrder', { id: o.id })} accessibilityLabel={`Order ${o.order_number}`}
          style={group === 'NEW' ? { borderColor: colors.secondary, borderWidth: 2 } : undefined}>
          <Row style={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
            <View style={{ flex: 1 }}>
              <Text style={{ fontWeight: '800', color: colors.text }}>{o.order_number}</Text>
              <Text style={{ color: colors.textSecondary, fontSize: font.xs }} numberOfLines={2}>{o.summary}</Text>
              <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>
                {o.fulfilment === 'PICKUP' ? '🛍️ Pickup' : '🛵 Delivery'} · {o.payment_method === 'ONLINE' ? 'Paid online' : 'Collect payment'} · {ago(o.created_at)}
                {o.source === 'RFQ' ? ' · from your quote' : ''}
              </Text>
            </View>
            <View style={{ alignItems: 'flex-end', gap: 4 }}>
              <StatusBadge status={o.status} />
              <Text style={{ fontWeight: '900', color: colors.forest }}>{money(o.total_amount)}</Text>
            </View>
          </Row>
        </Card>
      ))}
    </Screen>
  );
}

export function ShopOrderScreen({ params }: { params: { id: string } }) {
  const nav = useNav();
  const q = useQuery(() => api.get<any>(`/api/v1/partner/orders/${params.id}`), [params.id]);
  const act = useAction();
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const [code, setCode] = useState('');
  const o = q.data;
  const run = async (path: string, body: object = {}) => {
    const r = await act.run(() => api.post<any>(`/api/v1/partner/orders/${params.id}/${path}`, body));
    if (r) q.setData(r);
  };
  if (q.loading) return <View style={{ flex: 1 }}><GradientHeader title="Order" onBack={nav.pop} /><Loading /></View>;
  if (q.error || !o) return <View style={{ flex: 1 }}><GradientHeader title="Order" onBack={nav.pop} /><Screen><ErrorState message={q.error ?? 'Not found'} onRetry={q.reload} /></Screen></View>;
  const pickup = o.fulfilment === 'PICKUP';
  const f = o.fulfilment_snapshot ?? {};
  const actions: string[] = o.actions ?? [];
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title={o.order_number} subtitle={o.status_label} onBack={nav.pop} />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        <Card>
          <Row style={{ justifyContent: 'space-between' }}>
            <Text style={{ fontWeight: '800', color: colors.text }}>{pickup ? '🛍️ Customer picks up' : '🛵 You deliver'}</Text>
            <StatusBadge status={o.status} />
          </Row>
          <KeyValue k="Customer" v={o.customer_name ?? '—'} />
          {o.customer_phone ? <Button title={`📞 Call ${o.customer_name ?? 'customer'}`} small variant="outline" onPress={() => callPhone(o.customer_phone)} /> : null}
          {!pickup ? (
            f.delivery_address?.line1
              ? <KeyValue k="Deliver to" v={`${f.delivery_address.line1}${f.delivery_address.locality_name ? ', ' + f.delivery_address.locality_name : ''}`} />
              : <KeyValue k="Area" v={`${f.delivery_address?.locality_name ?? ''} — full address after you confirm`} />
          ) : null}
          <KeyValue k="Payment" v={o.payment_method === 'ONLINE' ? 'Paid online' : `Collect ${money(o.total_amount)} at ${pickup ? 'pickup' : 'delivery'}`} />
          <KeyValue k="Placed" v={dateTime(o.created_at)} />
          {o.quotation_number ? <KeyValue k="Your quote" v={o.quotation_number} /> : null}
          {o.customer_note ? <Notice tone="info" message={`Customer note: ${o.customer_note}`} /> : null}
        </Card>
        <Card>
          <Text style={{ fontWeight: '800', color: colors.text, marginBottom: 4 }}>Items</Text>
          {(o.items ?? []).map((i: any) => (
            <KeyValue key={i.line_no} k={`${i.quantity} × ${i.name}${i.variant_name && i.variant_name !== 'Standard' ? ` (${i.variant_name})` : ''}${i.brand ? ` · ${i.brand}` : ''}`} v={money(i.line_total)} />
          ))}
          <KeyValue k="Delivery" v={Number(o.delivery_fee) ? money(o.delivery_fee) : pickup ? '—' : 'Free'} />
          <Row style={{ justifyContent: 'space-between' }}>
            <Text style={{ fontWeight: '900', color: colors.text }}>Total</Text>
            <Text style={{ fontWeight: '900', color: colors.forest }}>{money(o.total_amount)}</Text>
          </Row>
        </Card>
        {act.error ? <Notice tone="danger" message={act.error} /> : null}
        {actions.includes('CONFIRM') ? <Button title="✓ Confirm — items available" onPress={() => run('confirm')} loading={act.busy} /> : null}
        {actions.includes('READY') ? <Button title="🛍️ Ready for pickup" onPress={() => run('ready')} loading={act.busy} /> : null}
        {actions.includes('DISPATCH') ? <Button title="🛵 Out for delivery" onPress={() => run('dispatch')} loading={act.busy} /> : null}
        {actions.includes('COMPLETE') ? (
          <Card>
            <Text style={{ fontWeight: '800', color: colors.text }}>Hand over</Text>
            <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>Ask the customer for their {pickup ? 'pickup' : 'delivery'} code (4 digits, shown in their app).</Text>
            <TextField value={code} onChangeText={(t) => setCode(t.replace(/[^0-9]/g, '').slice(0, 4))} keyboardType="number-pad" placeholder="Customer's code" />
            <Button title="Complete order" disabled={code.length !== 4} loading={act.busy} onPress={() => run('complete', { code })} />
          </Card>
        ) : null}
        {actions.includes('REJECT') ? <Button title="Can't fulfil — decline" variant="ghost" onPress={() => setRejecting(true)} /> : null}
        <Card>
          <Text style={{ fontWeight: '800', color: colors.text, marginBottom: space.sm }}>Timeline</Text>
          <Timeline history={o.timeline} />
        </Card>
      </Screen>
      <ConfirmDialog visible={rejecting} title="Decline this order?" danger confirmLabel="Decline" loading={act.busy}
        message="The customer is told why; any online payment is refunded." onCancel={() => setRejecting(false)}
        onConfirm={async () => { await run('reject', { reason }); setRejecting(false); }}>
        <TextField label="Reason" value={reason} onChangeText={setReason} placeholder="e.g. Out of stock" />
      </ConfirmDialog>
    </View>
  );
}

// ----- Products & stock ------------------------------------------------------------------------------------

const STOCK = ['IN_STOCK', 'LIMITED', 'OUT_OF_STOCK', 'ON_REQUEST'];

function ProductsList() {
  const nav = useNav();
  const q = useQuery(() => api.get<any[]>('/api/v1/partner/listings'));
  useOnRefocus(q.refresh);
  const act = useAction();
  const [result, setResult] = useState<string | null>(null);
  const setStock = async (l: any, stock: string) => {
    const r = await act.run(() => api.post<any>('/api/v1/partner/listings', { ...form(l), stock_status: stock }));
    if (r) q.refresh();
  };
  const upload = async () => {
    const pick = await DocumentPicker.getDocumentAsync({ type: ['application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'], copyToCacheDirectory: true });
    if (pick.canceled || !pick.assets?.length) return;
    const a = pick.assets[0];
    const fd = new FormData();
    if (Platform.OS === 'web' && (a as any).file) fd.append('file', (a as any).file, a.name);
    else fd.append('file', { uri: a.uri, name: a.name, type: a.mimeType ?? 'application/octet-stream' } as any);
    const r = await act.run(() => api.upload<any>('/api/v1/partner/listings/excel', fd));
    if (r) {
      setResult(`${r.saved} saved, ${r.skipped} skipped${r.errors.length ? `, ${r.errors.length} error(s): ${r.errors.slice(0, 3).map((e: any) => `row ${e.row}: ${e.error}`).join('; ')}` : ''}`);
      q.refresh();
    }
  };
  return (
    <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
      <Row>
        <Button title="＋ Add a product" small style={{ flex: 1 }} onPress={() => nav.push('Catalogue')} />
        <Button title="📤 Excel upload" small variant="outline" style={{ flex: 1 }} onPress={upload} />
      </Row>
      {Platform.OS === 'web' ? (
        <Button title="📥 Download my product sheet (.xlsx)" small variant="ghost" onPress={async () => {
          const blob = await api.download('/api/v1/partner/listings/excel');
          const url = URL.createObjectURL(blob);
          const link = document.createElement('a');
          link.href = url;
          link.download = 'buzzer-my-products.xlsx';
          link.click();
          URL.revokeObjectURL(url);
        }} />
      ) : <Text style={styles.small}>Tip: download the Excel sheet from the partner web app ({API_URL.replace(/:\d+$/, '')}) to update many prices at once.</Text>}
      {result ? <Notice tone="success" message={result} /> : null}
      {act.error ? <Notice tone="danger" message={act.error} /> : null}
      <Notice tone="info" message="Price and stock changes go live at once. New products are checked by BUZZER first. Keep stock status up to date — customers see when you last updated it." />
      {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : (q.data ?? []).length === 0 ? (
        <EmptyState title="No products yet" message="Add products from the BUZZER catalogue with your price and stock." />
      ) : (q.data ?? []).map((l) => (
        <Card key={l.id} onPress={() => nav.push('ListingForm', { listing: l })} accessibilityLabel={l.product_name}>
          <Row style={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
            <View style={{ flex: 1 }}>
              <Text style={{ fontWeight: '800', color: colors.text }}>{l.product_name}{l.variant_name !== 'Standard' ? ` (${l.variant_name})` : ''}</Text>
              <Text style={styles.small}>{l.brand ? `${l.brand} · ` : ''}{l.subcategory_name} · updated {ago(l.stock_updated_at)}</Text>
              {l.review_note ? <Text style={[styles.small, { color: colors.warning }]}>{l.review_note}</Text> : null}
            </View>
            <View style={{ alignItems: 'flex-end', gap: 4 }}>
              <Text style={{ fontWeight: '900', color: colors.forest }}>{money(l.price)}</Text>
              <Badge label={l.status === 'PENDING' ? 'In review' : statusLabel(l.status)} tone={l.status === 'ACTIVE' ? 'success' : l.status === 'PENDING' ? 'warning' : 'danger'} />
            </View>
          </Row>
          {l.status !== 'REJECTED' ? (
            <Row style={{ flexWrap: 'wrap', marginTop: 4 }}>
              {STOCK.map((s) => <Chip key={s} label={statusLabel(s)} selected={l.stock_status === s} onPress={() => setStock(l, s)} />)}
            </Row>
          ) : null}
        </Card>
      ))}
    </Screen>
  );
}

const form = (l: any) => ({
  variant_id: l.variant_id, price: Number(l.price), mrp: l.mrp == null ? null : Number(l.mrp), stock_status: l.stock_status, moq: l.moq,
  max_order_qty: l.max_order_qty ?? null, bulk_price: l.bulk_price == null ? null : Number(l.bulk_price), bulk_min_qty: l.bulk_min_qty ?? null,
  lead_time_days: l.lead_time_days ?? 0, notes: l.notes ?? null,
});

export function CatalogueScreen() {
  const nav = useNav();
  const [q, setQ] = useState('');
  const list = useQuery(() => api.get<any[]>(`/api/v1/partner/catalogue/products${q.trim().length >= 2 ? `?q=${encodeURIComponent(q.trim())}` : ''}`), [q.trim().length >= 2 ? q.trim() : '']);
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="Add a product" subtitle="From the BUZZER catalogue (your approved categories)" onBack={nav.pop} />
      <Screen refreshing={list.refreshing} onRefresh={list.refresh}>
        <TextField value={q} onChangeText={setQ} placeholder="Search name, brand or SKU" />
        {list.loading ? <Loading /> : list.error ? <ErrorState message={list.error} onRetry={list.reload} /> : (list.data ?? []).length === 0 ? (
          <EmptyState title="Nothing found" message="Not in the catalogue? Propose it and BUZZER adds it after a quick check." />
        ) : (list.data ?? []).map((p) => (
          <Card key={p.variant_id} onPress={() => nav.push('ListingForm', { listing: p.listing_id ? { ...p, id: p.listing_id, product_name: p.name } : { variant_id: p.variant_id, product_name: p.name, variant_name: p.variant_name } })}>
            <Row style={{ justifyContent: 'space-between' }}>
              <View style={{ flex: 1 }}>
                <Text style={{ fontWeight: '700', color: colors.text }}>{p.name}{p.variant_name !== 'Standard' ? ` (${p.variant_name})` : ''}</Text>
                <Text style={styles.small}>{p.brand ? `${p.brand} · ` : ''}{p.subcategory_name} · {p.sku}</Text>
              </View>
              {p.listing_id ? <Badge label={`Listed ${money(p.price)}`} tone="success" /> : <Text style={{ color: colors.emerald, fontWeight: '900' }}>＋</Text>}
            </Row>
          </Card>
        ))}
        <Button title="💡 Propose a new product" variant="secondary" onPress={() => nav.push('ProposeProduct')} />
      </Screen>
    </View>
  );
}

export function ListingFormScreen({ params }: { params: { listing: any } }) {
  const nav = useNav();
  const l = params.listing ?? {};
  const [price, setPrice] = useState(l.price != null ? String(Number(l.price)) : '');
  const [mrp, setMrp] = useState(l.mrp != null ? String(Number(l.mrp)) : '');
  const [stock, setStock] = useState<string>(l.stock_status ?? 'IN_STOCK');
  const [moq, setMoq] = useState(l.moq != null ? String(l.moq) : '1');
  const [bulkPrice, setBulkPrice] = useState(l.bulk_price != null ? String(Number(l.bulk_price)) : '');
  const [bulkMin, setBulkMin] = useState(l.bulk_min_qty != null ? String(l.bulk_min_qty) : '');
  const [lead, setLead] = useState(l.lead_time_days != null ? String(l.lead_time_days) : '0');
  const [notes, setNotes] = useState(l.notes ?? '');
  const [paused, setPaused] = useState(l.status === 'PAUSED');
  const act = useAction();
  const save = async () => {
    const r = await act.run(() => api.post('/api/v1/partner/listings', {
      variant_id: l.variant_id, price: Number(price), mrp: mrp ? Number(mrp) : null, stock_status: stock, moq: Number(moq) || 1,
      bulk_price: bulkPrice ? Number(bulkPrice) : null, bulk_min_qty: bulkMin ? Number(bulkMin) : null, lead_time_days: Number(lead) || 0,
      notes: notes.trim() || null, paused: l.id && ['ACTIVE', 'PAUSED'].includes(l.status) ? paused : null,
    }));
    if (r) nav.pop();
  };
  const dec = (t: string) => t.replace(/[^0-9.]/g, '');
  const int = (t: string) => t.replace(/[^0-9]/g, '');
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title={l.product_name ?? 'Product'} subtitle={l.variant_name && l.variant_name !== 'Standard' ? l.variant_name : 'Your price & stock'} onBack={nav.pop} />
      <Screen>
        {l.status === 'PENDING' ? <Notice tone="warning" message="BUZZER is reviewing this listing. You can still change it." /> : null}
        <Row>
          <View style={{ flex: 1 }}><TextField label="Your price ₹ (incl. GST)" value={price} onChangeText={(t) => setPrice(dec(t))} keyboardType="decimal-pad" /></View>
          <View style={{ flex: 1 }}><TextField label="MRP ₹ (optional)" value={mrp} onChangeText={(t) => setMrp(dec(t))} keyboardType="decimal-pad" /></View>
        </Row>
        <Text style={{ fontWeight: '700', color: colors.text }}>Stock</Text>
        <Row style={{ flexWrap: 'wrap' }}>{STOCK.map((s) => <Chip key={s} label={statusLabel(s)} selected={stock === s} onPress={() => setStock(s)} />)}</Row>
        <Row>
          <View style={{ flex: 1 }}><TextField label="Minimum order qty" value={moq} onChangeText={(t) => setMoq(int(t))} keyboardType="number-pad" /></View>
          <View style={{ flex: 1 }}><TextField label="Ready in (days)" value={lead} onChangeText={(t) => setLead(int(t))} keyboardType="number-pad" /></View>
        </Row>
        <Text style={{ fontWeight: '700', color: colors.text }}>Bulk price (optional)</Text>
        <Row>
          <View style={{ flex: 1 }}><TextField label="Price ₹ each" value={bulkPrice} onChangeText={(t) => setBulkPrice(dec(t))} keyboardType="decimal-pad" /></View>
          <View style={{ flex: 1 }}><TextField label="From quantity" value={bulkMin} onChangeText={(t) => setBulkMin(int(t))} keyboardType="number-pad" /></View>
        </Row>
        <TextField label="Notes (optional)" value={notes} onChangeText={setNotes} maxLength={300} />
        {l.id && ['ACTIVE', 'PAUSED'].includes(l.status) ? <Toggle label="Pause (hide from customers)" value={paused} onChange={setPaused} /> : null}
        {act.error ? <Notice tone="danger" message={act.error} /> : null}
        <Button title={l.id ? 'Save' : 'Add to my shop'} onPress={save} loading={act.busy} disabled={!(Number(price) > 0)} />
      </Screen>
    </View>
  );
}

export function ProposeProductScreen() {
  const nav = useNav();
  const me = useQuery(() => api.get<any>('/api/v1/partner/me'));
  const caps: any[] = (me.data?.capabilities ?? []).filter((c: any) => c.status === 'APPROVED');
  const [sub, setSub] = useState<string | undefined>();
  const [name, setName] = useState('');
  const [brand, setBrand] = useState('');
  const [unit, setUnit] = useState('each');
  const [price, setPrice] = useState('');
  const [specs, setSpecs] = useState('');
  const act = useAction();
  const submit = async () => {
    const spec: Record<string, string> = {};
    specs.split('\n').forEach((line) => {
      const [k, ...v] = line.split(':');
      if (k?.trim() && v.join(':').trim()) spec[k.trim()] = v.join(':').trim();
    });
    const r = await act.run(() => api.post('/api/v1/partner/products', { subcategory_id: sub, name: name.trim(), brand: brand.trim() || null, unit: unit.trim() || 'each',
      price: Number(price), specs: spec }));
    if (r) nav.pop();
  };
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="Propose a product" subtitle="BUZZER checks it, then customers can buy it from you" onBack={nav.pop} />
      <Screen>
        <Text style={{ fontWeight: '700', color: colors.text }}>Category</Text>
        {me.loading ? <Loading /> : (
          <Row style={{ flexWrap: 'wrap' }}>
            {caps.map((c) => <Chip key={c.subcategory_id} label={`${c.category_name} › ${c.subcategory_name}`} selected={sub === c.subcategory_id} onPress={() => setSub(c.subcategory_id)} />)}
          </Row>
        )}
        <TextField label="Product name" value={name} onChangeText={setName} placeholder="e.g. Havells Ambrose 1200 mm ceiling fan" maxLength={200} />
        <Row>
          <View style={{ flex: 1 }}><TextField label="Brand" value={brand} onChangeText={setBrand} /></View>
          <View style={{ flex: 1 }}><TextField label="Unit" value={unit} onChangeText={setUnit} /></View>
        </Row>
        <TextField label="Your price ₹ (incl. GST)" value={price} onChangeText={(t) => setPrice(t.replace(/[^0-9.]/g, ''))} keyboardType="decimal-pad" />
        <TextField label="Specifications (one per line, Name: value)" value={specs} onChangeText={setSpecs} multiline placeholder={'Sweep: 1200 mm\nColour: White'} />
        {act.error ? <Notice tone="danger" message={act.error} /> : null}
        <Button title="Send for review" onPress={submit} loading={act.busy} disabled={!sub || name.trim().length < 3 || !(Number(price) > 0)} />
      </Screen>
    </View>
  );
}

// ----- My shop: partner types and locations ---------------------------------------------------------------

const ROLE_LABEL: Record<string, string> = {
  RETAIL_SHOP: 'Retail shop', WHOLESALER: 'Wholesaler', DISTRIBUTOR: 'Distributor', SERVICE_PROVIDER: 'Service provider', TASK_PARTNER: 'Task & delivery partner',
};

function MyShop() {
  const nav = useNav();
  const q = useQuery(() => api.get<any>('/api/v1/partner/shop'));
  useOnRefocus(q.refresh);
  const act = useAction();
  const s = q.data;
  const has = (r: string) => (s?.roles ?? []).some((x: any) => x.role === r);
  return (
    <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
      {q.loading ? <Loading /> : q.error || !s ? <ErrorState message={q.error ?? 'Not found'} onRetry={q.reload} /> : (
        <>
          {!s.is_shop ? <Notice tone="warning" message="Your shop isn't approved yet. BUZZER approves new shops after verifying your registration, KYC and categories." /> : null}
          <Card>
            <Text style={{ fontWeight: '800', color: colors.text, marginBottom: 4 }}>Partner types</Text>
            {(s.roles ?? []).map((r: any) => (
              <Row key={r.role} style={{ justifyContent: 'space-between', paddingVertical: 4 }}>
                <Text style={{ color: colors.text }}>{ROLE_LABEL[r.role] ?? r.role}</Text>
                <StatusBadge status={r.status} />
              </Row>
            ))}
            <Row style={{ flexWrap: 'wrap', marginTop: 4 }}>
              {['WHOLESALER', 'DISTRIBUTOR', 'RETAIL_SHOP'].filter((r) => !has(r)).map((r) => (
                <Button key={r} title={`＋ ${ROLE_LABEL[r]}`} small variant="outline" onPress={async () => { if (await act.run(() => api.post('/api/v1/partner/roles', { role: r }))) q.refresh(); }} />
              ))}
            </Row>
          </Card>
          <Row style={{ flexWrap: 'wrap' }}>
            <Badge label={`${s.counts.active_listings} products live`} tone="success" />
            {Number(s.counts.pending_listings) ? <Badge label={`${s.counts.pending_listings} in review`} tone="warning" /> : null}
            {Number(s.counts.out_of_stock) ? <Badge label={`${s.counts.out_of_stock} out of stock`} tone="danger" /> : null}
          </Row>
          <Text style={{ fontWeight: '800', color: colors.text, fontSize: font.lg }}>Locations</Text>
          {(s.locations ?? []).map((l: any) => (
            <Card key={l.id} onPress={() => nav.push('LocationForm', { location: l })} style={!l.active ? { opacity: 0.6 } : undefined}>
              <Row style={{ justifyContent: 'space-between' }}>
                <Text style={{ fontWeight: '800', color: colors.text, flex: 1 }}>📍 {l.name}</Text>
                {l.is_primary ? <Badge label="Main" tone="info" /> : null}
              </Row>
              <Text style={styles.small}>{[l.address, l.locality_name].filter(Boolean).join(', ')}</Text>
              <Text style={styles.small}>
                {l.pickup_enabled ? 'Pickup' : ''}{l.pickup_enabled && l.delivery_enabled ? ' · ' : ''}{l.delivery_enabled ? `Delivery ${Number(l.delivery_fee) ? money(l.delivery_fee) : 'free'} within ${Number(l.service_radius_km)} km` : ''}
                {l.opening_hours ? ` · ${l.opening_hours}` : ''}
              </Text>
            </Card>
          ))}
          <Button title="＋ Add a location / branch" variant="outline" onPress={() => nav.push('LocationForm', {})} />
          {act.error ? <Notice tone="danger" message={act.error} /> : null}
        </>
      )}
    </Screen>
  );
}

export function LocationFormScreen({ params }: { params: { location?: any } }) {
  const nav = useNav();
  const l = params.location ?? {};
  const [name, setName] = useState(l.name ?? '');
  const [address, setAddress] = useState(l.address ?? '');
  const [hours, setHours] = useState(l.opening_hours ?? '');
  const [radiusKm, setRadiusKm] = useState(l.service_radius_km != null ? String(Number(l.service_radius_km)) : '10');
  const [pickup, setPickup] = useState(l.pickup_enabled ?? true);
  const [delivery, setDelivery] = useState(l.delivery_enabled ?? false);
  const [fee, setFee] = useState(l.delivery_fee != null ? String(Number(l.delivery_fee)) : '0');
  const [freeAbove, setFreeAbove] = useState(l.free_delivery_above != null ? String(Number(l.free_delivery_above)) : '');
  const [minOrder, setMinOrder] = useState(l.min_order_value != null ? String(Number(l.min_order_value)) : '0');
  const [active, setActive] = useState(l.active ?? true);
  const act = useAction();
  const dec = (t: string) => t.replace(/[^0-9.]/g, '');
  const save = async () => {
    const body = { name: name.trim(), address: address.trim() || null, locality_id: l.locality_id ?? null, latitude: l.latitude ?? null, longitude: l.longitude ?? null,
      service_radius_km: Number(radiusKm) || 10, opening_hours: hours.trim() || null, phone: l.phone ?? null, pickup_enabled: pickup, delivery_enabled: delivery,
      delivery_fee: Number(fee) || 0, free_delivery_above: freeAbove ? Number(freeAbove) : null, min_order_value: Number(minOrder) || 0, active };
    const r = await act.run(() => (l.id ? api.put(`/api/v1/partner/locations/${l.id}`, body) : api.post('/api/v1/partner/locations', body)));
    if (r) nav.pop();
  };
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title={l.id ? 'Edit location' : 'New location'} subtitle="Where customers pick up / how far you deliver" onBack={nav.pop} />
      <Screen>
        <TextField label="Name" value={name} onChangeText={setName} placeholder="e.g. Main shop, Kukatpally" />
        <TextField label="Address" value={address} onChangeText={setAddress} />
        <TextField label="Opening hours" value={hours} onChangeText={setHours} placeholder="9 AM - 9 PM, closed Sunday" />
        <TextField label="Serve customers within (km)" value={radiusKm} onChangeText={(t) => setRadiusKm(dec(t))} keyboardType="decimal-pad" />
        <Toggle label="Customers can pick up" value={pickup} onChange={setPickup} />
        <Toggle label="We deliver" value={delivery} onChange={setDelivery} />
        {delivery ? (
          <>
            <Row>
              <View style={{ flex: 1 }}><TextField label="Delivery fee ₹" value={fee} onChangeText={(t) => setFee(dec(t))} keyboardType="decimal-pad" /></View>
              <View style={{ flex: 1 }}><TextField label="Free above ₹" value={freeAbove} onChangeText={(t) => setFreeAbove(dec(t))} keyboardType="decimal-pad" /></View>
            </Row>
            <TextField label="Minimum order for delivery ₹" value={minOrder} onChangeText={(t) => setMinOrder(dec(t))} keyboardType="decimal-pad" />
          </>
        ) : null}
        {l.id ? <Toggle label="Location open" value={active} onChange={setActive} /> : null}
        {act.error ? <Notice tone="danger" message={act.error} /> : null}
        <Button title="Save" onPress={save} loading={act.busy} disabled={name.trim().length < 2} />
      </Screen>
    </View>
  );
}

const styles = StyleSheet.create({
  small: { color: colors.textSecondary, fontSize: font.xs },
});
