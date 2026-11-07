import React from 'react';
import { Text, View } from 'react-native';
import { api, Card, colors, date, dateTime, EmptyState, ErrorState, font, GradientHeader, KeyValue, Loading, money, Row, Screen, space, StatusBadge, useQuery } from '@buzzer/core';

const ACCOUNT_LABELS: Record<string, string> = {
  PARTNER_EARNING: 'Earning',
  PLATFORM_COMMISSION: 'Commission',
  PARTNER_PAYOUT: 'Payout',
  CASH_COLLECTED_BY_PARTNER: 'Cash collected',
};

export function EarningsScreen() {
  const q = useQuery(() => api.get<any>('/api/v1/partner/earnings'));
  const d = q.data;
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="Earnings" subtitle="Ledger-based statement" />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : (
          <>
            <Card>
              <KeyValue k="Total earned (after commission)" v={money(d.summary.total_earned)} />
              <KeyValue k="Commission deducted" v={money(d.summary.commission_deducted)} />
              <KeyValue k="Cash collected from customers" v={money(d.summary.cash_collected)} />
              <KeyValue k="Paid out to your bank" v={money(d.summary.total_paid_out)} />
              <Text style={{ color: colors.textSecondary, fontSize: font.xs, marginTop: space.sm }}>
                Payout target is T+1 working day, subject to bank processing, holidays, refunds and reconciliation.
              </Text>
            </Card>
            <Text style={{ fontWeight: '700', fontSize: font.lg, color: colors.text }}>Payouts</Text>
            {(d.payouts ?? []).length === 0 ? <EmptyState title="No payouts yet" /> : d.payouts.map((p: any) => (
              <Card key={p.id}>
                <Row style={{ justifyContent: 'space-between' }}>
                  <Text style={{ fontWeight: '700', color: colors.text }}>{money(p.amount)}</Text>
                  <StatusBadge status={p.status} />
                </Row>
                <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{p.payout_number} • #{p.request_number} • due {date(p.due_on)}{p.reference ? ` • Ref ${p.reference}` : ''}</Text>
              </Card>
            ))}
            <Text style={{ fontWeight: '700', fontSize: font.lg, color: colors.text }}>Ledger</Text>
            <Card>
              {(d.entries ?? []).length === 0 ? <Text style={{ color: colors.textSecondary }}>No entries yet.</Text> : d.entries.map((e: any, i: number) => (
                <KeyValue key={i} k={`${ACCOUNT_LABELS[e.account] ?? e.account} • #${e.request_number ?? ''} • ${dateTime(e.created_at)}`}
                  v={`${e.direction === 'DEBIT' ? '−' : '+'}${money(e.amount)}`} />
              ))}
            </Card>
          </>
        )}
      </Screen>
    </View>
  );
}
