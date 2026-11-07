import React from 'react';
import { Pressable, Text, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import {
  api, Badge, Card, colors, ErrorState, font, GradientHeader, gradient, Loading, money, Notice, radius, Row, Screen, space, StatusBadge,
  Toggle, useAction, useNav, useOnRefocus, useQuery,
} from '@buzzer/core';

export function DashboardScreen({ onOpenTab }: { onOpenTab: (t: string) => void }) {
  const nav = useNav();
  const q = useQuery(() => api.get<any>('/api/v1/partner/dashboard'));
  useOnRefocus(q.refresh);
  const act = useAction();
  const d = q.data;
  const p = d?.profile;
  const verified = p?.registration_status === 'APPROVED' && p?.kyc_status === 'VERIFIED';

  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title={p ? `Good day, ${p.display_name}` : 'Partner Dashboard'} subtitle={p?.business_name ?? p?.city_name}
        right={<Pressable onPress={() => nav.push('Notifications')} accessibilityLabel="Notifications" hitSlop={10}><Text style={{ fontSize: 22 }}>🔔</Text></Pressable>} />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : (
          <>
            <Card>
              <Row style={{ justifyContent: 'space-between' }}>
                <Text style={{ fontWeight: '700', color: colors.text }}>Account status</Text>
                {verified ? <Badge label="✓ Verified partner" tone="success" /> : <Badge label="Verification pending" tone="warning" />}
              </Row>
              <Row style={{ marginTop: space.sm, flexWrap: 'wrap' }}>
                <Text style={{ color: colors.textSecondary }}>Registration</Text><StatusBadge status={p.registration_status} />
                <Text style={{ color: colors.textSecondary }}>KYC</Text><StatusBadge status={p.kyc_status} />
              </Row>
              {!verified ? <Notice tone="warning" message="You will start receiving leads once registration is approved and KYC is verified. Submit KYC from the Account tab." /> : null}
              <Toggle label={p.availability === 'AVAILABLE' ? 'Available for new leads' : 'Not taking leads'} value={p.availability === 'AVAILABLE'}
                onChange={async (v) => { if (await act.run(() => api.put('/api/v1/partner/availability', { availability: v ? 'AVAILABLE' : 'UNAVAILABLE' }))) q.refresh(); }} />
            </Card>

            <LinearGradient colors={gradient.primary} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={{ borderRadius: radius.lg, padding: space.lg }}>
              <Text style={{ color: '#CFEBD9' }}>Total earned (after commission)</Text>
              <Text style={{ color: colors.white, fontSize: font.display, fontWeight: '900' }}>{money(d.earnings.total_earned)}</Text>
              <Text style={{ color: '#CFEBD9', fontSize: font.xs }}>Paid out {money(d.earnings.total_paid_out)} • Commission {money(d.earnings.commission_deducted)}</Text>
            </LinearGradient>

            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
              {[
                ['New leads', d.counts.new_leads, 'leads'],
                ['Quotes sent', d.counts.quotes_pending, 'leads'],
                ['To confirm', d.counts.awaiting_confirmation, 'jobs'],
                ['In progress', d.counts.in_progress, 'jobs'],
                ['Completed', d.counts.completed, 'jobs'],
              ].map(([label, value, tab]) => (
                <Card key={String(label)} onPress={() => onOpenTab(String(tab))} style={{ flexGrow: 1, minWidth: '30%', alignItems: 'center', padding: space.md }}>
                  <Text style={{ fontSize: font.xxl, fontWeight: '900', color: colors.forest }}>{String(value)}</Text>
                  <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{String(label)}</Text>
                </Card>
              ))}
            </View>
            {act.error ? <ErrorState message={act.error} /> : null}
          </>
        )}
      </Screen>
    </View>
  );
}
