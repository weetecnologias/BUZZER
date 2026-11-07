import React, { useState } from 'react';
import { Text, View } from 'react-native';
import { api, Card, Chip, colors, dateTime, EmptyState, ErrorState, font, GradientHeader, Loading, Row, Screen, space, StatusBadge, useNav, useOnRefocus, useQuery } from '@buzzer/core';

const TYPES: [string, string][] = [['', 'All'], ['PRODUCT_RFQ', '🧾 Quotes'], ['SERVICE_BOOKING', '🛠️ Services'], ['TASK_REQUEST', '🛵 Tasks'],
  ['PERSONAL_ASSISTANCE', '🧑‍💼 Assistance']];
const GROUPS: [string, string][] = [['ACTIVE', 'Active'], ['ACTION_NEEDED', 'Needs you'], ['DONE', 'Done'], ['CLOSED', 'Closed'], ['', 'All']];

export function RequestsScreen() {
  const nav = useNav();
  const [type, setType] = useState('');
  const [group, setGroup] = useState('ACTIVE');
  const q = useQuery(() => api.get<any[]>(`/api/v1/customer/requests?openOnly=false${type ? `&type=${type}` : ''}${group && group !== 'ACTIVE' ? `&group=${group}` : ''}`), [type, group]);
  const rows = (q.data ?? []).filter((r) => group !== 'ACTIVE' || ['OPEN', 'ACTION_NEEDED', 'IN_PROGRESS'].includes(r.status_group));
  const openOnly = group === 'ACTIVE';
  useOnRefocus(q.refresh);
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="My Requests" subtitle="Quotes, bookings, tasks and help requests" />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        <Row style={{ flexWrap: 'wrap' }}>
          {TYPES.map(([k, l]) => <Chip key={k} label={l} selected={type === k} onPress={() => setType(k)} />)}
        </Row>
        <Row style={{ flexWrap: 'wrap' }}>
          {GROUPS.map(([k, l]) => <Chip key={k} label={l} selected={group === k} onPress={() => setGroup(k)} />)}
        </Row>
        {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : rows.length === 0 ? (
          <EmptyState title={openOnly ? 'No active requests' : 'No requests yet'} message="Your requests will appear here." />
        ) : rows.map((r) => (
          <Card key={r.id} onPress={() => nav.push('RequestDetail', { id: r.id })} accessibilityLabel={`Request ${r.request_number}`}>
            <Row style={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <View style={{ flex: 1 }}>
                <Text style={{ fontWeight: '700', color: colors.text }}>{r.service_name || r.subcategory_name || r.category_name}</Text>
                <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>#{r.request_number} • {dateTime(r.created_at)}</Text>
              </View>
              <StatusBadge status={r.status} />
            </Row>
            <Row style={{ marginTop: space.sm, gap: space.sm, flexWrap: 'wrap' }}>
              <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{TYPES.find(([k]) => k === r.request_type)?.[1] ?? r.request_type}</Text>
              {Number(r.quotation_count) > 0 ? <Text style={{ color: colors.emerald, fontSize: font.xs }}>{r.quotation_count} quotation(s)</Text> : null}
              {Number(r.attachment_count) > 0 ? <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>📎 {r.attachment_count}</Text> : null}
              {r.status_group === 'ACTION_NEEDED' ? <Text style={{ color: colors.warning, fontSize: font.xs, fontWeight: '700' }}>Needs your decision</Text> : null}
            </Row>
          </Card>
        ))}
      </Screen>
    </View>
  );
}
