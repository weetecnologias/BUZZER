import React from 'react';
import { Text, View } from 'react-native';
import { api } from './api';
import { ago } from './format';
import { useQuery } from './hooks';
import { colors, font, space } from './theme';
import { Card, EmptyState, ErrorState, GradientHeader, Loading, Row, Screen } from './ui';

type Item = { id: string; title: string; body: string; read_at: string | null; created_at: string; ref_type: string | null; ref_id: string | null };

/** Shared notification inbox for all three apps. */
export function NotificationsInbox({ onOpen, onBack }: { onOpen?: (n: Item) => void; onBack?: () => void }) {
  const q = useQuery(() => api.get<Item[]>('/api/v1/notifications?limit=100'));
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="Updates" subtitle="Notifications and alerts" onBack={onBack} />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : (q.data ?? []).length === 0 ? (
          <EmptyState title="You're all caught up" message="Updates about your requests appear here." />
        ) : (q.data ?? []).map((n) => (
          <Card key={n.id} onPress={async () => {
            if (!n.read_at) await api.post(`/api/v1/notifications/${n.id}/read`).catch(() => undefined);
            onOpen?.(n);
            q.refresh();
          }} style={!n.read_at ? { borderColor: colors.secondary } : undefined}>
            <Row style={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <Text style={{ fontWeight: n.read_at ? '600' : '800', color: colors.text, flex: 1 }}>{n.title}</Text>
              <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{ago(n.created_at)}</Text>
            </Row>
            <Text style={{ color: colors.textSecondary, marginTop: space.xs }}>{n.body}</Text>
          </Card>
        ))}
      </Screen>
    </View>
  );
}
