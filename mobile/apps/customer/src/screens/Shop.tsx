import React, { useEffect, useRef, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import {
  ago, api, ApiError, Badge, Button, callPhone, Card, Chip, colors, ConfirmDialog, dateTime, EmptyState, ErrorState, font, GradientHeader, KeyValue,
  Loading, money, newIdempotencyKey, Notice, radius, Row, Screen, space, StatusBadge, statusLabel, statusTone, TextField, Timeline, Toggle,
  useAction, useNav, useOnRefocus, useQuery,
} from '@buzzer/core';
import { CircleButton, ServiceImage } from '../components/Catalog';

/**
 * Shop & Buy: products from local shops with price comparison, a one-shop product cart, checkout (pickup / delivery,
 * pay online or at pickup / on delivery), order tracking with the hand-over code, and quotation requests (RFQ) with a
 * side-by-side comparison of the shops' quotes.
 */

// ----- Where the customer is (for "nearest shop" and delivery) ------------------------------------------------

export function useShopperLocation() {
  const q = useQuery(() => api.get<any[]>('/api/v1/customer/addresses'));
  const addresses = q.data ?? [];
  const address = addresses[0];
  const qs = address
    ? address.latitude != null ? `lat=${address.latitude}&lng=${address.longitude}` : address.locality_id ? `localityId=${address.locality_id}` : ''
    : '';
  return { address, addresses, qs, loading: q.loading, refresh: q.refresh };
}

const km = (v: unknown) => (v == null ? null : `${Number(v).toFixed(1)} km`);
const ROLE: Record<string, string> = { RETAIL_SHOP: 'Shop', WHOLESALER: 'Wholesaler', DISTRIBUTOR: 'Distributor' };

function StockLine({ status, updated }: { status: string; updated?: string }) {
  return (
    <Text style={{ fontSize: font.xs, color: status === 'OUT_OF_STOCK' ? colors.danger : colors.textSecondary }}>
      {statusLabel(status)}{updated ? ` · shop updated ${ago(updated)}` : ''}
    </Text>
  );
}

/** Floating bar for the product cart (one shop). */
function ProductCartBar({ version = 0 }: { version?: number }) {
  const nav = useNav();
  const q = useQuery(() => api.get<any>('/api/v1/customer/product-cart'), [version]);
  useOnRefocus(q.refresh);
  const c = q.data;
  if (!c || !c.item_count) return null;
  return (
    <Pressable accessibilityRole="button" accessibilityLabel="Open cart" onPress={() => nav.push('ProductCart')} style={styles.cartBar}>
      <Text style={{ color: colors.white, fontWeight: '800', flex: 1 }}>🛒 {c.item_count} item(s) from {c.shop?.name}</Text>
      <Text style={{ color: colors.white, fontWeight: '900' }}>{money(c.items_total)} ›</Text>
    </Pressable>
  );
}

// ----- Shop & Buy home ------------------------------------------------------------------------------------------

export function ShopScreen({ params }: { params: { categoryId?: string; title?: string; q?: string } }) {
  const nav = useNav();
  const loc = useShopperLocation();
  const [q, setQ] = useState(params.q ?? '');
  const [debounced, setDebounced] = useState(q);
  const [cat, setCat] = useState<string | undefined>(params.categoryId);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(q.trim()), 300);
    return () => clearTimeout(t);
  }, [q]);
  const cats = useQuery(() => api.get<any[]>('/api/v1/catalogue/categories?vertical=MARKETPLACE'));
  const filter = `${loc.qs ? loc.qs + '&' : ''}${cat ? `categoryId=${cat}&` : ''}`;
  const products = useQuery(() => api.get<any>(`/api/v1/marketplace/products?${filter}${debounced ? `q=${encodeURIComponent(debounced)}&` : ''}size=30`),
    [filter, debounced]);
  const shops = useQuery(() => api.get<any[]>(`/api/v1/marketplace/shops?${filter}limit=12`), [filter]);
  useOnRefocus(products.refresh);
  const items: any[] = products.data?.items ?? [];
  return (
    <View style={{ flex: 1, backgroundColor: colors.paleMint }}>
      <GradientHeader title={params.title ?? 'Shop & Buy'} subtitle={loc.address ? `Near ${loc.address.locality_name ?? loc.address.line1}` : 'Products from local shops'}
        onBack={nav.pop} right={<CircleButton glyphText="🛒" label="Cart" onPress={() => nav.push('ProductCart')} />} />
      <View style={{ paddingHorizontal: space.lg, paddingTop: space.sm }}>
        <View style={styles.searchBox}>
          <Text>🔍</Text>
          <TextInput value={q} onChangeText={setQ} placeholder="Search products or brands (e.g. MCB, cement, atta)" placeholderTextColor={colors.textSecondary}
            style={styles.searchInput} accessibilityLabel="Search products" returnKeyType="search" />
          {q ? <Pressable accessibilityLabel="Clear search" onPress={() => setQ('')}><Text style={{ color: colors.textSecondary, fontSize: 18 }}>×</Text></Pressable> : null}
        </View>
      </View>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ flexGrow: 0 }}
        contentContainerStyle={{ gap: space.sm, paddingHorizontal: space.lg, paddingVertical: space.sm }}>
        <Chip label="All" selected={!cat} onPress={() => setCat(undefined)} />
        {(cats.data ?? []).map((c) => <Chip key={c.id} label={c.name} selected={cat === c.id} onPress={() => setCat(c.id)} />)}
      </ScrollView>
      <Screen refreshing={products.refreshing} onRefresh={() => { products.refresh(); shops.refresh(); }}>
        {!debounced && (shops.data ?? []).length ? (
          <>
            <Text style={styles.section}>🏪 Shops near you</Text>
            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: space.sm, paddingRight: space.lg }}
              style={{ marginHorizontal: -space.lg, paddingLeft: space.lg }}>
              {(shops.data ?? []).map((s) => (
                <Pressable key={s.id} accessibilityRole="button" accessibilityLabel={s.display_name} style={styles.shopTile} onPress={() => nav.push('ShopProfile', { id: s.id })}>
                  <Text style={{ fontSize: 26 }}>🏪</Text>
                  <Text style={{ fontWeight: '800', color: colors.text }} numberOfLines={1}>{s.display_name}</Text>
                  <Text style={{ color: colors.textSecondary, fontSize: font.xs }} numberOfLines={1}>
                    {(s.roles ?? '').split(',').filter(Boolean).map((r: string) => ROLE[r] ?? r).join(' · ')}{s.distance_km != null ? ` · ${km(s.distance_km)}` : ''}
                  </Text>
                  <Text style={{ color: colors.emerald, fontSize: font.xs, fontWeight: '700' }}>
                    {[s.pickup_enabled ? 'Pickup' : null, s.delivery_enabled ? 'Delivery' : null].filter(Boolean).join(' · ')}
                  </Text>
                </Pressable>
              ))}
            </ScrollView>
          </>
        ) : null}
        <Text style={styles.section}>{debounced ? `Results for “${debounced}”` : 'Products'}</Text>
        {products.loading ? <Loading /> : products.error ? <ErrorState message={products.error} onRetry={products.reload} /> : items.length === 0 ? (
          <EmptyState title="No shop lists this yet" message="Send a quotation request — local shops reply with their price and availability."
            action="🧾 Request quotations" onAction={() => nav.push('RfqCreate', { categoryId: cat, prefill: debounced })} />
        ) : items.map((p) => (
          <Card key={p.id} onPress={() => nav.push('Product', { id: p.id })} accessibilityLabel={p.name}>
            <Row style={{ alignItems: 'flex-start' }}>
              <ServiceImage uri={p.image_url} icon={p.category_icon} size={56} />
              <View style={{ flex: 1 }}>
                <Text style={{ fontWeight: '800', color: colors.text }}>{p.name}</Text>
                <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{p.brand ? `${p.brand} · ` : ''}{p.subcategory_name}</Text>
                <Row style={{ marginTop: 4, flexWrap: 'wrap' }}>
                  <Text style={{ color: colors.forest, fontWeight: '900' }}>
                    {Number(p.min_price) === Number(p.max_price) ? money(p.min_price) : `${money(p.min_price)} – ${money(p.max_price)}`}
                  </Text>
                  <Badge label={`${p.shop_count} shop${Number(p.shop_count) === 1 ? '' : 's'}`} tone="info" />
                  {p.nearest_km != null ? <Badge label={`nearest ${km(p.nearest_km)}`} tone="neutral" /> : null}
                  {!p.in_stock ? <Badge label="Out of stock" tone="danger" /> : null}
                </Row>
              </View>
              <Text style={{ color: colors.emerald, fontWeight: '900', fontSize: 20 }}>›</Text>
            </Row>
          </Card>
        ))}
        <Card>
          <Text style={{ fontWeight: '800', color: colors.text }}>Buying in bulk or can't find an item?</Text>
          <Text style={{ color: colors.textSecondary, marginVertical: 4 }}>
            List what you need (brand, size, quantity) — 5 to 10 nearby shops and wholesalers send item-wise quotes. You compare and accept one.
          </Text>
          <Button title="🧾 Request quotations" variant="secondary" onPress={() => nav.push('RfqCreate', { categoryId: cat })} />
        </Card>
      </Screen>
      <ProductCartBar />
    </View>
  );
}

// ----- Product page: every shop's offer ---------------------------------------------------------------------

function AddToCart({ offer, onAdded }: { offer: any; onAdded: () => void }) {
  const [qty, setQty] = useState<number>(Number(offer.moq) || 1);
  const [conflict, setConflict] = useState<string | null>(null);
  const act = useAction();
  const add = async (replace: boolean) => {
    try {
      await api.post('/api/v1/customer/product-cart/items', { listing_id: offer.listing_id, quantity: qty, replace_cart: replace });
      setConflict(null);
      onAdded();
    } catch (e) {
      if (e instanceof ApiError && e.code === 'CART_VENDOR_MISMATCH') setConflict(e.message);
      else act.setError(e instanceof Error ? e.message : 'Could not add');
    }
  };
  const step = (d: number) => setQty((q) => Math.max(Number(offer.moq) || 1, Math.min(offer.max_order_qty ?? 10000, q + d)));
  return (
    <>
      <Row style={{ marginTop: space.sm }}>
        <Pressable accessibilityLabel="Less" onPress={() => step(-1)} style={styles.step}><Text style={styles.stepText}>−</Text></Pressable>
        <Text style={{ minWidth: 34, textAlign: 'center', fontWeight: '800', color: colors.text }}>{qty}</Text>
        <Pressable accessibilityLabel="More" onPress={() => step(1)} style={styles.step}><Text style={styles.stepText}>＋</Text></Pressable>
        <Button title={`Add · ${money((offer.bulk_min_qty && qty >= offer.bulk_min_qty ? offer.bulk_price : offer.price) * qty)}`} small style={{ flex: 1 }}
          disabled={offer.stock_status === 'OUT_OF_STOCK'} onPress={() => add(false)} />
      </Row>
      {act.error ? <Notice tone="danger" message={act.error} /> : null}
      {/* Unmounted when closed, so a closing animation can never stay on screen after navigating away. */}
      {conflict ? (
        <ConfirmDialog visible title="One shop per order" message={conflict} confirmLabel="Empty cart & add"
          onConfirm={() => add(true)} onCancel={() => setConflict(null)} />
      ) : null}
    </>
  );
}

export function ProductScreen({ params }: { params: { id: string } }) {
  const nav = useNav();
  const loc = useShopperLocation();
  const q = useQuery(() => api.get<any>(`/api/v1/marketplace/products/${params.id}${loc.qs ? '?' + loc.qs : ''}`), [params.id, loc.qs]);
  const [variant, setVariant] = useState<string | null>(null);
  const [added, setAdded] = useState(0);
  const p = q.data;
  if (q.loading) return <View style={{ flex: 1 }}><GradientHeader title="Product" onBack={nav.pop} /><Loading /></View>;
  if (q.error || !p) return <View style={{ flex: 1 }}><GradientHeader title="Product" onBack={nav.pop} /><Screen><ErrorState message={q.error ?? 'Not found'} onRetry={q.reload} /></Screen></View>;
  const offers: any[] = (p.offers ?? []).filter((o: any) => !variant || o.variant_id === variant);
  const specs = Object.entries(p.specs ?? {});
  return (
    <View style={{ flex: 1, backgroundColor: colors.paleMint }}>
      <GradientHeader title={p.name} subtitle={[p.brand, p.category_name].filter(Boolean).join(' · ')} onBack={nav.pop}
        right={<CircleButton glyphText="🛒" label="Cart" onPress={() => nav.push('ProductCart')} />} />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        {added ? <Notice tone="success" message="Added to your cart." /> : null}
        {p.advisory_notice ? <Notice tone="warning" message={p.advisory_notice} /> : null}
        <Card>
          {p.description ? <Text style={{ color: colors.text, marginBottom: space.sm }}>{p.description}</Text> : null}
          {specs.map(([k, v]) => <KeyValue key={k} k={k} v={String(v)} />)}
          <KeyValue k="Unit" v={p.unit} />
          <KeyValue k="GST" v={`${Number(p.tax_rate_percent)}% (included in shop prices)`} />
        </Card>
        {(p.variants ?? []).length > 1 ? (
          <Row style={{ flexWrap: 'wrap' }}>
            <Chip label="All variants" selected={!variant} onPress={() => setVariant(null)} />
            {(p.variants ?? []).map((v: any) => <Chip key={v.id} label={v.name} selected={variant === v.id} onPress={() => setVariant(v.id)} />)}
          </Row>
        ) : null}
        <Text style={styles.section}>Compare {offers.length} shop{offers.length === 1 ? '' : 's'}</Text>
        {offers.length === 0 ? (
          <EmptyState title="No shop near you sells this right now" action="🧾 Ask shops for a quote"
            onAction={() => nav.push('RfqCreate', { prefill: p.name, productId: p.id })} />
        ) : offers.map((o) => (
          <Card key={o.listing_id} style={o.cheapest ? { borderColor: colors.secondary, borderWidth: 2 } : undefined}>
            <Row style={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <Pressable style={{ flex: 1 }} accessibilityRole="link" onPress={() => nav.push('ShopProfile', { id: o.partner_id })}>
                <Text style={{ fontWeight: '800', color: colors.text, fontSize: font.md }}>{o.shop_name} ›</Text>
                <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>
                  {(o.roles ?? '').split(',').filter(Boolean).map((r: string) => ROLE[r] ?? r).join(' · ')}
                  {o.location_locality ? ` · ${o.location_locality}` : ''}{o.distance_km != null ? ` · ${km(o.distance_km)}` : ''}
                </Text>
              </Pressable>
              <View style={{ alignItems: 'flex-end' }}>
                <Text style={{ fontWeight: '900', fontSize: font.xl, color: colors.forest }}>{money(o.price)}</Text>
                {o.mrp && Number(o.mrp) > Number(o.price) ? <Text style={{ color: colors.textSecondary, fontSize: font.xs, textDecorationLine: 'line-through' }}>MRP {money(o.mrp)}</Text> : null}
              </View>
            </Row>
            <Row style={{ marginTop: 4, flexWrap: 'wrap' }}>
              {o.cheapest ? <Badge label="Lowest price" tone="success" /> : null}
              {o.nearest ? <Badge label="Nearest" tone="info" /> : null}
              {(p.variants ?? []).length > 1 ? <Badge label={o.variant_name} tone="neutral" /> : null}
              {o.pickup_enabled ? <Badge label="Pickup" tone="neutral" /> : null}
              {o.delivery_enabled ? <Badge label={Number(o.delivery_fee) ? `Delivery ${money(o.delivery_fee)}` : 'Free delivery'} tone="neutral" /> : null}
              {Number(o.rating_count) ? <Badge label={`★ ${o.rating_avg}`} tone="info" /> : null}
            </Row>
            <StockLine status={o.stock_status} updated={o.stock_updated_at} />
            {Number(o.moq) > 1 ? <Text style={styles.small}>Minimum order {o.moq}</Text> : null}
            {o.bulk_price ? <Text style={styles.small}>Bulk: {money(o.bulk_price)} each from {o.bulk_min_qty}</Text> : null}
            {Number(o.lead_time_days) ? <Text style={styles.small}>Ready in {o.lead_time_days} day(s)</Text> : null}
            <AddToCart offer={o} onAdded={() => setAdded((n) => n + 1)} />
          </Card>
        ))}
        <Notice tone="neutral" message={p.stock_note} />
      </Screen>
      <ProductCartBar version={added} />
    </View>
  );
}

// ----- Shop profile ------------------------------------------------------------------------------------------

export function ShopProfileScreen({ params }: { params: { id: string } }) {
  const nav = useNav();
  const loc = useShopperLocation();
  const shop = useQuery(() => api.get<any>(`/api/v1/marketplace/shops/${params.id}${loc.qs ? '?' + loc.qs : ''}`), [params.id, loc.qs]);
  const products = useQuery(() => api.get<any>(`/api/v1/marketplace/products?shopId=${params.id}&size=50${loc.qs ? '&' + loc.qs : ''}`), [params.id, loc.qs]);
  const s = shop.data;
  return (
    <View style={{ flex: 1, backgroundColor: colors.paleMint }}>
      <GradientHeader title={s?.display_name ?? 'Shop'} subtitle={s ? (s.roles ?? '').split(',').filter(Boolean).map((r: string) => ROLE[r] ?? r).join(' · ') : undefined} onBack={nav.pop} />
      <Screen refreshing={shop.refreshing} onRefresh={() => { shop.refresh(); products.refresh(); }}>
        {shop.loading ? <Loading /> : shop.error || !s ? <ErrorState message={shop.error ?? 'Not found'} onRetry={shop.reload} /> : (
          <>
            <Card>
              <Row style={{ flexWrap: 'wrap' }}>
                <Badge label="✓ Verified by BUZZER" tone="success" />
                {Number(s.rating_count) ? <Badge label={`★ ${s.rating_avg} (${s.rating_count})`} tone="info" /> : <Badge label="New on BUZZER" tone="neutral" />}
                {s.distance_km != null ? <Badge label={km(s.distance_km) ?? ''} tone="neutral" /> : null}
              </Row>
              {(s.categories ?? []).length ? <Text style={{ color: colors.textSecondary, marginTop: 4 }}>{s.categories.map((c: any) => c.name).join(' · ')}</Text> : null}
              <Button title="📞 Call the shop" small variant="outline" onPress={() => callPhone(s.contact_phone)} />
            </Card>
            {(s.locations ?? []).map((l: any) => (
              <Card key={l.id}>
                <Text style={{ fontWeight: '800', color: colors.text }}>📍 {l.name}</Text>
                {l.address ? <Text style={{ color: colors.textSecondary }}>{l.address}{l.locality_name ? `, ${l.locality_name}` : ''}</Text> : null}
                {l.opening_hours ? <KeyValue k="Hours" v={l.opening_hours} /> : null}
                <KeyValue k="Pickup" v={l.pickup_enabled ? 'Yes' : 'No'} />
                <KeyValue k="Delivery" v={l.delivery_enabled ? `${Number(l.delivery_fee) ? money(l.delivery_fee) : 'Free'}${l.free_delivery_above ? ` · free above ${money(l.free_delivery_above)}` : ''} · within ${Number(l.service_radius_km)} km` : 'No'} />
                {Number(l.min_order_value) ? <KeyValue k="Minimum order for delivery" v={money(l.min_order_value)} /> : null}
              </Card>
            ))}
            <Text style={styles.section}>Products</Text>
            {products.loading ? <Loading /> : (products.data?.items ?? []).map((p: any) => (
              <Card key={p.id} onPress={() => nav.push('Product', { id: p.id })} accessibilityLabel={p.name}>
                <Row style={{ justifyContent: 'space-between' }}>
                  <View style={{ flex: 1 }}>
                    <Text style={{ fontWeight: '700', color: colors.text }}>{p.name}</Text>
                    <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{p.subcategory_name}</Text>
                  </View>
                  <Text style={{ color: colors.forest, fontWeight: '800' }}>{money(p.min_price)}</Text>
                </Row>
              </Card>
            ))}
            <Button title="🧾 Ask this kind of shop for a quote" variant="secondary" onPress={() => nav.push('RfqCreate', { categoryId: s.categories?.[0]?.id })} />
          </>
        )}
      </Screen>
      <ProductCartBar />
    </View>
  );
}

// ----- Product cart and checkout -----------------------------------------------------------------------------

export function ProductCartScreen() {
  const nav = useNav();
  const loc = useShopperLocation();
  const [fulfilment, setFulfilment] = useState<'PICKUP' | 'DELIVERY'>('PICKUP');
  const [addressId, setAddressId] = useState<string | undefined>();
  const [method, setMethod] = useState<'ONLINE' | 'PAY_ON_DELIVERY'>('ONLINE');
  const [note, setNote] = useState('');
  const key = useRef(newIdempotencyKey());
  const addr = addressId ?? loc.address?.id;
  const q = useQuery(() => api.get<any>(`/api/v1/customer/product-cart${fulfilment === 'DELIVERY' && addr ? `?addressId=${addr}` : ''}`), [fulfilment, addr]);
  const act = useAction();
  const [problem, setProblem] = useState<{ code: string; message: string } | null>(null);
  const c = q.data;
  useEffect(() => {
    if (c && !c.pickup?.available && c.delivery?.available) setFulfilment('DELIVERY');
  }, [c?.pickup?.available, c?.delivery?.available]);

  const setQty = async (listingId: string, quantity: number) => {
    const r = await act.run(() => api.put<any>(`/api/v1/customer/product-cart/items/${listingId}`, { quantity }));
    if (r) q.setData(r);
  };
  const place = async () => {
    setProblem(null);
    try {
      const r = await api.post<any>('/api/v1/customer/orders', { fulfilment, address_id: fulfilment === 'DELIVERY' ? addr : null, payment_method: method,
        note: note.trim() || null }, key.current);
      key.current = newIdempotencyKey();
      nav.replace('OrderDetail', { id: r.order.id, justPlaced: r.order.order_number });
    } catch (e) {
      if (e instanceof ApiError) setProblem({ code: e.code, message: e.message });
      q.refresh();
    }
  };

  if (q.loading) return <View style={{ flex: 1 }}><GradientHeader title="Cart" onBack={nav.pop} /><Loading /></View>;
  const items: any[] = c?.items ?? [];
  const deliveryFee = fulfilment === 'DELIVERY' ? Number(c?.delivery?.fee ?? 0) : 0;
  return (
    <View style={{ flex: 1, backgroundColor: colors.paleMint }}>
      <GradientHeader title="Cart" subtitle={c?.shop ? `From ${c.shop.name}` : 'Shop & Buy'} onBack={nav.pop} />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        {q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : items.length === 0 ? (
          <EmptyState title="Your cart is empty" message="Find products from local shops and compare their prices." action="🛍️ Shop & Buy" onAction={() => nav.replace('Shop', {})} />
        ) : (
          <>
            <Notice tone="info" message="One order is from one shop. Prices include GST." />
            {items.map((i) => (
              <Card key={i.listing_id}>
                <Row style={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
                  <View style={{ flex: 1 }}>
                    <Text style={{ fontWeight: '800', color: colors.text }}>{i.product_name}{i.variant_name && i.variant_name !== 'Standard' ? ` (${i.variant_name})` : ''}</Text>
                    <Text style={styles.small}>{money(i.unit_price)} {i.unit}{i.previous_price ? ` (was ${money(i.previous_price)})` : ''}</Text>
                    <StockLine status={i.stock_status} updated={i.stock_updated_at} />
                  </View>
                  <Text style={{ fontWeight: '900', color: colors.forest }}>{money(i.line_total)}</Text>
                </Row>
                {i.price_changed ? <Notice tone="warning" message={`Price changed from ${money(i.previous_price)} to ${money(i.unit_price)}`} /> : null}
                {(i.issues ?? []).map((t: string) => <Notice key={t} tone="danger" message={t} />)}
                <Row style={{ marginTop: space.sm }}>
                  <Pressable accessibilityLabel="Less" onPress={() => setQty(i.listing_id, Math.max(i.quantity - 1, 0) < i.moq ? 0 : i.quantity - 1)} style={styles.step}>
                    <Text style={styles.stepText}>−</Text>
                  </Pressable>
                  <Text style={{ minWidth: 34, textAlign: 'center', fontWeight: '800', color: colors.text }}>{i.quantity}</Text>
                  <Pressable accessibilityLabel="More" onPress={() => setQty(i.listing_id, i.quantity + 1)} style={styles.step}><Text style={styles.stepText}>＋</Text></Pressable>
                  {Number(i.moq) > 1 ? <Text style={styles.small}>min {i.moq}</Text> : null}
                  <View style={{ flex: 1 }} />
                  <Button title="Remove" variant="ghost" small onPress={() => setQty(i.listing_id, 0)} />
                </Row>
              </Card>
            ))}
            {c.price_changed ? (
              <Button title="Accept the new prices" variant="secondary" onPress={async () => { const r = await act.run(() => api.post<any>('/api/v1/customer/product-cart/accept-prices', {})); if (r) q.setData(r); }} />
            ) : null}

            <Text style={styles.section}>How do you want it?</Text>
            <Row>
              <Chip label="🛍️ Pickup from shop" selected={fulfilment === 'PICKUP'} onPress={() => setFulfilment('PICKUP')} />
              <Chip label="🛵 Shop delivers" selected={fulfilment === 'DELIVERY'} onPress={() => setFulfilment('DELIVERY')} />
            </Row>
            {fulfilment === 'PICKUP' ? (
              c.pickup?.available ? (
                <Card>
                  <Text style={{ fontWeight: '700', color: colors.text }}>📍 {c.location?.name}</Text>
                  {c.location?.address ? <Text style={styles.small}>{c.location.address}</Text> : null}
                  {c.location?.opening_hours ? <Text style={styles.small}>🕘 {c.location.opening_hours}</Text> : null}
                </Card>
              ) : <Notice tone="warning" message="This shop doesn't offer pickup - choose delivery." />
            ) : (
              <>
                {loc.addresses.length ? (
                  <Row style={{ flexWrap: 'wrap' }}>
                    {loc.addresses.map((a: any) => <Chip key={a.id} label={`${a.label}: ${a.locality_name ?? a.line1}`} selected={addr === a.id} onPress={() => setAddressId(a.id)} />)}
                  </Row>
                ) : <Button title="＋ Add a delivery address" variant="outline" onPress={() => nav.push('AddressForm', {})} />}
                {c.delivery?.available ? (
                  <Notice tone="success" message={`Delivery ${Number(c.delivery.fee) ? money(c.delivery.fee) : 'free'}${c.delivery.free_above && Number(c.delivery.fee) ? ` (free above ${money(c.delivery.free_above)})` : ''}`} />
                ) : <Notice tone="warning" message={c.delivery?.reason ?? 'Delivery is not available'} />}
              </>
            )}

            <Text style={styles.section}>Payment</Text>
            <Row style={{ flexWrap: 'wrap' }}>
              <Chip label="💳 Pay online" selected={method === 'ONLINE'} onPress={() => setMethod('ONLINE')} />
              <Chip label={fulfilment === 'PICKUP' ? '💵 Pay at pickup' : '💵 Pay on delivery'} selected={method === 'PAY_ON_DELIVERY'} onPress={() => setMethod('PAY_ON_DELIVERY')} />
            </Row>
            <TextField label="Note for the shop (optional)" value={note} onChangeText={setNote} placeholder="e.g. call before delivery" maxLength={500} />

            <Card>
              <KeyValue k="Items" v={money(c.items_total)} />
              <KeyValue k="of which GST" v={money(c.tax_included)} />
              <KeyValue k="Delivery" v={fulfilment === 'DELIVERY' ? (deliveryFee ? money(deliveryFee) : 'Free') : '—'} />
              <Row style={{ justifyContent: 'space-between', marginTop: 4 }}>
                <Text style={{ fontWeight: '900', color: colors.text, fontSize: font.lg }}>Total</Text>
                <Text style={{ fontWeight: '900', color: colors.forest, fontSize: font.lg }}>{money(Number(c.items_total) + deliveryFee)}</Text>
              </Row>
            </Card>
            {problem ? <Notice tone={problem.code === 'PRICE_CHANGED' ? 'warning' : 'danger'} message={problem.message} /> : null}
            {act.error ? <Notice tone="danger" message={act.error} /> : null}
            <Notice tone="neutral" message={c.stock_note} />
            <Button title={method === 'ONLINE' ? 'Place order & pay' : 'Place order'} onPress={place}
              disabled={c.has_issues || c.price_changed || (fulfilment === 'DELIVERY' && (!c.delivery?.available || !addr)) || (fulfilment === 'PICKUP' && !c.pickup?.available)} />
          </>
        )}
      </Screen>
    </View>
  );
}

// ----- Order tracking ----------------------------------------------------------------------------------------

const STEPS_PICKUP = ['PLACED', 'VENDOR_CONFIRMED', 'READY_FOR_PICKUP', 'COMPLETED'];
const STEPS_DELIVERY = ['PLACED', 'VENDOR_CONFIRMED', 'OUT_FOR_DELIVERY', 'COMPLETED'];

export function OrderDetailScreen({ params }: { params: { id: string; justPlaced?: string } }) {
  const nav = useNav();
  const q = useQuery(() => api.get<any>(`/api/v1/customer/orders/${params.id}`), [params.id]);
  useOnRefocus(q.refresh);
  const act = useAction();
  const payKey = useRef(newIdempotencyKey());
  const [cancelOpen, setCancelOpen] = useState(false);
  const [reason, setReason] = useState('');
  const o = q.data;
  if (q.loading) return <View style={{ flex: 1 }}><GradientHeader title="Order" onBack={nav.pop} /><Loading /></View>;
  if (q.error || !o) return <View style={{ flex: 1 }}><GradientHeader title="Order" onBack={nav.pop} /><Screen><ErrorState message={q.error ?? 'Not found'} onRetry={q.reload} /></Screen></View>;
  const pickup = o.fulfilment === 'PICKUP';
  const steps = pickup ? STEPS_PICKUP : STEPS_DELIVERY;
  const current = o.status === 'PAID' ? 'PLACED' : o.status;
  const reached = steps.indexOf(current);
  const lastPayment = (o.payments ?? [])[0];
  const v = o.vendor_snapshot ?? {};
  const f = o.fulfilment_snapshot ?? {};
  const pay = async () => {
    const r = await act.run(() => api.post(`/api/v1/customer/orders/${o.id}/pay`, {}, payKey.current));
    if (r) {
      payKey.current = newIdempotencyKey();
      q.refresh();
    }
  };
  const simulate = async (paymentId: string, outcome: 'SUCCESS' | 'FAILURE') => {
    if (await act.run(() => api.post(`/api/v1/customer/payments/${paymentId}/simulate`, { outcome }))) q.refresh();
  };
  return (
    <View style={{ flex: 1, backgroundColor: colors.paleMint }}>
      <GradientHeader title={`Order ${o.order_number}`} subtitle={v.name} onBack={nav.pop} />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        {params.justPlaced ? <Notice tone="success" message={`Order ${params.justPlaced} placed.`} /> : null}
        <Card>
          <Row style={{ justifyContent: 'space-between' }}>
            <Text style={{ fontWeight: '800', color: colors.text, fontSize: font.md, flex: 1 }}>{o.status_label}</Text>
            <StatusBadge status={o.status} />
          </Row>
          {!['CANCELLED', 'VENDOR_REJECTED', 'PAYMENT_PENDING'].includes(o.status) ? (
            <Row style={{ marginTop: space.sm, gap: 4 }}>
              {steps.map((s, i) => (
                <View key={s} style={{ flex: 1, alignItems: 'center' }}>
                  <View style={[styles.dot, { backgroundColor: i <= reached ? colors.emerald : colors.border }]} />
                  <Text style={{ fontSize: 10, color: i <= reached ? colors.text : colors.textSecondary, textAlign: 'center' }}>{statusLabel(s)}</Text>
                </View>
              ))}
            </Row>
          ) : null}
          {o.cancel_reason ? <Notice tone="danger" message={o.cancel_reason} /> : null}
          <KeyValue k="Request" v={`#${o.request_number}`} />
          <KeyValue k="Placed" v={dateTime(o.created_at)} />
          {o.quotation_number ? <KeyValue k="From quote" v={o.quotation_number} /> : null}
        </Card>

        {o.completion_code && ['PLACED', 'PAID', 'VENDOR_CONFIRMED', 'READY_FOR_PICKUP', 'OUT_FOR_DELIVERY'].includes(o.status) ? (
          <Card style={{ borderColor: colors.secondary, borderWidth: 2, alignItems: 'center' }}>
            <Text style={{ color: colors.textSecondary }}>{pickup ? 'Pickup code' : 'Delivery code'} — share it only when you receive your order</Text>
            <Text style={{ fontSize: 36, fontWeight: '900', letterSpacing: 8, color: colors.forest }} accessibilityLabel={`Code ${o.completion_code.split('').join(' ')}`}>{o.completion_code}</Text>
          </Card>
        ) : null}

        {o.status === 'PAYMENT_PENDING' ? (
          <Card>
            <Text style={{ fontWeight: '800', color: colors.text }}>Payment — {money(o.total_amount)}</Text>
            {lastPayment?.failure_reason ? <Notice tone="danger" message={`${lastPayment.failure_reason}. Your order is kept — try again.`} /> : null}
            {lastPayment?.test_mode && lastPayment.status === 'PENDING' ? (
              <>
                <Notice tone="warning" message="TEST MODE: no real payment gateway yet. Simulate the gateway's answer." />
                <Row>
                  <Button title="Simulate success" small style={{ flex: 1 }} onPress={() => simulate(lastPayment.id, 'SUCCESS')} loading={act.busy} />
                  <Button title="Simulate failure" small variant="outline" style={{ flex: 1 }} onPress={() => simulate(lastPayment.id, 'FAILURE')} />
                </Row>
              </>
            ) : <Button title="Pay now" onPress={pay} loading={act.busy} />}
          </Card>
        ) : null}

        <Card>
          <Text style={{ fontWeight: '800', color: colors.text, marginBottom: 4 }}>Items</Text>
          {(o.items ?? []).map((i: any) => (
            <KeyValue key={i.line_no} k={`${i.quantity} × ${i.name}${i.variant_name && i.variant_name !== 'Standard' ? ` (${i.variant_name})` : ''}${i.brand ? ` · ${i.brand}` : ''}`} v={money(i.line_total)} />
          ))}
          {Number(o.discount_total) ? <KeyValue k="Discount" v={`- ${money(o.discount_total)}`} /> : null}
          <KeyValue k={o.prices_include_tax ? 'of which GST' : 'GST'} v={money(o.tax_total)} />
          <KeyValue k="Delivery" v={pickup ? 'Pickup' : Number(o.delivery_fee) ? money(o.delivery_fee) : 'Free'} />
          <Row style={{ justifyContent: 'space-between', marginTop: 4 }}>
            <Text style={{ fontWeight: '900', color: colors.text }}>Total</Text>
            <Text style={{ fontWeight: '900', color: colors.forest }}>{money(o.total_amount)}</Text>
          </Row>
          <Text style={styles.small}>{o.payment_method === 'ONLINE' ? 'Paid online' : pickup ? 'Pay at pickup' : 'Pay on delivery'}</Text>
        </Card>

        <Card>
          <Text style={{ fontWeight: '800', color: colors.text }}>{pickup ? '📍 Pick up from' : '🛵 Delivering to'}</Text>
          {pickup ? (
            <>
              <Text style={{ color: colors.text }}>{f.pickup_location?.name ?? v.name}</Text>
              {f.pickup_location?.address ? <Text style={styles.small}>{f.pickup_location.address}</Text> : null}
              {f.pickup_location?.opening_hours ? <Text style={styles.small}>🕘 {f.pickup_location.opening_hours}</Text> : null}
            </>
          ) : (
            <Text style={{ color: colors.text }}>{f.delivery_address ? `${f.delivery_address.line1}${f.delivery_address.locality_name ? ', ' + f.delivery_address.locality_name : ''}` : '—'}</Text>
          )}
          {f.quote_terms ? <KeyValue k="Shop's terms" v={f.quote_terms} /> : null}
          {v.phone ? <Button title={`📞 Call ${v.name}`} small variant="outline" onPress={() => callPhone(v.phone)} /> : null}
        </Card>

        {act.error ? <Notice tone="danger" message={act.error} /> : null}
        {o.can_cancel ? <Button title="Cancel order" variant="ghost" onPress={() => setCancelOpen(true)} /> : null}

        <Card>
          <Text style={{ fontWeight: '800', color: colors.text, marginBottom: space.sm }}>Timeline</Text>
          <Timeline history={o.timeline} />
        </Card>
      </Screen>
      <ConfirmDialog visible={cancelOpen} title="Cancel this order?" danger confirmLabel="Cancel order" loading={act.busy}
        message={o.payment_method === 'ONLINE' && o.status === 'PAID' ? 'Your payment will be refunded.' : undefined}
        onCancel={() => setCancelOpen(false)}
        onConfirm={async () => {
          const r = await act.run(() => api.post(`/api/v1/customer/orders/${o.id}/cancel`, { reason: reason.trim() || null }));
          setCancelOpen(false);
          if (r) q.refresh();
        }}>
        <TextField placeholder="Reason (optional)" value={reason} onChangeText={setReason} />
      </ConfirmDialog>
    </View>
  );
}

// ----- Quotation request (RFQ) -------------------------------------------------------------------------------

type Item = { name: string; brand: string; specs: string; quantity: string; unit: string; notes: string; product_id?: string };
const blankItem = (name = ''): Item => ({ name, brand: '', specs: '', quantity: '1', unit: '', notes: '' });

export function RfqCreateScreen({ params }: { params: { categoryId?: string; prefill?: string; productId?: string } }) {
  const nav = useNav();
  const loc = useShopperLocation();
  const cats = useQuery(() => api.get<any[]>('/api/v1/catalogue/categories?vertical=MARKETPLACE'));
  const [categoryId, setCategoryId] = useState<string | undefined>(params.categoryId);
  const [items, setItems] = useState<Item[]>([{ ...blankItem(params.prefill ?? ''), product_id: params.productId }]);
  const [fulfilment, setFulfilment] = useState<'DELIVERY' | 'PICKUP'>('DELIVERY');
  const [bulk, setBulk] = useState(false);
  const [description, setDescription] = useState('');
  const [neededIn, setNeededIn] = useState<number | null>(null);
  const [addressId, setAddressId] = useState<string | undefined>();
  const key = useRef(newIdempotencyKey());
  const act = useAction();
  const addr = addressId ?? loc.address?.id;
  const set = (i: number, patch: Partial<Item>) => setItems((list) => list.map((x, j) => (j === i ? { ...x, ...patch } : x)));
  const submit = async () => {
    const neededBy = neededIn == null ? null : new Date(Date.now() + neededIn * 86400000).toISOString();
    const r = await act.run(() => api.post<any>('/api/v1/customer/rfqs', {
      category_id: categoryId, address_id: addr ?? null, fulfilment, bulk, description: description.trim() || null, needed_by: neededBy,
      items: items.filter((x) => x.name.trim()).map((x) => ({
        name: x.name.trim(), brand: x.brand.trim() || null, specs: x.specs.trim() || null, quantity: Number(x.quantity) || 1,
        unit: x.unit.trim() || null, notes: x.notes.trim() || null, product_id: x.product_id ?? null,
      })),
    }, key.current));
    if (r) {
      key.current = newIdempotencyKey();
      nav.replace('RequestDetail', { id: r.id, justCreated: r.request_number });
    }
  };
  return (
    <View style={{ flex: 1, backgroundColor: colors.paleMint }}>
      <GradientHeader title="Request quotations" subtitle="Shops reply with item-wise prices" onBack={nav.pop} />
      <Screen>
        <Text style={styles.section}>What kind of products?</Text>
        {cats.loading ? <Loading /> : (
          <Row style={{ flexWrap: 'wrap' }}>
            {(cats.data ?? []).map((c) => <Chip key={c.id} label={c.name} selected={categoryId === c.id} onPress={() => setCategoryId(c.id)} />)}
          </Row>
        )}
        <Text style={styles.section}>Items</Text>
        {items.map((it, i) => (
          <Card key={i}>
            <Row style={{ justifyContent: 'space-between' }}>
              <Text style={{ fontWeight: '800', color: colors.text }}>Item {i + 1}</Text>
              {items.length > 1 ? <Button title="Remove" variant="ghost" small onPress={() => setItems((l) => l.filter((_, j) => j !== i))} /> : null}
            </Row>
            <TextField label="Product" value={it.name} onChangeText={(t) => set(i, { name: t })} placeholder="e.g. uPVC pipe 1/2 inch" maxLength={200} />
            <Row>
              <View style={{ flex: 1 }}><TextField label="Quantity" value={it.quantity} keyboardType="number-pad" onChangeText={(t) => set(i, { quantity: t.replace(/[^0-9]/g, '') })} /></View>
              <View style={{ flex: 1 }}><TextField label="Unit" value={it.unit} onChangeText={(t) => set(i, { unit: t })} placeholder="bags, pcs, kg" maxLength={40} /></View>
            </Row>
            <TextField label="Preferred brand (optional)" value={it.brand} onChangeText={(t) => set(i, { brand: t })} maxLength={120} />
            <TextField label="Specifications (optional)" value={it.specs} onChangeText={(t) => set(i, { specs: t })} placeholder="size, grade, colour…" maxLength={500} />
          </Card>
        ))}
        {items.length < 50 ? <Button title="＋ Add another item" variant="outline" onPress={() => setItems((l) => [...l, blankItem()])} /> : null}

        <Text style={styles.section}>Delivery</Text>
        <Row>
          <Chip label="🛵 Deliver to me" selected={fulfilment === 'DELIVERY'} onPress={() => setFulfilment('DELIVERY')} />
          <Chip label="🛍️ I'll pick up" selected={fulfilment === 'PICKUP'} onPress={() => setFulfilment('PICKUP')} />
        </Row>
        {loc.addresses.length ? (
          <Row style={{ flexWrap: 'wrap' }}>
            {loc.addresses.map((a: any) => <Chip key={a.id} label={`${a.label}: ${a.locality_name ?? a.line1}`} selected={addr === a.id} onPress={() => setAddressId(a.id)} />)}
          </Row>
        ) : <Button title="＋ Add an address" variant="outline" onPress={() => nav.push('AddressForm', {})} />}
        <Text style={styles.small}>Shops near this address are asked to quote.</Text>
        <Text style={styles.section}>When do you need it?</Text>
        <Row style={{ flexWrap: 'wrap' }}>
          {[[null, 'No rush'], [1, 'Tomorrow'], [3, 'In 3 days'], [7, 'This week']].map(([d, l]) => (
            <Chip key={String(l)} label={String(l)} selected={neededIn === d} onPress={() => setNeededIn(d as number | null)} />
          ))}
        </Row>
        <Toggle label="Bulk / wholesale quantity (ask wholesalers first)" value={bulk} onChange={setBulk} />
        <TextField label="Anything else? (optional)" value={description} onChangeText={setDescription} multiline maxLength={2000} />
        <Notice tone="info" message="You can add photos or a BOQ / PDF after sending. Shops quote each item; you compare and accept one — nothing is ordered until you accept." />
        {act.error ? <Notice tone="danger" message={act.error} /> : null}
        <Button title="Send to shops" onPress={submit} loading={act.busy} disabled={!categoryId || !items.some((x) => x.name.trim().length >= 2)} />
      </Screen>
    </View>
  );
}

// ----- Side-by-side comparison and acceptance ----------------------------------------------------------------

export function CompareQuotesScreen({ params }: { params: { id: string } }) {
  const nav = useNav();
  const q = useQuery(() => api.get<any>(`/api/v1/customer/requests/${params.id}/compare`), [params.id]);
  const req = useQuery(() => api.get<any>(`/api/v1/customer/requests/${params.id}`), [params.id]);
  const act = useAction();
  const key = useRef(newIdempotencyKey());
  const [choice, setChoice] = useState<any>(null);
  const [method, setMethod] = useState<'ONLINE' | 'PAY_ON_DELIVERY'>('ONLINE');
  const [fulfilment, setFulfilment] = useState<'DELIVERY' | 'PICKUP'>('DELIVERY');
  const c = q.data;
  useEffect(() => {
    if (req.data && !req.data.address_snapshot) setFulfilment('PICKUP');
  }, [req.data?.address_snapshot]);
  const accept = async () => {
    if (!choice) return;
    const r = await act.run(() => api.post<any>(`/api/v1/customer/requests/${params.id}/quotations/${choice.quotation_id}/accept`,
      { payment_method: method, fulfilment }, key.current));
    setChoice(null);
    if (r?.order) nav.replace('OrderDetail', { id: r.order.id, justPlaced: r.order.order_number });
  };
  const quotes: any[] = c?.quotes ?? [];
  const open = quotes.filter((x) => x.status === 'SHARED' && !x.expired);
  const badge = (id: string) => [
    c?.best?.lowest_complete === id ? 'Lowest (all items)' : null,
    c?.best?.lowest_total === id && c?.best?.lowest_complete !== id ? 'Lowest total' : null,
    c?.best?.fastest === id ? 'Fastest' : null,
  ].filter(Boolean) as string[];
  return (
    <View style={{ flex: 1, backgroundColor: colors.paleMint }}>
      <GradientHeader title="Compare quotes" subtitle={req.data ? `#${req.data.request_number}` : undefined} onBack={nav.pop} />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        {q.loading ? <Loading /> : q.error || !c ? <ErrorState message={q.error ?? 'Not found'} onRetry={q.reload} /> : quotes.length === 0 ? (
          <EmptyState title="No quotes yet" message="Shops are preparing their quotes. You'll be notified as each one arrives." />
        ) : (
          <>
            <Notice tone="info" message={c.rule} />
            <ScrollView horizontal showsHorizontalScrollIndicator>
              <View>
                <View style={styles.tr}>
                  <View style={[styles.th, styles.firstCol]}><Text style={styles.thText}>Item</Text></View>
                  {quotes.map((x) => (
                    <View key={x.quotation_id} style={styles.th}>
                      <Text style={styles.thText} numberOfLines={2}>{x.shop_name}</Text>
                      {badge(x.quotation_id).map((b) => <Text key={b} style={{ color: colors.emerald, fontSize: 10, fontWeight: '800' }}>★ {b}</Text>)}
                    </View>
                  ))}
                </View>
                {(c.items ?? []).map((it: any) => (
                  <View key={it.key} style={styles.tr}>
                    <View style={[styles.td, styles.firstCol]}>
                      <Text style={{ fontWeight: '700', color: colors.text, fontSize: font.xs }}>{it.quantity}{it.unit ? ` ${it.unit}` : ''} × {it.name}</Text>
                      {it.brand_preference ? <Text style={styles.tiny}>Brand: {it.brand_preference}</Text> : null}
                    </View>
                    {quotes.map((x) => {
                      const l = x.lines?.[it.key];
                      return (
                        <View key={x.quotation_id} style={styles.td}>
                          {!l ? <Text style={styles.tiny}>—</Text> : !l.available ? <Text style={{ color: colors.danger, fontSize: font.xs }}>Not available</Text> : (
                            <>
                              <Text style={{ fontWeight: '800', color: colors.text, fontSize: font.xs }}>{money(l.line_total)}</Text>
                              <Text style={styles.tiny}>{money(l.unit_price)} each{Number(l.discount_percent) ? ` −${Number(l.discount_percent)}%` : ''}{Number(l.tax_rate) ? ` +${Number(l.tax_rate)}% GST` : ''}</Text>
                              {l.brand ? <Text style={styles.tiny}>{l.brand}</Text> : null}
                              {l.moq ? <Text style={styles.tiny}>min {l.moq}</Text> : null}
                            </>
                          )}
                        </View>
                      );
                    })}
                  </View>
                ))}
                {[
                  ['Items available', (x: any) => `${x.available_count} of ${x.item_count}`],
                  ['Delivery', (x: any) => (Number(x.delivery_fee) ? money(x.delivery_fee) : 'Free')],
                  ['GST', (x: any) => money(x.tax_amount)],
                  ['Lead time', (x: any) => (x.lead_time_days == null ? '—' : x.lead_time_days === 0 ? 'Same day' : `${x.lead_time_days} day(s)`)],
                  ['Valid until', (x: any) => (x.expired ? 'Expired' : dateTime(x.valid_until))],
                  ['Total', (x: any) => money(x.total_amount)],
                ].map(([label, fmt]: any) => (
                  <View key={label} style={styles.tr}>
                    <View style={[styles.td, styles.firstCol]}><Text style={{ fontWeight: '800', color: colors.text, fontSize: font.xs }}>{label}</Text></View>
                    {quotes.map((x) => <View key={x.quotation_id} style={styles.td}><Text style={{ fontSize: font.xs, color: colors.text, fontWeight: label === 'Total' ? '900' : '400' }}>{fmt(x)}</Text></View>)}
                  </View>
                ))}
              </View>
            </ScrollView>
            {quotes.map((x) => (
              <Card key={x.quotation_id}>
                <Row style={{ justifyContent: 'space-between' }}>
                  <Text style={{ fontWeight: '800', color: colors.text, flex: 1 }}>{x.shop_name}</Text>
                  <Text style={{ fontWeight: '900', color: colors.forest, fontSize: font.lg }}>{money(x.total_amount)}</Text>
                </Row>
                <Row style={{ flexWrap: 'wrap' }}>
                  <Badge label={statusLabel(x.status === 'SHARED' ? 'QUOTE_READY' : x.status)} tone={statusTone(x.status)} />
                  {badge(x.quotation_id).map((b) => <Badge key={b} label={b} tone="success" />)}
                  {x.pay_on_delivery_allowed ? <Badge label="Pay later allowed" tone="neutral" /> : null}
                </Row>
                {x.terms ? <KeyValue k="Terms" v={x.terms} /> : null}
                {x.exclusions ? <KeyValue k="Exclusions" v={x.exclusions} /> : null}
                {x.partner_note ? <Text style={{ color: colors.text }}>💬 {x.partner_note}</Text> : null}
                {open.includes(x) ? <Button title="Accept this quote" onPress={() => { setMethod('ONLINE'); setChoice(x); }} /> : null}
              </Card>
            ))}
            {act.error ? <Notice tone="danger" message={act.error} /> : null}
          </>
        )}
      </Screen>
      <ConfirmDialog visible={!!choice} title={`Accept ${choice?.shop_name ?? ''}?`} confirmLabel="Accept & place order" loading={act.busy}
        message={choice ? `${money(choice.total_amount)} · ${choice.available_count} of ${choice.item_count} items. The other shops will be told you chose someone else.` : ''}
        onCancel={() => setChoice(null)} onConfirm={accept}>
        <Row style={{ flexWrap: 'wrap' }}>
          <Chip label="🛵 Delivery" selected={fulfilment === 'DELIVERY'} onPress={() => setFulfilment('DELIVERY')} />
          <Chip label="🛍️ Pickup" selected={fulfilment === 'PICKUP'} onPress={() => setFulfilment('PICKUP')} />
        </Row>
        <Row style={{ flexWrap: 'wrap' }}>
          <Chip label="💳 Pay online" selected={method === 'ONLINE'} onPress={() => setMethod('ONLINE')} />
          {choice?.pay_on_delivery_allowed ? <Chip label="💵 Pay later" selected={method === 'PAY_ON_DELIVERY'} onPress={() => setMethod('PAY_ON_DELIVERY')} /> : null}
        </Row>
      </ConfirmDialog>
    </View>
  );
}

const styles = StyleSheet.create({
  section: { fontSize: font.lg, fontWeight: '800', color: colors.text, marginTop: space.sm },
  small: { color: colors.textSecondary, fontSize: font.xs },
  tiny: { color: colors.textSecondary, fontSize: 10 },
  searchBox: { flexDirection: 'row', alignItems: 'center', gap: space.sm, backgroundColor: colors.white, borderRadius: radius.pill, paddingHorizontal: space.md, borderWidth: 1, borderColor: colors.border },
  searchInput: { flex: 1, fontSize: font.md, color: colors.text, paddingVertical: 12 },
  shopTile: { width: 150, backgroundColor: colors.white, borderRadius: radius.lg, padding: space.md, gap: 2, borderWidth: 1, borderColor: colors.border },
  cartBar: { position: 'absolute', left: space.lg, right: space.lg, bottom: space.lg, backgroundColor: colors.forest, borderRadius: radius.pill, paddingVertical: 14, paddingHorizontal: space.lg, flexDirection: 'row', alignItems: 'center' },
  step: { width: 34, height: 34, borderRadius: 17, borderWidth: 1, borderColor: colors.emerald, alignItems: 'center', justifyContent: 'center' },
  stepText: { color: colors.emerald, fontWeight: '900', fontSize: 18 },
  dot: { width: 14, height: 14, borderRadius: 7, marginBottom: 2 },
  tr: { flexDirection: 'row' },
  th: { width: 130, padding: 6, backgroundColor: colors.lightGreen, borderWidth: 0.5, borderColor: colors.border },
  thText: { fontWeight: '800', color: colors.text, fontSize: font.xs },
  td: { width: 130, padding: 6, backgroundColor: colors.white, borderWidth: 0.5, borderColor: colors.border },
  firstCol: { width: 150 },
});
