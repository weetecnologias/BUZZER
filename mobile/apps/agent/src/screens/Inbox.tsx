import React, { useState } from 'react';
import { Text, View } from 'react-native';
import { api, ago, Badge, Card, Chip, colors, EmptyState, ErrorState, font, GradientHeader, Loading, Row, Screen, StatusBadge, useNav, useOnRefocus, useQuery } from '@buzzer/core';

const FILTERS: [string, string][] = [['', 'All'], ['REQUEST_CREATED', 'New'], ['AGENT_REVIEW', 'Reviewing'], ['PARTNER_QUOTATION', 'Quoting'],
  ['CUSTOMER_APPROVAL', 'With customer'], ['PARTNER_DECLINED', 'Reassign'], ['PARTNER_CONFIRMED', 'Booked']];

export function InboxScreen() {
  const nav = useNav();
  const [status, setStatus] = useState('');
  const q = useQuery(() => api.get<any[]>(`/api/v1/agent/requests${status ? `?status=${status}` : ''}`), [status]);
  useOnRefocus(q.refresh);
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="Request inbox" subtitle="Un-answered first, then by SLA" />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        <Row style={{ flexWrap: 'wrap' }}>{FILTERS.map(([k, l]) => <Chip key={k} label={l} selected={status === k} onPress={() => setStatus(k)} />)}</Row>
        {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : (q.data ?? []).length === 0 ? (
          <EmptyState title="Inbox clear" message="New requests from your customers will appear here." />
        ) : (q.data ?? []).map((r) => {
          const breached = !r.first_agent_response_at && r.sla_due_at && new Date(r.sla_due_at).getTime() < Date.now();
          return (
            <Card key={r.id} onPress={() => nav.push('Request', { id: r.id })} style={breached ? { borderColor: colors.danger } : undefined} accessibilityLabel={`Request ${r.request_number}`}>
              <Row style={{ justifyContent: 'space-between' }}>
                <Text style={{ fontWeight: '700', color: colors.text, flex: 1 }}>{r.service_name || r.subcategory_name || r.category_name}</Text>
                <StatusBadge status={r.status} />
              </Row>
              <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>#{r.request_number} • {r.customer_name ?? r.customer_phone} • {ago(r.created_at)}</Text>
              <Row style={{ marginTop: 6 }}>
                {breached ? <Badge label="SLA breached" tone="danger" /> : null}
                {r.escalated_at ? <Badge label="Escalated" tone="warning" /> : null}
                {r.created_via === 'AGENT_APP' && !r.customer_confirmed_at ? <Badge label="Awaiting customer confirmation" tone="neutral" /> : null}
                {Number(r.quotation_count) > 0 ? <Badge label={`${r.quotation_count} quotes`} tone="info" /> : null}
              </Row>
            </Card>
          );
        })}
      </Screen>
    </View>
  );
}
