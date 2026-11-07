import React, { useState } from 'react';
import { Text, View } from 'react-native';
import { api, Card, Chip, colors, dateTime, EmptyState, ErrorState, font, GradientHeader, Loading, Row, Screen, space, StatusBadge, useNav, useOnRefocus, useQuery } from '@buzzer/core';

export function RequestsScreen() {
  const nav = useNav();
  const [openOnly, setOpenOnly] = useState(true);
  const q = useQuery(() => api.get<any[]>(`/api/v1/customer/requests?openOnly=${openOnly}`), [openOnly]);
  useOnRefocus(q.refresh);
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="My requests" subtitle="Track every request and booking" />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        <Row>
          <Chip label="Active" selected={openOnly} onPress={() => setOpenOnly(true)} />
          <Chip label="All history" selected={!openOnly} onPress={() => setOpenOnly(false)} />
        </Row>
        {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : (q.data ?? []).length === 0 ? (
          <EmptyState title={openOnly ? 'No active requests' : 'No requests yet'} message="Your requests will appear here." />
        ) : (q.data ?? []).map((r) => (
          <Card key={r.id} onPress={() => nav.push('RequestDetail', { id: r.id })} accessibilityLabel={`Request ${r.request_number}`}>
            <Row style={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <View style={{ flex: 1 }}>
                <Text style={{ fontWeight: '700', color: colors.text }}>{r.service_name || r.subcategory_name || r.category_name}</Text>
                <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>#{r.request_number} • {dateTime(r.created_at)}</Text>
              </View>
              <StatusBadge status={r.status} />
            </Row>
            {Number(r.quotation_count) > 0 ? <Text style={{ color: colors.emerald, fontSize: font.xs, marginTop: space.sm }}>{r.quotation_count} quotation(s)</Text> : null}
          </Card>
        ))}
      </Screen>
    </View>
  );
}
