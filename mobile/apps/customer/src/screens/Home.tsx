import React, { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { SafeAreaView } from 'react-native-safe-area-context';
import {
  api, Avatar, callPhone, Card, colors, EmptyState, ErrorState, font, gradient, Loading, radius, Row, Screen, SectionTitle, shadow, space,
  StatusBadge, openWhatsApp, useAuth, useNav, useOnRefocus, useQuery,
} from '@buzzer/core';
import { CartBar, CategoryChip, CircleButton, DealCard, PopularServiceRow } from '../components/Catalog';
import { useCart } from '../cart';

type AgentLink = { agent_id: string; display_name: string; photo_url: string | null; availability: string; phone: string; online: boolean; languages: string };
type AgentCardData = { primary: AgentLink | null; backup: AgentLink | null; support_phone: string };
type Category = { id: string; name: string; icon: string; code: string; image_url: string | null; workflow_template: string };
type HomeData = { active_requests: any[]; popular_services: any[] };

const dial = (phone: string) => callPhone(phone);
const chat = (phone: string) => openWhatsApp(phone);

/** Personal agent card: placed directly below the header, above search and categories. */
function AgentCard({ data, onWrite }: { data: AgentCardData | null; onWrite: () => void }) {
  if (!data) return null;
  const a = data.primary;
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

export function HomeScreen({ onOpenTab }: { onOpenTab?: (t: string) => void }) {
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
  const search = useQuery(() => (q.trim().length >= 2 ? api.get<any[]>(`/api/v1/catalogue/search?q=${encodeURIComponent(q.trim())}`) : Promise.resolve([])), [q]);
  useOnRefocus(home.refresh);

  const refresh = () => {
    agent.refresh();
    cats.refresh();
    home.refresh();
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
          <View style={{ flex: 1 }}>
            <Text style={{ color: colors.textSecondary, fontSize: font.sm }}>Welcome back • 📍 {profile.data?.city_name ?? '…'}</Text>
            <Text style={{ color: colors.text, fontSize: font.xxl, fontWeight: '800' }}>Hello, {firstName}</Text>
          </View>
          <CircleButton glyphText="♡" label="Wishlist" onPress={() => nav.push('Wishlist')} />
          <CircleButton glyphText="🛒" label="Cart" badge={cart?.item_count ?? 0} onPress={() => nav.push('Cart')} />
          <CircleButton glyphText="🔔" label="Notifications" onPress={() => onOpenTab?.('alerts')} />
        </View>
      </SafeAreaView>
      <Screen refreshing={agent.refreshing || home.refreshing} onRefresh={refresh}>
        {agent.loading ? <Loading label="Finding your agent…" /> : agent.error ? <ErrorState message={agent.error} onRetry={agent.reload} /> : <AgentCard data={agent.data} onWrite={() => nav.push('WriteToAgent', {})} />}

        <Row style={{ gap: space.sm }}>
          <View style={styles.search}>
            <Text>🔍</Text>
            <TextInput value={q} onChangeText={setQ} placeholder="Search for service…" placeholderTextColor={colors.textSecondary}
              style={{ flex: 1, fontSize: font.md, color: colors.text, paddingVertical: 12 }} accessibilityLabel="Search services" />
          </View>
          <CircleButton glyphText="⚙️" label="Browse all categories" onPress={() => nav.push('Categories')} />
        </Row>
        {q.trim().length >= 2 ? (
          <Card>
            {search.loading ? <Loading /> : (search.data ?? []).length === 0 ? (
              <EmptyState title="No matches" message="Try another word, or raise a Special Request and your agent will arrange it." />
            ) : (search.data ?? []).map((s: any) => (
              <Pressable key={s.id} onPress={() => (s.workflow_template === 'MENU_DRIVEN' || s.workflow_template === 'EVENT_BRIEF') && !s.is_other ? openItem(s) : book(s)} style={styles.result}>
                <Text style={{ fontWeight: '600', color: colors.text }}>{s.name}</Text>
                <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{s.category_name} › {s.subcategory_name}</Text>
              </Pressable>
            ))}
          </Card>
        ) : null}

        <SectionTitle title="Categories" action="View All" onAction={() => nav.push('Categories')} />
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
            <SectionTitle title="🏷️ Exclusive deals" />
            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: space.sm, paddingVertical: 4, paddingRight: space.lg }}
              style={{ marginHorizontal: -space.lg, paddingLeft: space.lg }}>
              {(deals.data ?? []).map((d) => <DealCard key={d.id} s={d} onPress={() => openItem(d)} />)}
            </ScrollView>
          </>
        ) : null}

        {(home.data?.active_requests ?? []).length ? (
          <>
            <SectionTitle title="Active requests" action="See all" onAction={() => onOpenTab?.('requests')} />
            {(home.data?.active_requests ?? []).map((r) => (
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
  search: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: space.sm, backgroundColor: colors.white, borderRadius: radius.pill,
    paddingHorizontal: space.lg, ...shadow.card },
  result: { paddingVertical: space.sm, borderBottomWidth: 1, borderBottomColor: colors.border },
  promo: { borderRadius: 24, padding: space.lg, flexDirection: 'row', gap: space.md, overflow: 'hidden' },
  promoTag: { alignSelf: 'flex-start', backgroundColor: 'rgba(255,255,255,0.18)', borderRadius: radius.pill, paddingHorizontal: 12, paddingVertical: 5 },
  promoBtn: { alignSelf: 'flex-start', backgroundColor: colors.white, borderRadius: radius.pill, paddingHorizontal: 18, paddingVertical: 10 },
  promoArt: { width: 120, flexDirection: 'row', flexWrap: 'wrap', gap: 8, alignContent: 'center', justifyContent: 'center' },
  promoBubble: { width: 52, height: 52, borderRadius: 26, backgroundColor: 'rgba(255,255,255,0.16)', alignItems: 'center', justifyContent: 'center' },
});
