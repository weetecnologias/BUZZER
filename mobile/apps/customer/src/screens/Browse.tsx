import React, { useMemo, useState } from 'react';
import { Modal, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import {
  api, Button, colors, EmptyState, ErrorState, font, Loading, Notice, radius, Screen, space, useNav, useQuery,
} from '@buzzer/core';
import { AddControl, CartBar, CircleButton, FilterPill, HeartButton, PriceTag, ServiceCard, ServiceImage } from '../components/Catalog';
import { isCartable, useCart } from '../cart';

type Category = { id: string; name: string; icon: string; image_url: string | null; description: string; advisory_notice: string | null; workflow_template: string };

export function TopBar({ title, onBack, right }: { title: string; onBack: () => void; right?: React.ReactNode }) {
  return (
    <SafeAreaView edges={['top']} style={{ backgroundColor: colors.paleMint }}>
      <View style={styles.topBar}>
        <CircleButton glyphText="←" label="Go back" onPress={onBack} />
        <Text style={styles.topTitle} numberOfLines={1}>{title}</Text>
        {right ?? <View style={{ width: 46 }} />}
      </View>
    </SafeAreaView>
  );
}

function CartButton() {
  const nav = useNav();
  const { cart } = useCart();
  return <CircleButton glyphText="🛒" label="Cart" badge={cart?.item_count ?? 0} onPress={() => nav.push('Cart')} />;
}

/** All categories as a grid of rounded cards with round images. */
export function CategoriesScreen() {
  const nav = useNav();
  const q = useQuery(() => api.get<Category[]>('/api/v1/catalogue/categories'));
  return (
    <View style={{ flex: 1, backgroundColor: colors.paleMint }}>
      <TopBar title="All Categories" onBack={nav.pop} right={<CartButton />} />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : (
          <View style={styles.grid}>
            {(q.data ?? []).map((c) => (
              <Pressable key={c.id} accessibilityRole="button" accessibilityLabel={c.name} style={({ pressed }) => [styles.gridCard, pressed && { opacity: 0.85 }]}
                onPress={() => nav.push('Category', { categoryId: c.id, title: c.name })}>
                <ServiceImage uri={c.image_url} icon={c.icon} size={58} round />
                <Text style={styles.gridLabel} numberOfLines={2}>{c.name}</Text>
              </Pressable>
            ))}
          </View>
        )}
      </Screen>
      <CartBar onPress={() => nav.push('Cart')} />
    </View>
  );
}

/**
 * Category screen: "All" + subcategory filter pills (only this category's subcategories), then service cards.
 * Menu services are added to the cart; requirement-based services (and "Other ..." items) open the request form.
 */
export function CategoryScreen({ params }: { params: { categoryId: string; title: string; subcategoryId?: string } }) {
  const nav = useNav();
  const { cart } = useCart();
  const cat = useQuery(() => api.get<Category>(`/api/v1/catalogue/categories/${params.categoryId}`), [params.categoryId]);
  const subs = useQuery(() => api.get<any[]>(`/api/v1/catalogue/categories/${params.categoryId}/subcategories`), [params.categoryId]);
  const services = useQuery(() => api.get<any[]>(`/api/v1/catalogue/categories/${params.categoryId}/services`), [params.categoryId]);
  const [filter, setFilter] = useState<string>(params.subcategoryId ?? 'ALL');
  const [details, setDetails] = useState<any>(null);
  const workflow = cat.data?.workflow_template;

  const visible = useMemo(() => {
    const all = services.data ?? [];
    return filter === 'ALL' ? all.filter((s) => !s.is_other) : all.filter((s) => s.subcategory_id === filter);
  }, [services.data, filter]);
  const partners = useMemo(() => {
    const seen = new Map<string, number>();
    (services.data ?? []).filter((s) => filter === 'ALL' || s.subcategory_id === filter).forEach((s) => seen.set(s.subcategory_id, Number(s.verified_partners ?? 0)));
    return [...seen.values()].reduce((a, b) => a + b, 0);
  }, [services.data, filter]);
  const request = (s: any) => nav.push('NewRequest', { serviceItemId: s.id, title: s.name });
  const isSpecial = workflow === 'GLOBAL_SPECIAL_REQUEST';
  const selectedSub = (subs.data ?? []).find((s) => s.id === filter);

  return (
    <View style={{ flex: 1, backgroundColor: colors.paleMint }}>
      <TopBar title={params.title} onBack={nav.pop} right={<CartButton />} />
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ flexGrow: 0 }}
        contentContainerStyle={{ gap: space.sm, paddingHorizontal: space.lg, paddingVertical: space.sm }}>
        <FilterPill label="All" selected={filter === 'ALL'} onPress={() => setFilter('ALL')} />
        {(subs.data ?? []).map((s) => <FilterPill key={s.id} label={s.name} selected={filter === s.id} onPress={() => setFilter(s.id)} />)}
      </ScrollView>
      <Screen refreshing={services.refreshing} onRefresh={services.refresh}>
        {cat.data?.advisory_notice ? <Notice tone="warning" message={cat.data.advisory_notice} /> : null}
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-end' }}>
          <View style={{ flex: 1 }}>
            <Text style={{ fontSize: font.xl, fontWeight: '800', color: colors.text }}>{selectedSub ? selectedSub.name : 'Top Services'}</Text>
            <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>
              {partners > 0 ? `${partners} verified partner${partners === 1 ? '' : 's'} available` : 'Your agent will source verified partners'}
            </Text>
          </View>
          <Text style={{ color: colors.emerald, fontWeight: '700', fontSize: font.sm }}>{visible.length} options</Text>
        </View>

        {isSpecial ? (
          <ServiceCard s={{ name: 'Tell us anything', description: 'For needs that don’t fit any category. Your agent takes it from here.', icon: 'star', is_other: true, special: true }}
            workflow={workflow} onDetails={() => nav.push('WriteToAgent', {})} onRequest={() => nav.push('WriteToAgent', {})} />
        ) : null}

        {services.loading ? <Loading /> : services.error ? <ErrorState message={services.error} onRetry={services.reload} /> : visible.length === 0 && !isSpecial ? (
          <EmptyState title="Coming soon" message="Services in this category are being set up." />
        ) : visible.map((s, i) => (
          <React.Fragment key={s.id}>
            {filter !== 'ALL' && s.menu_group && s.menu_group !== visible[i - 1]?.menu_group ? (
              <Text style={{ marginTop: space.md, marginBottom: 2, fontSize: font.md, fontWeight: '800', color: colors.forest }}>{s.menu_group}</Text>
            ) : null}
            <ServiceCard s={s} workflow={workflow} onDetails={() => setDetails(s)} onRequest={() => request(s)} />
          </React.Fragment>
        ))}
        {!isSpecial ? (
          <Pressable accessibilityRole="button" onPress={() => nav.push('WriteToAgent', { categoryId: params.categoryId, categoryName: params.title })} style={styles.writeCard}>
            <Text style={{ fontSize: 22 }}>✍️</Text>
            <View style={{ flex: 1 }}>
              <Text style={{ fontWeight: '800', color: colors.forest }}>Can't find what you need?</Text>
              <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>Write to your agent — they will arrange it for you.</Text>
            </View>
            <Text style={{ color: colors.emerald, fontWeight: '800' }}>›</Text>
          </Pressable>
        ) : null}
        {(cart?.item_count ?? 0) > 0 ? <View style={{ height: 72 }} /> : null}
      </Screen>
      <CartBar onPress={() => nav.push('Cart')} />

      <Modal visible={!!details} transparent animationType="slide" onRequestClose={() => setDetails(null)}>
        <Pressable style={styles.backdrop} onPress={() => setDetails(null)}>
          <Pressable style={styles.sheet} onPress={(e) => e.stopPropagation()}>
            <View style={styles.handle} />
            {details ? (
              <>
                <View style={{ flexDirection: 'row', gap: space.md, alignItems: 'center' }}>
                  <ServiceImage uri={details.image_url} icon={details.icon ?? details.subcategory_icon} size={72} />
                  <View style={{ flex: 1 }}>
                    <Text style={{ fontSize: font.xl, fontWeight: '800', color: colors.text }}>{details.name}</Text>
                    {details.subcategory_name ? <Text style={{ color: colors.textSecondary }}>{params.title} › {details.subcategory_name}</Text> : null}
                  </View>
                  <HeartButton id={details.id} />
                </View>
                {details.description ? <Text style={{ color: colors.text, lineHeight: 21 }}>{details.description}</Text> : null}
                <View style={styles.priceBox}>
                  <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{details.deal_label ? `🏷️ ${details.deal_label}` : 'Price'}</Text>
                  <PriceTag s={details} size={font.xl} />
                  {details.duration_label ? <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>⏱ Takes about {details.duration_label}</Text> : null}
                  <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>
                    {['STARTING_FROM', 'VISIT_PLUS_LABOUR', 'RANGE'].includes(details.pricing_type) ? 'Starting price — final amount confirmed before work starts.'
                      : details.pricing_type === 'QUOTE_REQUIRED' ? 'Your agent collects quotes from verified partners for you.' : 'Listed price by verified BUZZER partners.'}
                  </Text>
                </View>
                <View style={{ gap: 6 }}>
                  {['Add to cart and check out — book yourself or send it to your agent', 'A verified partner is assigned and confirmed',
                    'Partner starts & completes the job with your OTP', 'Pay securely online or on delivery where allowed'].map((t, i) => (
                    <Text key={t} style={{ color: colors.text }}><Text style={{ color: colors.emerald, fontWeight: '800' }}>{i + 1}. </Text>{t}</Text>
                  ))}
                </View>
                {!details.is_other && !['QUOTE_REQUIRED', 'API_PRICED'].includes(details.pricing_type) && workflow === 'MENU_DRIVEN' ? (
                  <Button title="⚡ Book a time & professional" onPress={() => { const d = details; setDetails(null); nav.push('BookService', { serviceItemId: d.id, title: d.name }); }} />
                ) : null}
                {isCartable(details, workflow) ? (
                  <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                    <AddControl id={details.id} />
                    <Button title="Go to cart" variant="outline" small onPress={() => { setDetails(null); nav.push('Cart'); }} />
                  </View>
                ) : (
                  <Button title={details.is_other ? 'Describe your requirement' : 'Request this service'} onPress={() => { const d = details; setDetails(null); request(d); }} />
                )}
              </>
            ) : null}
          </Pressable>
        </Pressable>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  topBar: { flexDirection: 'row', alignItems: 'center', gap: space.md, paddingHorizontal: space.lg, paddingVertical: space.md, width: '100%', maxWidth: 760, alignSelf: 'center' },
  topTitle: { flex: 1, textAlign: 'center', fontSize: font.xl, fontWeight: '800', color: colors.text },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, justifyContent: 'space-between' },
  gridCard: { width: '31%', backgroundColor: colors.white, borderRadius: 20, paddingVertical: space.md, paddingHorizontal: 6, alignItems: 'center', gap: 8,
    borderWidth: 1, borderColor: colors.border },
  gridLabel: { fontSize: 12, fontWeight: '600', color: colors.text, textAlign: 'center' },
  writeCard: { flexDirection: 'row', alignItems: 'center', gap: space.md, backgroundColor: colors.lightGreen, borderRadius: 18, padding: space.md,
    borderWidth: 1, borderColor: colors.secondary, borderStyle: 'dashed' },
  backdrop: { flex: 1, backgroundColor: colors.overlay, justifyContent: 'flex-end' },
  sheet: { backgroundColor: colors.white, borderTopLeftRadius: 28, borderTopRightRadius: 28, padding: space.xl, gap: space.md, width: '100%', maxWidth: 760, alignSelf: 'center' },
  handle: { alignSelf: 'center', width: 44, height: 5, borderRadius: 3, backgroundColor: colors.border },
  priceBox: { backgroundColor: colors.paleMint, borderRadius: radius.lg, padding: space.md, gap: 4 },
});
