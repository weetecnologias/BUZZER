import React, { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  api, ago, Badge, Card, Chip, colors, EmptyState, ErrorState, font, GradientHeader, Loading, Row, Screen, space, StatusBadge, statusLabel,
  useNav, useOnRefocus, useQuery,
} from '@buzzer/core';

type Req = Record<string, any>;
type Bucket = 'NEW' | 'ACTION' | 'AUTOMATED' | 'CUSTOMER' | 'PROGRESS' | 'CLOSED';

const CLOSED = ['SETTLED', 'CANCELLED', 'REJECTED', 'EXPIRED', 'REFUNDED'];
const WITH_CUSTOMER = ['CUSTOMER_APPROVAL', 'PAYMENT_PENDING'];
const ACTION = ['REQUEST_CREATED', 'AGENT_REVIEW', 'PARTNER_QUOTATION', 'PARTNER_DECLINED', 'NO_SHOW', 'DISPUTED', 'REFUND_PENDING'];

/** Which list a ticket belongs to. "New" (never opened) is shown on top of every list as well. */
function bucket(r: Req): Exclude<Bucket, 'NEW'> {
  if (CLOSED.includes(r.status)) return 'CLOSED';
  if (r.handling_mode === 'AUTOMATED') return 'AUTOMATED';
  if (WITH_CUSTOMER.includes(r.status)) return 'CUSTOMER';
  if (ACTION.includes(r.status) || r.escalated_at) return 'ACTION';
  return 'PROGRESS';
}

const TABS: [Bucket, string][] = [
  ['NEW', '🆕 New'], ['ACTION', '⚡ Needs action'], ['AUTOMATED', '🤖 Automated'], ['CUSTOMER', '💬 With customer'],
  ['PROGRESS', '🛠️ In progress'], ['CLOSED', '✅ Closed'],
];

const MODE_LABEL: Record<string, string> = {
  DIRECT_QUOTE: 'Quotes direct from partners', AGENT_ASSISTED: 'Sent to agent', AGENT_DIRECT: 'Written to agent', SELF: 'Booked at listed prices',
};

function sla(r: Req): { label: string; tone: 'danger' | 'warning' | 'neutral' } | null {
  if (r.first_agent_response_at || r.handling_mode === 'AUTOMATED' || !r.sla_due_at || CLOSED.includes(r.status)) return null;
  const mins = Math.round((new Date(r.sla_due_at).getTime() - Date.now()) / 60000);
  if (mins < 0) return { label: `SLA breached ${-mins < 60 ? -mins + ' min' : Math.round(-mins / 60) + ' h'} ago`, tone: 'danger' };
  return { label: `Reply within ${mins < 60 ? mins + ' min' : Math.round(mins / 60) + ' h'}`, tone: mins < 30 ? 'warning' : 'neutral' };
}

export function InboxScreen() {
  const nav = useNav();
  const [tab, setTab] = useState<Bucket>('ACTION');
  const q = useQuery(() => api.get<Req[]>('/api/v1/agent/requests'));
  useOnRefocus(q.refresh);
  const all = q.data ?? [];
  const isNew = (r: Req) => !r.agent_seen_at && !CLOSED.includes(r.status);
  const counts = Object.fromEntries(TABS.map(([k]) => [k, k === 'NEW' ? all.filter(isNew).length : all.filter((r) => bucket(r) === k).length])) as Record<Bucket, number>;
  const shown = (tab === 'NEW' ? all.filter(isNew) : all.filter((r) => bucket(r) === tab))
    .sort((a, b) => Number(isNew(b)) - Number(isNew(a)));

  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="Tickets" subtitle={counts.NEW ? `${counts.NEW} new • ${counts.ACTION} need action` : `${counts.ACTION} need action • ${counts.AUTOMATED} running automatically`} />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        <Row style={{ flexWrap: 'wrap' }}>
          {TABS.map(([k, label]) => (
            <Chip key={k} label={`${label}${counts[k] ? ` (${counts[k]})` : ''}`} selected={tab === k} onPress={() => setTab(k)} />
          ))}
        </Row>
        {tab === 'AUTOMATED' ? (
          <Text style={styles.hint}>These run on their own: partners quote straight to your customer, who approves and pays. Open one to follow it or take over.</Text>
        ) : null}
        {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : shown.length === 0 ? (
          <EmptyState title={tab === 'NEW' ? 'No new tickets' : 'Nothing here'} message={tab === 'ACTION' ? 'You are all caught up.' : 'Tickets appear here as they move.'} />
        ) : shown.map((r) => {
          const fresh = isNew(r);
          const s = sla(r);
          return (
            <Card key={r.id} onPress={() => nav.push('Request', { id: r.id })} accessibilityLabel={`Ticket ${r.request_number}${fresh ? ', new' : ''}`}
              style={[fresh && styles.fresh, s?.tone === 'danger' && styles.breached]}>
              <Row style={{ justifyContent: 'space-between' }}>
                <Row style={{ flex: 1, gap: 6 }}>
                  {fresh ? <View style={styles.newPill}><Text style={styles.newPillText}>NEW</Text></View> : null}
                  <Text style={{ fontWeight: fresh ? '900' : '700', color: colors.text, flex: 1 }} numberOfLines={1}>
                    {r.service_name || r.subcategory_name || r.category_name}
                  </Text>
                </Row>
                <StatusBadge status={r.status} />
              </Row>
              <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>
                #{r.request_number} • {r.customer_name ?? r.customer_phone} • created {ago(r.created_at)}
              </Text>
              <Row style={{ marginTop: 6, flexWrap: 'wrap', gap: 4 }}>
                {r.handling_mode === 'AUTOMATED' ? <Badge label="🤖 Automated" tone="info" /> : null}
                {r.handed_to_agent_at ? <Badge label={`Handed to you: ${String(r.handover_reason ?? '').toLowerCase()}`} tone="warning" /> : null}
                {s ? <Badge label={s.label} tone={s.tone} /> : null}
                {r.escalated_at ? <Badge label="Escalated" tone="warning" /> : null}
                {r.agent_source === 'ON_DUTY' ? <Badge label="On-duty (not subscribed)" tone="neutral" /> : null}
                {r.created_via === 'AGENT_APP' && !r.customer_confirmed_at ? <Badge label="Awaiting customer confirmation" tone="neutral" /> : null}
                {Number(r.item_count) > 0 ? <Badge label={`${r.item_count} items`} tone="neutral" /> : null}
                {Number(r.quotation_count) > 0 ? <Badge label={`${r.quotation_count} quotes`} tone="info" /> : null}
              </Row>
              <Text style={styles.last}>
                {MODE_LABEL[r.booking_mode] ?? ''}{r.last_activity_at ? ` • last update ${ago(r.last_activity_at)}` : ''} • {statusLabel(r.status)}
              </Text>
            </Card>
          );
        })}
      </Screen>
    </View>
  );
}

const styles = StyleSheet.create({
  fresh: { borderLeftWidth: 6, borderLeftColor: colors.secondary, backgroundColor: '#F4FFF7' },
  breached: { borderColor: colors.danger },
  newPill: { backgroundColor: colors.secondary, borderRadius: 999, paddingHorizontal: 8, paddingVertical: 2 },
  newPillText: { color: colors.white, fontWeight: '900', fontSize: 11, letterSpacing: 0.5 },
  last: { color: colors.textSecondary, fontSize: font.xs, marginTop: 6 },
  hint: { color: colors.textSecondary, fontSize: font.sm, marginBottom: space.sm },
});
