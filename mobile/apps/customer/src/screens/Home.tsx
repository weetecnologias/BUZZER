import React, { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { SafeAreaView } from 'react-native-safe-area-context';
import {
  api, Avatar, callPhone, Card, colors, EmptyState, ErrorState, font, gradient, Loading, radius, Row, Screen, SectionTitle, shadow, space,
  StatusBadge, openWhatsApp, useAuth, useNav, useOnRefocus, useQuery,
} from '@buzzer/core';
import { ApprovalsBanner } from './Approvals';
import { CartBar, CategoryChip, CircleButton, DealCard, PopularServiceRow } from '../components/Catalog';
import { useCart } from '../cart';
import { useVerticals } from './Unified';

type AgentLink = { agent_id: string; display_name: string; photo_url: string | null; availability: string; phone: string; online: boolean; languages: string };
type AgentCardData = { primary: AgentLink | null; backup: AgentLink | null; support_phone: string; plan: 'DEDICATED' | 'EXPLORE' | null };
type Category = { id: string; name: string; icon: string; code: string; image_url: string | null; workflow_template: string };
type HomeData = { active_requests: any[]; popular_services: any[]; recent_requests: any[]; active_orders: any[] };

const CARDS = [
  { key: 'SHOP', vertical: 'MARKETPLACE', glyph: '🛍️', title: 'Shop & Buy', text: 'Products from local shops' },
  { key: 'QUOTES', vertical: 'MARKETPLACE', glyph: '🧾', title: 'Request Quotations', text: 'Shops send item-wise prices' },
  { key: 'SERVICES', vertical: 'SERVICES', glyph: '🛠️', title: 'Book Services', text: 'Verified professionals' },
  { key: 'ASSIST', vertical: 'ASSISTANCE', glyph: '🧑‍💼', title: 'Personal Assistance', text: 'A person who gets it done' },
] as const;

const dial = (phone: string) => callPhone(phone);
const chat = (phone: string) => openWhatsApp(phone);

/** Personal agent card: placed directly below the header, above search and categories. */
function AgentCard({ data, onWrite, onSubscribe }: { data: AgentCardData | null; onWrite: () => void; onSubscribe: () => void }) {
  if (!data) return null;
  const a = data.primary;
  if (!a && data.plan !== 'DEDICATED') {
    // Explore customers: invite them to subscribe; meanwhile the on-duty team handles requests.
    return (
      <LinearGradient colors={gradient.primary} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={styles.agentCard}>
        <Text style={{ color: '#CFEBD9', fontSize: font.xs }}>You're exploring BUZZER</Text>
        <Text style={{ color: colors.white, fontWeight: '800', fontSize: font.lg }}>Get your own dedicated agent</Text>
        <Text style={{ color: '#E3F5EA', fontSize: font.sm, marginTop: 2 }}>One person who speaks your language and handles everything for you. Free.</Text>
        <Row style={{ marginTop: space.md, gap: space.sm }}>
          <Pressable accessibilityRole="button" onPress={onSubscribe} style={styles.writeBtn}>
            <Text style={{ color: colors.forest, fontSize: font.sm, fontWeight: '800' }}>⭐ Subscribe now</Text>
          </Pressable>
          <Pressable accessibilityRole="button" onPress={onWrite} style={[styles.backupRow, { flex: 1, marginTop: 0 }]}>
            <Text style={{ color: colors.white, fontSize: font.xs, fontWeight: '600' }} numberOfLines={2}>✍️ Write to the BUZZER team</Text>
          </Pressable>
        </Row>
      </LinearGradient>
    );
  }
  if (!a) {
    return (
      <Card style={styles.agentCard}>
        <Text style={{ fontWeight: '700', color: colors.text }}>We're assigning your personal agent</Text>
        <Text style={{ color: colors.textSecondary, marginTop: 4 }}>Meanwhile, our support team is here to help.</Text>
        <Pressable onPress={() => dial(data.support_phone)} style={[styles.pill, { marginTop: space.sm, alignSelf: 'flex-start' }]}>
          <Text style={styles.pillText}>📞 Call support</Text>
        </Pressable>
      </Card>
    );
  }
  const online = a.availability === 'AVAILABLE' && a.online;
  return (
    <LinearGradient colors={gradient.primary} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={styles.agentCard}>
      <Row>
        <View style={styles.avatarRing}><Avatar name={a.display_name} size={52} /></View>
        <View style={{ flex: 1 }}>
          <Text style={{ color: '#CFEBD9', fontSize: font.xs }}>Your Personal Agent</Text>
          <Text style={{ color: colors.white, fontWeight: '800', fontSize: font.lg }}>{a.display_name}</Text>
          <Row style={{ gap: 6 }}>
            <View style={[styles.dot, { backgroundColor: online ? '#B8F07A' : '#F6C453' }]} />
            <Text style={{ color: online ? '#B8F07A' : '#F6C453', fontSize: font.xs, fontWeight: '600' }}>
              {online ? 'Online • Call for assistance' : 'Away • Backup support available'}
            </Text>
          </Row>
        </View>
        <Pressable accessibilityLabel={`Call ${a.display_name}`} onPress={() => dial(a.phone)} style={styles.roundBtn}><Text style={styles.roundGlyph}>📞</Text></Pressable>
        <Pressable accessibilityLabel={`Chat with ${a.display_name}`} onPress={() => chat(a.phone)} style={styles.roundBtn}><Text style={styles.roundGlyph}>💬</Text></Pressable>
      </Row>
      <Row style={{ marginTop: space.md, gap: space.sm }}>
        <Pressable accessibilityRole="button" onPress={onWrite} style={styles.writeBtn}>
          <Text style={{ color: colors.forest, fontSize: font.sm, fontWeight: '800' }}>✍️ Write to {a.display_name.split(' ')[0]}</Text>
        </Pressable>
        <Pressable accessibilityRole="button" onPress={() => dial(data.backup?.phone ?? data.support_phone)} style={[styles.backupRow, { flex: 1, marginTop: 0 }]}>
          <Text style={{ color: colors.white, fontSize: font.xs, fontWeight: '600' }} numberOfLines={2}>
            🛟 Backup: {data.backup ? data.backup.display_name : 'BUZZER Care'}
          </Text>
        </Pressable>
      </Row>
    </LinearGradient>
  );
}

export function HomeScreen({ onOpenTab }: { onOpenTab?: (t: string, opts?: { vertical?: string }) => void }) {
  const verticals = useVerticals();
  const shops = useQuery(() => api.get<any[]>('/api/v1/platform/featured-shops?limit=8'));
  const nav = useNav();
  const { me } = useAuth();
  const [q, setQ] = useState('');
  const [selected, setSelected] = useState<Category | null>(null);
  const profile = useQuery(() => api.get<{ city_name: string }>('/api/v1/customer/profile'));
  const agent = useQuery(() => api.get<AgentCardData>('/api/v1/customer/agent'));
  const cats = useQuery(() => api.get<Category[]>('/api/v1/catalogue/categories'));
  const home = useQuery(() => api.get<HomeData>('/api/v1/customer/home'));
  const deals = useQuery(() => api.get<any[]>('/api/v1/catalogue/deals?limit=10'));
  const { cart } = useCart();

  useOnRefocus(() => {
    home.refresh();
    agent.refresh();
  });

  const refresh = () => {
    agent.refresh();
    cats.refresh();
    home.refresh();
    shops.refresh();
    verticals.reload();
  };
  const openCard = (key: string) => {
    if (key === 'SHOP') nav.push('Shop', {});
    else if (key === 'QUOTES') nav.push('RfqCreate', {});
    else if (key === 'SERVICES') onOpenTab?.('explore', { vertical: 'SERVICES' });
    else nav.push('Assistance');
  };
  const book = (s: any) => nav.push('NewRequest', { serviceItemId: s.id, title: s.name });
  const openItem = (s: any) => nav.push('Category', { categoryId: s.category_id, title: s.category_name, subcategoryId: s.subcategory_id });
  const openCategory = (c: { id: string; name: string }, subcategoryId?: string) => nav.push('Category', { categoryId: c.id, title: c.name, subcategoryId });
  const firstName = me?.full_name?.split(' ')[0] || 'there';
  const services: any[] = home.data?.popular_services ?? [];

  return (
    <View style={{ flex: 1, backgroundColor: colors.paleMint }}>
      <SafeAreaView edges={['top']} style={{ backgroundColor: colors.paleMint }}>
        <View style={styles.header}>
          <View style={styles.logo}><Text style={styles.logoB}>B</Text></View>
          <View style={{ flex: 1 }}>
            <Text style={{ color: colors.forest, fontWeight: '900', letterSpacing: 1, fontSize: font.md }}>BUZZER</Text>
            <Text style={{ color: colors.textSecondary, fontSize: font.xs }} numberOfLines={1}>📍 {profile.data?.city_name ?? '…'}</Text>
          </View>
          <CircleButton glyphText="🛒" label="Cart" badge={cart?.item_count ?? 0} onPress={() => nav.push('Cart')} />
          <CircleButton glyphText="🔔" label="Notifications" onPress={() => nav.push('Notifications')} />
          <Pressable accessibilityRole="button" accessibilityLabel="Account" onPress={() => onOpenTab?.('account')}>
            <Avatar name={me?.full_name ?? me?.phone} size={40} />
          </Pressable>
        </View>
      </SafeAreaView>
      <Screen refreshing={agent.refreshing || home.refreshing} onRefresh={refresh}>
        <Text style={styles.hello}>Hi {firstName} 👋</Text>
        <Text style={styles.headline}>What do you need today?</Text>
        <Pressable accessibilityRole="search" accessibilityLabel="Search products, shops, services" onPress={() => nav.push('Search', {})} style={[styles.search, { flex: 0, minHeight: 50, marginBottom: space.sm }]}>
          <Text>🔍</Text>
          <Text style={{ flex: 1, color: colors.textSecondary, paddingVertical: 14, fontSize: font.sm }} numberOfLines={1}>
            Search products, shops, services or tell us what you need...
          </Text>
        </Pressable>

        <View style={styles.cards}>
          {CARDS.filter((cd) => verticals.has(cd.vertical)).map((cd) => (
            <Pressable key={cd.key} accessibilityRole="button" accessibilityLabel={cd.title} onPress={() => openCard(cd.key)}
              style={({ pressed }) => [styles.card4, pressed && { opacity: 0.85 }]}>
              <Text style={{ fontSize: 30 }}>{cd.glyph}</Text>
              <Text style={{ fontWeight: '900', color: colors.forest, fontSize: font.md }}>{cd.title}</Text>
              <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{cd.text}</Text>
            </Pressable>
          ))}
        </View>
        <Pressable accessibilityRole="button" onPress={() => nav.push('CreateRequest', {})}>
          <LinearGradient colors={gradient.primary} start={{ x: 0, y: 0 }} end={{ x: 1, y: 0 }} style={styles.createBtn}>
            <Text style={{ color: colors.white, fontWeight: '900', fontSize: font.lg }}>＋ Create a Request</Text>
            <Text style={{ color: '#CFEBD9', fontSize: font.xs }}>Quotes · bookings · errands · anything else</Text>
          </LinearGradient>
        </Pressable>
        <ApprovalsBanner />

        {(home.data?.active_orders ?? []).length ? (
          <>
            <SectionTitle title="📦 Active orders" action="See all" onAction={() => onOpenTab?.('orders')} />
            {(home.data?.active_orders ?? []).map((r) => (
              <Card key={r.id} onPress={() => (r.order_id ? nav.push('OrderDetail', { id: r.order_id }) : nav.push('RequestDetail', { id: r.id }))} accessibilityLabel={`Order ${r.request_number}`}>
                <Row style={{ justifyContent: 'space-between' }}>
                  <Text style={{ fontWeight: '700', color: colors.text, flex: 1 }}>{r.order_shop_name ? `🛍️ ${r.order_shop_name}` : r.service_name || r.subcategory_name || r.category_name}</Text>
                  <StatusBadge status={r.status} />
                </Row>
              </Card>
            ))}
          </>
        ) : null}

        {verticals.has('ASSISTANCE') ? (
          <>
            <SectionTitle title="My assistant" />
            {agent.loading ? <Loading label="Finding your agent…" /> : agent.error ? <ErrorState message={agent.error} onRetry={agent.reload} /> : <AgentCard data={agent.data} onWrite={() => nav.push('WriteToAgent', {})} onSubscribe={() => nav.push('SubscribeAgent')} />}
          </>
        ) : null}

        <SectionTitle title="Popular categories" action="Explore" onAction={() => onOpenTab?.('explore')} />
        {cats.loading ? <Loading /> : cats.error ? <ErrorState message={cats.error} onRetry={cats.reload} /> : (
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: space.sm, paddingVertical: 4, paddingRight: space.lg }}
            style={{ marginHorizontal: -space.lg, paddingLeft: space.lg }}>
            {(cats.data ?? []).map((c) => (
              <CategoryChip key={c.id} label={c.name.split(' & ')[0]} icon={c.icon} imageUrl={c.image_url} selected={selected?.id === c.id}
                onPress={() => {
                  setSelected(c); // keeps the last opened category highlighted when returning home
                  openCategory(c);
                }} />
            ))}
          </ScrollView>
        )}

        {(shops.data ?? []).length ? (
          <>
            <SectionTitle title="🏪 Shops on BUZZER" action="Shop" onAction={() => nav.push('Shop', {})} />
            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: space.sm, paddingVertical: 4, paddingRight: space.lg }}
              style={{ marginHorizontal: -space.lg, paddingLeft: space.lg }}>
              {(shops.data ?? []).map((sh) => (
                <Pressable key={sh.id} accessibilityRole="button" accessibilityLabel={sh.display_name} style={styles.shop}
                  onPress={() => nav.push('ShopProfile', { id: sh.id })}>
                  <Text style={{ fontSize: 28 }}>🏪</Text>
                  <Text style={{ fontWeight: '800', color: colors.text }} numberOfLines={1}>{sh.display_name}</Text>
                  <Text style={{ color: colors.textSecondary, fontSize: font.xs }} numberOfLines={2}>{sh.categories}</Text>
                  <Text style={{ color: colors.emerald, fontSize: font.xs, fontWeight: '700' }}>{Number(sh.rating_count) ? `★ ${sh.rating_avg}` : '✓ Verified'}</Text>
                </Pressable>
              ))}
            </ScrollView>
          </>
        ) : null}

        <LinearGradient colors={gradient.header} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={styles.promo}>
          <View style={{ flex: 1, gap: space.sm }}>
            <View style={styles.promoTag}><Text style={{ color: colors.white, fontWeight: '700', fontSize: font.xs }}>BUZZER Assist</Text></View>
            <Text style={{ color: colors.white, fontSize: font.xl, fontWeight: '800' }}>You Buzz.{'\n'}We Do.</Text>
            <Pressable accessibilityRole="button" onPress={() => {
              nav.push('WriteToAgent', {});
            }} style={styles.promoBtn}>
              <Text style={{ color: colors.forest, fontWeight: '800' }}>✍️ Ask my agent</Text>
            </Pressable>
          </View>
          <View style={styles.promoArt}>
            {['🛠️', '🎉', '🚚', '💚'].map((g) => <View key={g} style={styles.promoBubble}><Text style={{ fontSize: 26 }}>{g}</Text></View>)}
          </View>
        </LinearGradient>

        {(deals.data ?? []).length ? (
          <>
            <SectionTitle title="🏷️ Offers & featured" />
            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: space.sm, paddingVertical: 4, paddingRight: space.lg }}
              style={{ marginHorizontal: -space.lg, paddingLeft: space.lg }}>
              {(deals.data ?? []).map((d) => <DealCard key={d.id} s={d} onPress={() => openItem(d)} />)}
            </ScrollView>
          </>
        ) : null}

        {(home.data?.recent_requests ?? []).length ? (
          <>
            <SectionTitle title="Recent requests" action="See all" onAction={() => onOpenTab?.('requests')} />
            {(home.data?.recent_requests ?? []).map((r) => (
              <Card key={r.id} onPress={() => nav.push('RequestDetail', { id: r.id })} accessibilityLabel={`Request ${r.request_number}`}>
                <Row style={{ justifyContent: 'space-between' }}>
                  <Text style={{ fontWeight: '700', color: colors.text, flex: 1 }}>{r.service_name || r.subcategory_name || r.category_name}</Text>
                  <StatusBadge status={r.status} />
                </Row>
                <Text style={{ color: colors.textSecondary, fontSize: font.xs, marginTop: 4 }}>#{r.request_number}</Text>
              </Card>
            ))}
          </>
        ) : null}

        <SectionTitle title="Popular services" action="View All" onAction={() => nav.push('Categories')} />
        {home.loading ? <Loading /> : home.error ? <ErrorState message={home.error} onRetry={home.reload} /> : services.length === 0 ? (
          <Card><EmptyState title="Coming soon" message="Services here are being set up." /></Card>
        ) : services.map((s) => (
          <PopularServiceRow key={s.id} s={s} onRequest={() => book(s)}
            onPress={() => openCategory({ id: s.category_id, name: s.category_name }, s.subcategory_id)} />
        ))}
        {(cart?.item_count ?? 0) > 0 ? <View style={{ height: 72 }} /> : null}
      </Screen>
      <CartBar onPress={() => nav.push('Cart')} />
    </View>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', gap: space.sm, paddingHorizontal: space.lg, paddingTop: space.md, paddingBottom: space.xs,
    width: '100%', maxWidth: 760, alignSelf: 'center' },
  agentCard: { borderRadius: 24, padding: space.lg, ...shadow.raised },
  avatarRing: { borderWidth: 2, borderColor: 'rgba(255,255,255,0.5)', borderRadius: 30, padding: 2 },
  dot: { width: 8, height: 8, borderRadius: 4 },
  roundBtn: { width: 42, height: 42, borderRadius: 21, backgroundColor: colors.white, alignItems: 'center', justifyContent: 'center' },
  roundGlyph: { fontSize: 18 },
  writeBtn: { backgroundColor: colors.white, borderRadius: radius.pill, paddingHorizontal: 14, paddingVertical: 10 },
  backupRow: { marginTop: space.md, backgroundColor: 'rgba(255,255,255,0.15)', borderRadius: radius.md, padding: space.sm },
  pill: { backgroundColor: colors.lightGreen, borderRadius: radius.pill, paddingHorizontal: 14, paddingVertical: 8 },
  pillText: { color: colors.forest, fontWeight: '700' },
  logo: { width: 40, height: 40, borderRadius: 12, backgroundColor: '#B8F07A', alignItems: 'center', justifyContent: 'center' },
  logoB: { fontSize: 22, fontWeight: '900', color: colors.forest },
  hello: { color: colors.textSecondary, fontSize: font.sm, marginTop: space.xs },
  headline: { color: colors.text, fontSize: 26, fontWeight: '900', marginBottom: space.sm },
  cards: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, justifyContent: 'space-between' },
  card4: { width: '48.5%', backgroundColor: colors.white, borderRadius: radius.xl, padding: space.md, gap: 4, borderWidth: 1, borderColor: colors.border, ...shadow.card },
  createBtn: { borderRadius: radius.xl, paddingVertical: space.md, paddingHorizontal: space.lg, alignItems: 'center', marginTop: space.sm },
  shop: { width: 150, backgroundColor: colors.white, borderRadius: radius.lg, padding: space.md, gap: 2, borderWidth: 1, borderColor: colors.border },
  search: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: space.sm, backgroundColor: colors.white, borderRadius: radius.pill,
    paddingHorizontal: space.lg, ...shadow.card },
  result: { paddingVertical: space.sm, borderBottomWidth: 1, borderBottomColor: colors.border },
  promo: { borderRadius: 24, padding: space.lg, flexDirection: 'row', gap: space.md, overflow: 'hidden' },
  promoTag: { alignSelf: 'flex-start', backgroundColor: 'rgba(255,255,255,0.18)', borderRadius: radius.pill, paddingHorizontal: 12, paddingVertical: 5 },
  promoBtn: { alignSelf: 'flex-start', backgroundColor: colors.white, borderRadius: radius.pill, paddingHorizontal: 18, paddingVertical: 10 },
  promoArt: { width: 120, flexDirection: 'row', flexWrap: 'wrap', gap: 8, alignContent: 'center', justifyContent: 'center' },
  promoBubble: { width: 52, height: 52, borderRadius: 26, backgroundColor: 'rgba(255,255,255,0.16)', alignItems: 'center', justifyContent: 'center' },
});
