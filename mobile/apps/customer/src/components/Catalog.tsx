import React from 'react';
import { Image, Pressable, StyleSheet, Text, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { API_URL, colors, font, glyph, gradient, money, radius, shadow, space } from '@buzzer/core';
import { isCartable, useCart } from '../cart';

/** Photo when the admin has configured one, otherwise a soft green tile with the item's icon. */
export function ServiceImage({ uri, icon, size = 92, round = false }: { uri?: string | null; icon?: string | null; size?: number; round?: boolean }) {
  const r = round ? size / 2 : radius.lg;
  if (uri) return <Image source={{ uri: uri.startsWith('/') ? API_URL + uri : uri }} style={{ width: size, height: size, borderRadius: r, backgroundColor: colors.lightGreen }} accessibilityIgnoresInvertColors />;
  return (
    <LinearGradient colors={['#E8F6EC', '#CDEBD7']} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }}
      style={{ width: size, height: size, borderRadius: r, alignItems: 'center', justifyContent: 'center' }}>
      <Text style={{ fontSize: size * 0.42 }}>{glyph(icon)}</Text>
    </LinearGradient>
  );
}

/** Category card used in the horizontal row: round image + label; selected state is solid green. */
export function CategoryChip({ label, icon, imageUrl, selected, onPress }: {
  label: string; icon?: string | null; imageUrl?: string | null; selected?: boolean; onPress: () => void;
}) {
  return (
    <Pressable accessibilityRole="button" accessibilityState={{ selected: !!selected }} accessibilityLabel={label} onPress={onPress}
      style={({ pressed }) => [styles.catChip, selected && styles.catChipOn, pressed && { opacity: 0.85 }]}>
      <View style={[styles.catImgRing, selected && { borderColor: 'rgba(255,255,255,0.6)' }]}>
        <ServiceImage uri={imageUrl} icon={icon} size={54} round />
      </View>
      <Text numberOfLines={2} style={[styles.catLabel, selected && { color: colors.white }]}>{label}</Text>
    </Pressable>
  );
}

export function CircleButton({ glyphText, onPress, label, badge }: { glyphText: string; onPress: () => void; label: string; badge?: number | boolean }) {
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={label} onPress={onPress} hitSlop={6} style={({ pressed }) => [styles.circle, pressed && { opacity: 0.8 }]}>
      <Text style={{ fontSize: 20 }}>{glyphText}</Text>
      {typeof badge === 'number' && badge > 0 ? (
        <View style={styles.countBadge}><Text style={styles.countText}>{badge > 99 ? '99+' : badge}</Text></View>
      ) : badge === true ? <View style={styles.dot} /> : null}
    </Pressable>
  );
}

export function FilterPill({ label, selected, onPress }: { label: string; selected?: boolean; onPress: () => void }) {
  return (
    <Pressable accessibilityRole="button" accessibilityState={{ selected: !!selected }} onPress={onPress}
      style={[styles.pill, selected && { backgroundColor: colors.emerald, borderColor: colors.emerald }]}>
      <Text style={[styles.pillText, selected && { color: colors.white }]}>{label}</Text>
    </Pressable>
  );
}

export function priceLine(s: any): string {
  const free = s.base_price != null && Number(s.base_price) === 0;
  switch (s.pricing_type) {
    case 'FIXED': return free ? 'Free' : money(s.base_price) + (s.unit ? ` ${s.unit}` : '');
    case 'STARTING_FROM': return `From ${money(s.base_price)}${s.unit ? ' ' + s.unit : ''}`;
    case 'RANGE': return `${money(s.min_price)} – ${money(s.max_price)}`;
    case 'PER_UNIT': return `${money(s.base_price)} ${s.unit ?? ''}`.trim();
    case 'HOURLY': return `${money(s.base_price)}/hr`;
    case 'VISIT_PLUS_LABOUR': return `Visit ${money(s.base_price)} + labour`;
    case 'PACKAGE': return s.base_price ? `${money(s.base_price)}${s.unit ? ' ' + s.unit : ''}` : 'Package';
    default: return 'Price on quote';
  }
}

/** Price with strike-through original price and discount when the item carries a deal. */
export function PriceTag({ s, size = font.md }: { s: any; size?: number }) {
  const hasDeal = s.original_price != null && s.base_price != null && Number(s.original_price) > Number(s.base_price);
  const pct = hasDeal ? Math.round((1 - Number(s.base_price) / Number(s.original_price)) * 100) : 0;
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: 6 }}>
      <Text style={[styles.price, { fontSize: size }]}>{s.is_other ? 'Price on quote' : priceLine(s)}</Text>
      {hasDeal ? <Text style={styles.strike}>{money(s.original_price)}</Text> : null}
      {hasDeal ? <Text style={styles.off}>{pct}% off</Text> : null}
    </View>
  );
}

export function HeartButton({ id, size = 20 }: { id: string; size?: number }) {
  const { wishlist, toggleWish } = useCart();
  const on = wishlist.has(id);
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={on ? 'Remove from wishlist' : 'Add to wishlist'} accessibilityState={{ selected: on }}
      onPress={() => toggleWish(id)} hitSlop={10} style={styles.heart}>
      <Text style={{ fontSize: size, color: on ? colors.danger : colors.textSecondary }}>{on ? '♥' : '♡'}</Text>
    </Pressable>
  );
}

/** "Add" button that turns into a − qty + stepper once the item is in the cart. */
export function AddControl({ id, compact }: { id: string; compact?: boolean }) {
  const { qtyOf, add, setQty } = useCart();
  const qty = qtyOf(id);
  if (qty === 0) {
    return (
      <Pressable accessibilityRole="button" accessibilityLabel="Add to cart" onPress={() => add(id)}
        style={({ pressed }) => [styles.addBtn, compact && { paddingHorizontal: 14, minHeight: 34 }, pressed && { opacity: 0.85 }]}>
        <Text style={styles.addText}>Add +</Text>
      </Pressable>
    );
  }
  return (
    <View style={[styles.stepper, compact && { minHeight: 34 }]}>
      <Pressable accessibilityRole="button" accessibilityLabel="Decrease quantity" onPress={() => setQty(id, qty - 1)} hitSlop={6} style={styles.stepBtn}>
        <Text style={styles.stepGlyph}>−</Text>
      </Pressable>
      <Text style={styles.stepQty} accessibilityLabel={`Quantity ${qty}`}>{qty}</Text>
      <Pressable accessibilityRole="button" accessibilityLabel="Increase quantity" onPress={() => setQty(id, qty + 1)} hitSlop={6} style={styles.stepBtn}>
        <Text style={styles.stepGlyph}>+</Text>
      </Pressable>
    </View>
  );
}

/**
 * Service card: image, wishlist heart, name, description, price/deal, then Details + Add (cart items)
 * or Details + Request (requirement-form services, "Other ..." items).
 */
export function ServiceCard({ s, workflow, onDetails, onRequest }: { s: any; workflow?: string | null; onDetails: () => void; onRequest: () => void }) {
  const cartable = isCartable(s, workflow);
  return (
    <View style={styles.svcCard}>
      <Pressable accessibilityRole="button" accessibilityLabel={`${s.name} details`} onPress={onDetails} style={{ flexDirection: 'row', gap: space.md }}>
        <ServiceImage uri={s.image_url} icon={s.icon ?? s.subcategory_icon} size={88} />
        <View style={{ flex: 1, gap: 3 }}>
          {s.deal_label ? <Text style={styles.dealTag}>🏷️ {s.deal_label}</Text> : null}
          <Text style={styles.svcName} numberOfLines={2}>{s.name}</Text>
          <Text style={styles.svcDesc} numberOfLines={2}>
            {s.description || (s.is_other ? 'Describe exactly what you need — your agent arranges it.' : `${s.subcategory_name ?? 'Service'} by verified BUZZER partners`)}
          </Text>
          {s.duration_label ? <Text style={styles.svcDesc}>⏱ {s.duration_label}</Text> : null}
          <PriceTag s={s} />
        </View>
      </Pressable>
      {s.id && !s.special ? <View style={{ position: 'absolute', top: 10, right: 10 }}><HeartButton id={s.id} /></View> : null}
      <View style={styles.cardFooter}>
        <Pressable accessibilityRole="button" onPress={onDetails} hitSlop={6}>
          <Text style={styles.detailsLink}>View details</Text>
        </Pressable>
        {cartable && s.id ? <AddControl id={s.id} /> : (
          <Pressable accessibilityRole="button" onPress={onRequest} style={({ pressed }) => [styles.addBtn, pressed && { opacity: 0.85 }]}>
            <Text style={styles.addText}>{s.is_other ? 'Describe →' : 'Request →'}</Text>
          </Pressable>
        )}
      </View>
    </View>
  );
}

/** Compact row for "Popular services" / wishlist: details area + Add control as sibling buttons. */
export function PopularServiceRow({ s, onPress, onRequest }: { s: any; onPress: () => void; onRequest?: () => void }) {
  const cartable = isCartable(s, s.workflow_template);
  return (
    <View style={styles.popRow}>
      <Pressable accessibilityRole="button" accessibilityLabel={s.name} onPress={onPress}
        style={({ pressed }) => [{ flex: 1, flexDirection: 'row', alignItems: 'center', gap: space.md }, pressed && { opacity: 0.85 }]}>
        <ServiceImage uri={s.image_url} icon={s.icon ?? s.subcategory_icon} size={60} />
        <View style={{ flex: 1, gap: 2 }}>
          <Text style={styles.svcName} numberOfLines={2}>{s.name}</Text>
          <Text style={{ color: colors.textSecondary, fontSize: font.xs }} numberOfLines={1}>
            {Number(s.verified_partners) > 0 ? `✓ ${s.verified_partners} verified pros • ` : ''}{s.subcategory_name ?? s.category_name}
          </Text>
          <PriceTag s={s} size={font.sm} />
        </View>
      </Pressable>
      {cartable ? <AddControl id={s.id} compact /> : (
        <Pressable accessibilityRole="button" onPress={onRequest ?? onPress} style={[styles.addBtn, { paddingHorizontal: 14, minHeight: 34 }]}>
          <Text style={styles.addText}>Request</Text>
        </Pressable>
      )}
    </View>
  );
}

/** Deal card for the home screen's horizontal "Exclusive deals" row. */
export function DealCard({ s, onPress }: { s: any; onPress: () => void }) {
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={s.name} onPress={onPress} style={({ pressed }) => [styles.dealCard, pressed && { opacity: 0.9 }]}>
      <LinearGradient colors={gradient.header} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={styles.dealTop}>
        <Text style={{ color: '#B8F07A', fontWeight: '800', fontSize: font.xs }}>{s.deal_label}</Text>
        <Text style={{ fontSize: 30 }}>{glyph(s.icon ?? s.subcategory_icon)}</Text>
      </LinearGradient>
      <View style={{ padding: space.sm, gap: 4 }}>
        <Text style={styles.svcName} numberOfLines={2}>{s.name}</Text>
        <PriceTag s={s} size={font.sm} />
        <View style={{ alignSelf: 'flex-start' }}><AddControl id={s.id} compact /></View>
      </View>
    </Pressable>
  );
}

/** Floating "View cart" bar shown while the cart has items. */
export function CartBar({ onPress }: { onPress: () => void }) {
  const { cart } = useCart();
  if (!cart || cart.item_count === 0) return null;
  return (
    <Pressable accessibilityRole="button" accessibilityLabel="View cart" onPress={onPress} style={styles.cartBarWrap}>
      <LinearGradient colors={gradient.primary} start={{ x: 0, y: 0 }} end={{ x: 1, y: 0 }} style={styles.cartBar}>
        <View>
          <Text style={{ color: colors.white, fontWeight: '800' }}>{cart.item_count} item{cart.item_count === 1 ? '' : 's'} • {money(cart.estimated_total)}{cart.has_quote_items ? ' + quotes' : ''}</Text>
          {Number(cart.savings) > 0 ? <Text style={{ color: '#B8F07A', fontSize: font.xs, fontWeight: '700' }}>You save {money(cart.savings)}</Text> : null}
        </View>
        <Text style={{ color: colors.white, fontWeight: '800' }}>View cart  ›</Text>
      </LinearGradient>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  catChip: { width: 96, paddingVertical: 12, paddingHorizontal: 6, borderRadius: 20, backgroundColor: colors.white, alignItems: 'center', gap: 8,
    borderWidth: 1, borderColor: colors.border, ...shadow.card },
  catChipOn: { backgroundColor: colors.emerald, borderColor: colors.emerald },
  catImgRing: { borderWidth: 2, borderColor: colors.lightGreen, borderRadius: 32, padding: 2 },
  catLabel: { fontSize: 12, fontWeight: '600', color: colors.text, textAlign: 'center', minHeight: 30 },
  circle: { width: 46, height: 46, borderRadius: 23, backgroundColor: colors.white, alignItems: 'center', justifyContent: 'center', ...shadow.card },
  dot: { position: 'absolute', top: 10, right: 11, width: 8, height: 8, borderRadius: 4, backgroundColor: colors.danger, borderWidth: 1.5, borderColor: colors.white },
  countBadge: { position: 'absolute', top: -2, right: -2, minWidth: 18, height: 18, borderRadius: 9, backgroundColor: colors.danger, alignItems: 'center',
    justifyContent: 'center', paddingHorizontal: 4, borderWidth: 1.5, borderColor: colors.white },
  countText: { color: colors.white, fontSize: 10, fontWeight: '800' },
  pill: { paddingHorizontal: 18, paddingVertical: 10, borderRadius: radius.pill, backgroundColor: colors.white, borderWidth: 1, borderColor: colors.border },
  pillText: { color: colors.text, fontWeight: '600', fontSize: font.sm },
  svcCard: { backgroundColor: colors.white, borderRadius: 22, padding: space.md, gap: space.md, ...shadow.card, borderWidth: 1, borderColor: '#EAF2EC' },
  svcName: { fontSize: font.md, fontWeight: '700', color: colors.text, paddingRight: 26 },
  svcDesc: { fontSize: font.xs, color: colors.textSecondary, lineHeight: 17 },
  price: { fontWeight: '800', color: colors.emerald },
  strike: { color: colors.textSecondary, textDecorationLine: 'line-through', fontSize: font.xs },
  off: { color: colors.white, backgroundColor: colors.secondary, fontSize: 10, fontWeight: '800', paddingHorizontal: 6, paddingVertical: 2, borderRadius: 6, overflow: 'hidden' },
  dealTag: { alignSelf: 'flex-start', color: colors.forest, backgroundColor: '#E9F9C8', fontSize: 11, fontWeight: '800', paddingHorizontal: 8, paddingVertical: 2,
    borderRadius: 8, overflow: 'hidden' },
  heart: { width: 34, height: 34, borderRadius: 17, backgroundColor: colors.white, alignItems: 'center', justifyContent: 'center', ...shadow.card },
  cardFooter: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', borderTopWidth: 1, borderTopColor: colors.border, paddingTop: space.md },
  detailsLink: { color: colors.emerald, fontWeight: '700', fontSize: font.sm },
  addBtn: { backgroundColor: colors.emerald, borderRadius: radius.pill, paddingHorizontal: 20, minHeight: 40, alignItems: 'center', justifyContent: 'center' },
  addText: { color: colors.white, fontWeight: '800', fontSize: font.sm },
  stepper: { flexDirection: 'row', alignItems: 'center', backgroundColor: colors.lightGreen, borderRadius: radius.pill, minHeight: 40, borderWidth: 1,
    borderColor: colors.secondary },
  stepBtn: { width: 36, alignItems: 'center', justifyContent: 'center', alignSelf: 'stretch' },
  stepGlyph: { color: colors.forest, fontSize: 20, fontWeight: '800' },
  stepQty: { minWidth: 22, textAlign: 'center', color: colors.forest, fontWeight: '800', fontSize: font.md },
  popRow: { flexDirection: 'row', alignItems: 'center', gap: space.sm, backgroundColor: colors.white, borderRadius: 20, padding: space.sm, paddingRight: space.md,
    ...shadow.card, borderWidth: 1, borderColor: '#EAF2EC' },
  dealCard: { width: 190, backgroundColor: colors.white, borderRadius: 20, overflow: 'hidden', borderWidth: 1, borderColor: colors.border, ...shadow.card },
  dealTop: { height: 74, padding: space.sm, justifyContent: 'space-between', flexDirection: 'row', alignItems: 'flex-start' },
  cartBarWrap: { position: 'absolute', left: space.lg, right: space.lg, bottom: space.md, borderRadius: radius.pill, overflow: 'hidden', ...shadow.raised,
    maxWidth: 728, alignSelf: 'center' },
  cartBar: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingHorizontal: space.lg, paddingVertical: 14 },
});
