import React, { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { SafeAreaView } from 'react-native-safe-area-context';
import {
  api, Avatar, Button, callPhone, Card, Chip, colors, ErrorState, font, gradient, GradientHeader, Loading, Notice, openWhatsApp, radius, Row,
  Screen, space, TextField, useAction, useAuth, useNav, useQuery,
} from '@buzzer/core';
import { getCurrentLocation } from '../location';

export type Onboarding = {
  full_name: string | null;
  plan: 'DEDICATED' | 'EXPLORE' | null;
  onboarded: boolean;
  preferred_language: string | null;
  preferences: { languages?: string[]; contact_method?: string; contact_time?: string; interest_category_ids?: string[]; notes?: string | null } | null;
  agent: MatchedAgent | null;
  language_options: { code: string; label: string }[];
  contact_methods: string[];
  contact_times: string[];
};
type MatchedAgent = {
  agent_id: string; display_name: string; phone: string; languages: string; availability: string; online: boolean; wing: string | null;
  shift_start: string; shift_end: string; shared_languages: string[];
};
type SubscribeResult = { plan: string; agent: MatchedAgent | null; pending: boolean; already_had_agent: boolean };

const METHOD_LABEL: Record<string, string> = { CALL: '📞 Phone call', WHATSAPP: '💬 WhatsApp', IN_APP_CHAT: '✍️ In-app chat' };
const TIME_LABEL: Record<string, string> = { ANYTIME: 'Anytime', MORNING: 'Morning (9–12)', AFTERNOON: 'Afternoon (12–5)', EVENING: 'Evening (5–9)' };
const BENEFITS = [
  ['🧑‍💼', 'One person who knows you', 'Your own agent handles every request — repairs, errands, tickets, events.'],
  ['🗣️', 'Speaks your language', 'Matched on the languages you are comfortable with, in your area.'],
  ['⚡', 'Faster, better quotes', 'Your agent compares verified partners and shares the best options.'],
  ['🛟', 'Always a backup', 'A backup agent steps in when yours is away. No request is left waiting.'],
];

/** First screen after sign up: subscribe for a dedicated agent, or explore the app first. */
export function WelcomeScreen({ name, onSubscribe, onExplore, exploring }: { name?: string | null; onSubscribe: () => void; onExplore: () => void; exploring?: boolean }) {
  const first = name?.split(' ')[0];
  return (
    <LinearGradient colors={gradient.primary} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={{ flex: 1 }}>
      <SafeAreaView style={{ flex: 1 }}>
        <ScrollView contentContainerStyle={styles.welcomeWrap}>
          <View style={styles.logo}><Text style={styles.logoB}>B</Text></View>
          <Text style={styles.hello}>{first ? `Welcome, ${first}!` : 'Welcome to BUZZER'}</Text>
          <Text style={styles.sub}>Your Personal Assistant for Everyday Life</Text>
          <View style={styles.benefits}>
            {BENEFITS.map(([g, t, d]) => (
              <Row key={t} style={{ alignItems: 'flex-start', gap: space.md, marginBottom: space.md }}>
                <Text style={{ fontSize: 26 }}>{g}</Text>
                <View style={{ flex: 1 }}>
                  <Text style={{ color: colors.text, fontWeight: '800' }}>{t}</Text>
                  <Text style={{ color: colors.textSecondary, fontSize: font.sm }}>{d}</Text>
                </View>
              </Row>
            ))}
          </View>
        </ScrollView>
        <View style={styles.welcomeFooter}>
          <Pressable accessibilityRole="button" onPress={onSubscribe} style={styles.subscribeBtn}>
            <Text style={styles.subscribeText}>Subscribe for a dedicated agent</Text>
            <Text style={styles.subscribeHint}>Free • matched to your language in seconds</Text>
          </Pressable>
          <Pressable accessibilityRole="button" onPress={onExplore} disabled={exploring} style={styles.exploreBtn}>
            <Text style={styles.exploreText}>{exploring ? 'Opening…' : 'Explore the app first  →'}</Text>
          </Pressable>
          <Text style={styles.footNote}>You can subscribe any time from Home or Profile.</Text>
        </View>
      </SafeAreaView>
    </LinearGradient>
  );
}

/** Preferences form + automatic match. Used in the welcome flow and later from Home / Profile. */
export function SubscribeForm({ onBack, onDone }: { onBack?: () => void; onDone: (r: SubscribeResult) => void }) {
  const { me } = useAuth();
  const status = useQuery(() => api.get<Onboarding>('/api/v1/customer/onboarding'));
  const cats = useQuery(() => api.get<{ id: string; name: string }[]>('/api/v1/catalogue/categories'));
  const act = useAction();
  const [name, setName] = useState<string | null>(null);
  const [langs, setLangs] = useState<string[] | null>(null);
  const [method, setMethod] = useState<string | null>(null);
  const [time, setTime] = useState<string | null>(null);
  const [interests, setInterests] = useState<string[] | null>(null);
  const [notes, setNotes] = useState<string | null>(null);
  const [area, setArea] = useState<{ id: string; name: string } | null>(null);
  const [locating, setLocating] = useState(false);
  const [locError, setLocError] = useState<string | null>(null);

  const s = status.data;
  const p = s?.preferences;
  // Defaults come from earlier preferences (when updating) or sensible values.
  const vName = name ?? s?.full_name ?? me?.full_name ?? '';
  const vLangs = langs ?? p?.languages ?? (s?.preferred_language ? [s.preferred_language] : []);
  const vMethod = method ?? p?.contact_method ?? 'CALL';
  const vTime = time ?? p?.contact_time ?? 'ANYTIME';
  const vInterests = interests ?? p?.interest_category_ids ?? [];
  const vNotes = notes ?? p?.notes ?? '';
  const toggle = (list: string[], v: string) => (list.includes(v) ? list.filter((x) => x !== v) : [...list, v]);

  const locate = async () => {
    setLocating(true);
    setLocError(null);
    try {
      const loc = await getCurrentLocation();
      if (loc.locality_id && loc.in_service_area) setArea({ id: loc.locality_id, name: loc.locality_name ?? 'Your area' });
      else setLocError('You are outside our current service area — we will still match you with a city agent.');
    } catch (e) {
      setLocError(e instanceof Error ? e.message : 'Could not get your location');
    } finally {
      setLocating(false);
    }
  };

  const submit = async () => {
    const r = await act.run(() => api.post<SubscribeResult>('/api/v1/customer/agent/subscribe', {
      full_name: vName.trim() || null, languages: vLangs, contact_method: vMethod, contact_time: vTime,
      interest_category_ids: vInterests, locality_id: area?.id ?? null, notes: vNotes.trim() || null,
    }));
    if (r) onDone(r);
  };

  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title={s?.plan === 'DEDICATED' ? 'Agent preferences' : 'Get your dedicated agent'} subtitle="We match you with the best agent for you" onBack={onBack} />
      <Screen footer={
        <Button title={act.busy ? 'Finding your agent…' : s?.plan === 'DEDICATED' ? 'Save preferences' : 'Subscribe & match my agent'}
          loading={act.busy} disabled={!vLangs.length || status.loading} onPress={submit} />
      }>
        {status.loading ? <Loading /> : status.error ? <ErrorState message={status.error} onRetry={status.reload} /> : (
          <>
            <Card>
              <TextField label="Your name" value={vName} onChangeText={setName} placeholder="Full name" autoComplete="name" />
            </Card>

            <Card>
              <Text style={styles.q}>Which languages are you comfortable with? *</Text>
              <Text style={styles.hint}>Tap in order of preference — your first choice matters most.</Text>
              <View style={styles.chips}>
                {(s?.language_options ?? []).map((l) => {
                  const i = vLangs.indexOf(l.code);
                  return <Chip key={l.code} label={i >= 0 ? `${i + 1}. ${l.label}` : l.label} selected={i >= 0} onPress={() => setLangs(toggle(vLangs, l.code).slice(0, 5))} />;
                })}
              </View>
            </Card>

            <Card>
              <Text style={styles.q}>How should your agent reach you?</Text>
              <View style={styles.chips}>
                {(s?.contact_methods ?? []).map((m) => <Chip key={m} label={METHOD_LABEL[m] ?? m} selected={vMethod === m} onPress={() => setMethod(m)} />)}
              </View>
              <Text style={[styles.q, { marginTop: space.md }]}>Best time to contact you</Text>
              <View style={styles.chips}>
                {(s?.contact_times ?? []).map((t) => <Chip key={t} label={TIME_LABEL[t] ?? t} selected={vTime === t} onPress={() => setTime(t)} />)}
              </View>
            </Card>

            <Card>
              <Text style={styles.q}>Your area</Text>
              <Text style={styles.hint}>Agents who cover your locality are preferred.</Text>
              <Row style={{ marginTop: space.sm, gap: space.sm }}>
                <Button title={locating ? 'Locating…' : '📍 Use current location'} small variant="outline" onPress={locate} disabled={locating} />
                {area ? <Text style={{ color: colors.forest, fontWeight: '700' }}>✓ {area.name}</Text> : null}
              </Row>
              {locError ? <Text style={{ color: colors.textSecondary, fontSize: font.xs, marginTop: 6 }}>{locError}</Text> : null}
            </Card>

            <Card>
              <Text style={styles.q}>What will you mostly need help with? (optional)</Text>
              <View style={styles.chips}>
                {(cats.data ?? []).map((c) => <Chip key={c.id} label={c.name} selected={vInterests.includes(c.id)} onPress={() => setInterests(toggle(vInterests, c.id))} />)}
              </View>
              <TextField label="Anything your agent should know? (optional)" value={vNotes} onChangeText={setNotes} multiline
                placeholder="e.g. Senior citizen at home, prefer weekend visits" />
            </Card>
            <Notice tone="info" message="Free during launch. Your agent is matched on language, area, availability and workload. A backup agent is assigned too." />
            {act.error ? <ErrorState message={act.error} /> : null}
          </>
        )}
      </Screen>
    </View>
  );
}

/** Shows who was matched, right after subscribing. */
export function MatchedScreen({ result, onContinue }: { result: SubscribeResult; onContinue: () => void }) {
  const a = result.agent;
  return (
    <LinearGradient colors={gradient.primary} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={{ flex: 1 }}>
      <SafeAreaView style={{ flex: 1, justifyContent: 'center', padding: space.xl }}>
        {a ? (
          <View style={styles.matchCard}>
            <Text style={{ fontSize: 40, textAlign: 'center' }}>🎉</Text>
            <Text style={styles.matchTitle}>{result.already_had_agent ? 'Preferences saved' : 'Meet your dedicated agent'}</Text>
            <View style={{ alignItems: 'center', marginVertical: space.md }}>
              <Avatar name={a.display_name} size={84} />
              <Text style={{ fontSize: font.xl, fontWeight: '900', color: colors.text, marginTop: space.sm }}>{a.display_name}</Text>
              {a.shared_languages?.length ? <Text style={{ color: colors.emerald, fontWeight: '700' }}>Speaks {a.shared_languages.join(' & ')}</Text> : null}
              <Text style={{ color: colors.textSecondary, fontSize: font.sm }}>{a.wing ? `${a.wing} • ` : ''}Available {a.shift_start}–{a.shift_end}</Text>
            </View>
            <Row style={{ justifyContent: 'center', gap: space.sm, marginBottom: space.md }}>
              <Button title="📞 Call" small variant="secondary" onPress={() => callPhone(a.phone)} />
              <Button title="💬 WhatsApp" small variant="secondary" onPress={() => openWhatsApp(a.phone)} />
            </Row>
            <Text style={{ color: colors.textSecondary, textAlign: 'center', marginBottom: space.md }}>
              {a.display_name.split(' ')[0]} will handle all your requests. Book from the menu or just write to them.
            </Text>
            <Button title="Start using BUZZER" onPress={onContinue} />
          </View>
        ) : (
          <View style={styles.matchCard}>
            <Text style={styles.matchTitle}>You're subscribed!</Text>
            <Text style={{ color: colors.textSecondary, textAlign: 'center', marginVertical: space.md }}>
              All agents who match your preferences are busy right now. We'll assign your dedicated agent shortly and notify you. Meanwhile our on-duty team handles your requests.
            </Text>
            <Button title="Continue" onPress={onContinue} />
          </View>
        )}
      </SafeAreaView>
    </LinearGradient>
  );
}

/**
 * Wraps the app: new customers see Welcome → (Subscribe form → Matched) or Explore, then the normal app.
 * Keyed by user so a different sign-in starts fresh.
 */
export function OnboardingGate({ children }: { children: React.ReactNode }) {
  const { me } = useAuth();
  const status = useQuery(() => api.get<Onboarding>('/api/v1/customer/onboarding'), [me?.id]);
  const act = useAction();
  const [step, setStep] = useState<'welcome' | 'form' | 'matched' | 'done'>('welcome');
  const [result, setResult] = useState<SubscribeResult | null>(null);

  if (status.loading) return <Loading label="Setting things up…" />;
  if (status.error) return <Screen><ErrorState message={status.error} onRetry={status.reload} /></Screen>;
  if (status.data?.onboarded || step === 'done') return <>{children}</>;
  if (step === 'form') return <SubscribeForm onBack={() => setStep('welcome')} onDone={(r) => { setResult(r); setStep('matched'); }} />;
  if (step === 'matched' && result) return <MatchedScreen result={result} onContinue={() => setStep('done')} />;
  return (
    <WelcomeScreen name={status.data?.full_name ?? me?.full_name} exploring={act.busy} onSubscribe={() => setStep('form')}
      onExplore={async () => { if (await act.run(() => api.post('/api/v1/customer/onboarding/explore', {}))) setStep('done'); }} />
  );
}

/** Stack route used from Home / Profile ("Get a dedicated agent" / "Agent preferences"). */
export function SubscribeAgentScreen() {
  const nav = useNav();
  const [result, setResult] = useState<SubscribeResult | null>(null);
  if (result) return <MatchedScreen result={result} onContinue={() => nav.reset('Tabs')} />;
  return <SubscribeForm onBack={nav.pop} onDone={setResult} />;
}

const styles = StyleSheet.create({
  welcomeWrap: { padding: space.xl, paddingTop: space.xxl, alignItems: 'center' },
  logo: { width: 64, height: 64, borderRadius: 18, backgroundColor: '#B8F07A', alignItems: 'center', justifyContent: 'center', marginBottom: space.md },
  logoB: { fontSize: 36, fontWeight: '900', color: colors.forest },
  hello: { color: colors.white, fontSize: 28, fontWeight: '900', textAlign: 'center' },
  sub: { color: '#CFEBD9', fontSize: font.md, marginTop: 4, marginBottom: space.xl, textAlign: 'center' },
  benefits: { backgroundColor: colors.white, borderRadius: radius.xl, padding: space.lg, width: '100%', maxWidth: 480 },
  welcomeFooter: { padding: space.lg, paddingBottom: space.xl, alignItems: 'center', gap: space.sm },
  subscribeBtn: { backgroundColor: '#B8F07A', borderRadius: radius.pill, paddingVertical: 14, paddingHorizontal: space.xl, width: '100%', maxWidth: 480, alignItems: 'center' },
  subscribeText: { color: colors.forest, fontWeight: '900', fontSize: font.lg },
  subscribeHint: { color: colors.forest, fontSize: font.xs, opacity: 0.8 },
  exploreBtn: { borderRadius: radius.pill, borderWidth: 1.5, borderColor: 'rgba(255,255,255,0.7)', paddingVertical: 12, width: '100%', maxWidth: 480, alignItems: 'center' },
  exploreText: { color: colors.white, fontWeight: '800', fontSize: font.md },
  footNote: { color: '#CFEBD9', fontSize: font.xs },
  q: { fontWeight: '800', color: colors.text },
  hint: { color: colors.textSecondary, fontSize: font.xs, marginTop: 2 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, marginTop: space.sm, marginBottom: space.sm },
  matchCard: { backgroundColor: colors.white, borderRadius: radius.xl, padding: space.xl, width: '100%', maxWidth: 480, alignSelf: 'center' },
  matchTitle: { fontSize: font.xl, fontWeight: '900', color: colors.forest, textAlign: 'center', marginTop: space.sm },
});
