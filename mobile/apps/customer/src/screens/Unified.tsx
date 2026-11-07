import React, { useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { SafeAreaView } from 'react-native-safe-area-context';
import {
  api, Avatar, Button, callPhone, Card, Chip, colors, dateTime, EmptyState, ErrorState, font, glyph, gradient, GradientHeader, Loading, money, Notice,
  openWhatsApp, radius, Row, Screen, space, StatusBadge, useNav, useOnRefocus, useQuery,
} from '@buzzer/core';
import { CartBar, CircleButton, ServiceImage } from '../components/Catalog';
import { useCart } from '../cart';

export type Vertical = { code: 'MARKETPLACE' | 'SERVICES' | 'TASKS' | 'ASSISTANCE'; name: string; tagline: string; icon: string; category_count: number };
type Category = { id: string; name: string; icon: string; image_url: string | null; vertical: string; description: string | null };

/** Enabled business verticals (admin can switch them off). Cached for the session. */
let verticalCache: Vertical[] | null = null;
export function useVerticals() {
  const q = useQuery(async () => (verticalCache = await api.get<Vertical[]>('/api/v1/platform/verticals')));
  const list = q.data ?? verticalCache ?? [];
  return { list, loading: q.loading && !verticalCache, has: (code: string) => list.some((v) => v.code === code), reload: q.refresh };
}

function CategoryGrid({ categories }: { categories: Category[] }) {
  const nav = useNav();
  return (
    <View style={styles.grid}>
      {categories.map((c) => (
        <Pressable key={c.id} accessibilityRole="button" accessibilityLabel={c.name} style={({ pressed }) => [styles.gridCard, pressed && { opacity: 0.85 }]}
          onPress={() => nav.push('Category', { categoryId: c.id, title: c.name })}>
          <ServiceImage uri={c.image_url} icon={c.icon} size={58} round />
          <Text style={styles.gridLabel} numberOfLines={2}>{c.name}</Text>
        </Pressable>
      ))}
    </View>
  );
}

const VERTICAL_HINT: Record<string, string> = {
  MARKETPLACE: 'Products from local shops. Add items (and anything not listed) to your cart, then buy or tap “Get quotes” — shops reply with item-wise prices.',
  SERVICES: 'Verified professionals for your home and life — fixed prices or quotes.',
  TASKS: 'Pickups, drops, collections and errands — tell us what to get done.',
  ASSISTANCE: 'Anything else? Your BUZZER agent handles it end to end.',
};

/** Explore tab: one chip per enabled vertical, then that vertical's categories. */
export function ExploreScreen({ vertical, onVertical }: { vertical?: string; onVertical: (v: string) => void }) {
  const nav = useNav();
  const v = useVerticals();
  const current = v.list.find((x) => x.code === vertical) ?? v.list[0];
  const cats = useQuery(() => (current ? api.get<Category[]>(`/api/v1/catalogue/categories?vertical=${current.code}`) : Promise.resolve([])), [current?.code]);
  return (
    <View style={{ flex: 1, backgroundColor: colors.paleMint }}>
      <GradientHeader title="Explore" subtitle="Products · Services · Tasks · Assistance" right={
        <CircleButton glyphText="🔍" label="Search" onPress={() => nav.push('Search', {})} />
      } />
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ flexGrow: 0 }}
        contentContainerStyle={{ gap: space.sm, paddingHorizontal: space.lg, paddingVertical: space.sm }}>
        {v.list.map((x) => <Chip key={x.code} label={`${glyph(x.icon)} ${x.name}`} selected={current?.code === x.code} onPress={() => onVertical(x.code)} />)}
      </ScrollView>
      <Screen refreshing={cats.refreshing} onRefresh={cats.refresh}>
        {current ? <Notice tone="info" message={VERTICAL_HINT[current.code] ?? current.tagline} /> : null}
        {v.loading || cats.loading ? <Loading /> : cats.error ? <ErrorState message={cats.error} onRetry={cats.reload} /> : !(cats.data ?? []).length ? (
          <EmptyState title="Coming soon" message="Categories for this section are being set up." />
        ) : <CategoryGrid categories={cats.data ?? []} />}
        {current?.code === 'ASSISTANCE' || current?.code === 'TASKS' ? (
          <Button title={current.code === 'TASKS' ? '📝 Describe a task' : '✍️ Ask a BUZZER agent'} variant="secondary"
            onPress={() => nav.push('WriteToAgent', {})} />
        ) : null}
      </Screen>
      <CartBar onPress={() => nav.push('Cart')} />
    </View>
  );
}

// ----- Universal search -----------------------------------------------------------------------------------

type SearchResult = {
  query: string;
  suggestion: null | { workflow: string; title: string; description: string; vertical: string; category_id?: string; subcategory_id?: string; category_name?: string };
  categories: Category[];
  items: any[];
  shops: { id: string; display_name: string; business_name: string | null; rating_avg: number; rating_count: number; categories: string; category_id: string }[];
};

const WORKFLOW_GLYPH: Record<string, string> = { SHOP: '🛍️', REQUEST_QUOTES: '🧾', BOOK_SERVICE: '🛠️', TASK: '🛵', ASSISTANCE: '🧑‍💼' };

export function UniversalSearchScreen({ params }: { params: { q?: string } }) {
  const nav = useNav();
  const [q, setQ] = useState(params.q ?? '');
  const [debounced, setDebounced] = useState(q);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(q.trim()), 300);
    return () => clearTimeout(t);
  }, [q]);
  const r = useQuery(() => (debounced.length >= 2 ? api.get<SearchResult>(`/api/v1/search?q=${encodeURIComponent(debounced)}`) : Promise.resolve(null)), [debounced]);
  const s = r.data?.suggestion;

  const follow = () => {
    if (!s) return;
    if (s.workflow === 'ASSISTANCE' || (s.workflow === 'TASK' && !s.category_id)) return nav.push('WriteToAgent', { prefill: debounced });
    if (s.category_id) return nav.push('Category', { categoryId: s.category_id, title: s.category_name ?? s.title, subcategoryId: s.subcategory_id });
    nav.push('Tabs', { tab: 'explore', vertical: s.vertical });
  };

  return (
    <View style={{ flex: 1, backgroundColor: colors.paleMint }}>
      <SafeAreaView edges={['top']} style={{ backgroundColor: colors.paleMint }}>
        <Row style={{ paddingHorizontal: space.lg, paddingVertical: space.sm, gap: space.sm }}>
          <CircleButton glyphText="←" label="Go back" onPress={nav.pop} />
          <View style={styles.searchBox}>
            <Text>🔍</Text>
            <TextInput autoFocus value={q} onChangeText={setQ} placeholder="Search products, shops, services or tell us what you need..."
              placeholderTextColor={colors.textSecondary} style={styles.searchInput} accessibilityLabel="Universal search" returnKeyType="search" />
            {q ? <Pressable accessibilityLabel="Clear search" onPress={() => setQ('')}><Text style={{ color: colors.textSecondary, fontSize: 18 }}>×</Text></Pressable> : null}
          </View>
        </Row>
      </SafeAreaView>
      <Screen>
        {debounced.length < 2 ? (
          <>
            <Text style={styles.section}>Try</Text>
            <Row style={{ flexWrap: 'wrap' }}>
              {['LED bulb', 'leaking tap repair', '50 bags wall putty', 'pickup documents', 'AC service', 'plan a birthday party'].map((x) => (
                <Chip key={x} label={x} onPress={() => setQ(x)} />
              ))}
            </Row>
          </>
        ) : r.loading ? <Loading /> : r.error ? <ErrorState message={r.error} onRetry={r.reload} /> : (
          <>
            {s ? (
              <Pressable accessibilityRole="button" onPress={follow}>
                <LinearGradient colors={gradient.primary} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={styles.suggestion}>
                  <Text style={{ fontSize: 30 }}>{WORKFLOW_GLYPH[s.workflow] ?? '✨'}</Text>
                  <View style={{ flex: 1 }}>
                    <Text style={{ color: '#CFEBD9', fontSize: font.xs }}>Suggested for “{debounced}”</Text>
                    <Text style={{ color: colors.white, fontWeight: '900', fontSize: font.lg }}>{s.title}</Text>
                    <Text style={{ color: '#E3F5EA', fontSize: font.sm }}>{s.description}</Text>
                  </View>
                  <Text style={{ color: colors.white, fontSize: 22, fontWeight: '900' }}>›</Text>
                </LinearGradient>
              </Pressable>
            ) : null}
            {(r.data?.items ?? []).length ? (
              <>
                <Text style={styles.section}>Products & services</Text>
                <Card>
                  {(r.data?.items ?? []).map((i) => (
                    <Pressable key={i.id} accessibilityRole="button" style={styles.row}
                      onPress={() => nav.push('Category', { categoryId: i.category_id, title: i.category_name, subcategoryId: i.subcategory_id })}>
                      <ServiceImage uri={i.image_url} icon={i.icon ?? i.subcategory_icon} size={40} />
                      <View style={{ flex: 1 }}>
                        <Text style={{ fontWeight: '700', color: colors.text }}>{i.name}</Text>
                        <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{i.category_name} › {i.subcategory_name}</Text>
                      </View>
                      {i.base_price != null ? <Text style={{ color: colors.forest, fontWeight: '800' }}>{money(i.base_price)}</Text> : null}
                    </Pressable>
                  ))}
                </Card>
              </>
            ) : null}
            {(r.data?.shops ?? []).length ? (
              <>
                <Text style={styles.section}>Shops</Text>
                {(r.data?.shops ?? []).map((sh) => (
                  <Card key={sh.id} onPress={() => nav.push('Category', { categoryId: sh.category_id, title: sh.categories })}>
                    <Row>
                      <Text style={{ fontSize: 26 }}>🏪</Text>
                      <View style={{ flex: 1 }}>
                        <Text style={{ fontWeight: '800', color: colors.text }}>{sh.display_name}</Text>
                        <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{sh.categories}{Number(sh.rating_count) ? ` • ★ ${sh.rating_avg}` : ''}</Text>
                      </View>
                    </Row>
                  </Card>
                ))}
              </>
            ) : null}
            {(r.data?.categories ?? []).length ? (
              <>
                <Text style={styles.section}>Categories</Text>
                <CategoryGrid categories={r.data?.categories ?? []} />
              </>
            ) : null}
            <Card>
              <Text style={{ fontWeight: '800', color: colors.text }}>Not finding it?</Text>
              <Text style={{ color: colors.textSecondary, marginVertical: 4 }}>Tell us in your own words — a BUZZER agent will take it from there.</Text>
              <Button title="📝 Create a request" variant="secondary" onPress={() => nav.push('CreateRequest', { text: debounced })} />
            </Card>
          </>
        )}
      </Screen>
    </View>
  );
}

// ----- Create a request ---------------------------------------------------------------------------------

/** One entry point for every kind of request; each option opens the matching flow. */
export function CreateRequestScreen({ params }: { params: { text?: string } }) {
  const nav = useNav();
  const v = useVerticals();
  const options = [
    { show: v.has('MARKETPLACE'), glyph: '🧾', title: 'Get quotes from local shops', text: 'Make a list of items — shops reply with availability and price for each.',
      go: () => nav.push('Tabs', { tab: 'explore', vertical: 'MARKETPLACE' }) },
    { show: v.has('SERVICES'), glyph: '🛠️', title: 'Book a service', text: 'Plumber, electrician, AC, cleaning, painting and more.',
      go: () => nav.push('Tabs', { tab: 'explore', vertical: 'SERVICES' }) },
    { show: v.has('TASKS'), glyph: '🛵', title: 'Task or errand', text: 'Pick up documents or a parcel, collect from a shop, deliver to someone.',
      go: () => nav.push('WriteToAgent', { prefill: params.text, kind: 'TASK' }) },
    { show: v.has('ASSISTANCE'), glyph: '🧑‍💼', title: 'Ask a person to handle it', text: 'Describe anything — a BUZZER agent arranges it for you.',
      go: () => nav.push('WriteToAgent', { prefill: params.text }) },
  ].filter((o) => o.show);
  return (
    <View style={{ flex: 1, backgroundColor: colors.paleMint }}>
      <GradientHeader title="Create a request" subtitle="What do you need today?" onBack={nav.pop} />
      <Screen>
        {params.text ? <Notice tone="info" message={`You searched for “${params.text}”. Choose how you'd like to get it done.`} /> : null}
        {v.loading ? <Loading /> : options.map((o) => (
          <Card key={o.title} onPress={o.go} accessibilityLabel={o.title}>
            <Row style={{ alignItems: 'flex-start' }}>
              <View style={styles.optionIcon}><Text style={{ fontSize: 26 }}>{o.glyph}</Text></View>
              <View style={{ flex: 1 }}>
                <Text style={{ fontWeight: '800', color: colors.text, fontSize: font.md }}>{o.title}</Text>
                <Text style={{ color: colors.textSecondary }}>{o.text}</Text>
              </View>
              <Text style={{ color: colors.emerald, fontWeight: '900', fontSize: 20 }}>›</Text>
            </Row>
          </Card>
        ))}
        <Notice tone="neutral" message="Nothing is booked or charged until you review and confirm." />
      </Screen>
    </View>
  );
}

// ----- Personal assistance --------------------------------------------------------------------------------

export function AssistanceScreen() {
  const nav = useNav();
  const st = useQuery(() => api.get<any>('/api/v1/customer/onboarding'));
  useOnRefocus(st.refresh);
  const a = st.data?.agent;
  return (
    <View style={{ flex: 1, backgroundColor: colors.paleMint }}>
      <GradientHeader title="Personal Assistance" subtitle="A real person who gets it done" onBack={nav.pop} />
      <Screen refreshing={st.refreshing} onRefresh={st.refresh}>
        {st.loading ? <Loading /> : st.error ? <ErrorState message={st.error} onRetry={st.reload} /> : a ? (
          <LinearGradient colors={gradient.primary} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={styles.agent}>
            <Row>
              <Avatar name={a.display_name} size={56} />
              <View style={{ flex: 1 }}>
                <Text style={{ color: '#CFEBD9', fontSize: font.xs }}>Your dedicated agent</Text>
                <Text style={{ color: colors.white, fontWeight: '900', fontSize: font.lg }}>{a.display_name}</Text>
                <Text style={{ color: '#E3F5EA', fontSize: font.sm }}>{a.online ? '🟢 Online now' : '🟡 Away — backup agent covers you'} • {a.shift_start}–{a.shift_end}</Text>
              </View>
            </Row>
            <Row style={{ marginTop: space.md, gap: space.sm }}>
              <Button title="📞 Call" small variant="secondary" onPress={() => callPhone(a.phone)} />
              <Button title="💬 WhatsApp" small variant="secondary" onPress={() => openWhatsApp(a.phone)} />
              <Button title="✍️ Write" small variant="secondary" onPress={() => nav.push('WriteToAgent', {})} />
            </Row>
          </LinearGradient>
        ) : (
          <Card>
            <Text style={{ fontWeight: '900', color: colors.text, fontSize: font.lg }}>Get your own dedicated agent</Text>
            <Text style={{ color: colors.textSecondary, marginVertical: space.sm }}>
              One person who speaks your language, knows your home and handles shopping, quotes, bookings and errands for you. Free during launch.
            </Text>
            <Button title="⭐ Subscribe for a dedicated agent" onPress={() => nav.push('SubscribeAgent')} />
          </Card>
        )}
        <Text style={styles.section}>Your agent can</Text>
        {[['🧾', 'Find products and collect quotes from shops'], ['🛠️', 'Book and follow up on services'], ['🛵', 'Arrange pickups, drops and errands'],
          ['📞', 'Follow up with vendors for you'], ['✅', 'You always approve before anything is ordered or paid']].map(([g, t]) => (
          <Row key={t} style={{ paddingVertical: 6 }}><Text style={{ fontSize: 20 }}>{g}</Text><Text style={{ color: colors.text, flex: 1 }}>{t}</Text></Row>
        ))}
        <Button title={a ? 'Ask for help now' : 'Ask the BUZZER team for help now'} variant="outline" onPress={() => nav.push('WriteToAgent', {})} />
      </Screen>
    </View>
  );
}

// ----- Orders tab -------------------------------------------------------------------------------------------

const ORDER_STATES = ['PAYMENT_PENDING', 'PAYMENT_CONFIRMED', 'PARTNER_ASSIGNED', 'PARTNER_CONFIRMED', 'RESCHEDULING_REQUESTED', 'PARTNER_ARRIVED',
  'SERVICE_STARTED', 'IN_PROGRESS', 'COMPLETION_PENDING', 'PARTNER_DECLINED', 'NO_SHOW'];
const DONE_STATES = ['COMPLETED', 'SETTLEMENT_PENDING', 'SETTLED', 'REFUND_PENDING', 'REFUNDED', 'DISPUTED'];

/** Orders & bookings: requests the customer has accepted (paid or pay-on-delivery) and their history. */
export function OrdersScreen() {
  const nav = useNav();
  const [tab, setTab] = useState<'ACTIVE' | 'PAST'>('ACTIVE');
  const q = useQuery(() => api.get<any[]>('/api/v1/customer/requests?openOnly=false'));
  useOnRefocus(q.refresh);
  const rows = (q.data ?? []).filter((r) => (tab === 'ACTIVE' ? ORDER_STATES : DONE_STATES).includes(r.status));
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="Orders" subtitle="Purchases, bookings and deliveries you've confirmed" />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        <Row>
          <Chip label="Active" selected={tab === 'ACTIVE'} onPress={() => setTab('ACTIVE')} />
          <Chip label="Past orders" selected={tab === 'PAST'} onPress={() => setTab('PAST')} />
        </Row>
        {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : rows.length === 0 ? (
          <EmptyState title={tab === 'ACTIVE' ? 'No active orders' : 'No past orders yet'}
            message="When you accept a quote or book a service, it shows up here until it's delivered or done." />
        ) : rows.map((r) => (
          <Card key={r.id} onPress={() => nav.push('RequestDetail', { id: r.id })} accessibilityLabel={`Order ${r.request_number}`}>
            <Row style={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <View style={{ flex: 1 }}>
                <Text style={{ fontWeight: '800', color: colors.text }}>{r.service_name || r.subcategory_name || r.category_name}</Text>
                <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>#{r.request_number} • {dateTime(r.updated_at ?? r.created_at)}</Text>
              </View>
              <StatusBadge status={r.status} />
            </Row>
            {r.estimated_total != null ? <Text style={{ color: colors.forest, fontWeight: '700', marginTop: 4 }}>{money(r.estimated_total)}</Text> : null}
          </Card>
        ))}
      </Screen>
    </View>
  );
}

const styles = StyleSheet.create({
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, justifyContent: 'flex-start' },
  gridCard: { width: '31%', minWidth: 96, backgroundColor: colors.white, borderRadius: radius.lg, padding: space.sm, alignItems: 'center', gap: 6, borderWidth: 1, borderColor: colors.border },
  gridLabel: { textAlign: 'center', color: colors.text, fontWeight: '700', fontSize: font.xs },
  searchBox: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: space.sm, backgroundColor: colors.white, borderRadius: radius.pill, paddingHorizontal: space.md, borderWidth: 1, borderColor: colors.border },
  searchInput: { flex: 1, fontSize: font.md, color: colors.text, paddingVertical: 12 },
  section: { fontSize: font.lg, fontWeight: '800', color: colors.text, marginTop: space.sm },
  suggestion: { borderRadius: radius.xl, padding: space.lg, flexDirection: 'row', alignItems: 'center', gap: space.md },
  row: { flexDirection: 'row', alignItems: 'center', gap: space.sm, paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: colors.border },
  optionIcon: { width: 52, height: 52, borderRadius: 26, backgroundColor: colors.lightGreen, alignItems: 'center', justifyContent: 'center' },
  agent: { borderRadius: radius.xl, padding: space.lg },
});
