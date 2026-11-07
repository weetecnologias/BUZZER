import React from 'react';
import { Text, View } from 'react-native';
import {
  api, Button, Card, Chip, colors, ErrorState, font, GradientHeader, KeyValue, Loading, Notice, Row, Screen, space, useAction, useAuth,
  useOnRefocus, useQuery,
} from '@buzzer/core';

export function DashboardScreen({ onOpenTab }: { onOpenTab: (t: string) => void }) {
  const { signOut } = useAuth();
  const q = useQuery(() => api.get<any>('/api/v1/agent/dashboard'));
  const me = useQuery(() => api.get<any>('/api/v1/agent/me'));
  useOnRefocus(q.refresh);
  const act = useAction();
  const [note, setNote] = React.useState<string | null>(null);

  const setAvailability = async (availability: string) => {
    const r = await act.run(() => api.put<{ escalated_requests: number }>('/api/v1/agent/availability', { availability }));
    if (r) {
      setNote(r.escalated_requests ? `${r.escalated_requests} un-answered request(s) were handed to your backup / manager.` : null);
      me.refresh();
    }
  };

  const c = q.data?.counts;
  const p = q.data?.performance_30d;
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title={me.data ? `Welcome, ${me.data.display_name}` : 'Agent Dashboard'} subtitle={me.data ? `${me.data.city_name}${me.data.is_manager ? ' • Manager' : ''}` : undefined} />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : (
          <>
            <Card>
              <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>My availability</Text>
              <Row style={{ flexWrap: 'wrap' }}>
                {['AVAILABLE', 'BUSY', 'OFFLINE', 'ON_LEAVE'].map((s) => (
                  <Chip key={s} label={s.replace('_', ' ')} selected={me.data?.availability === s} onPress={() => setAvailability(s)} />
                ))}
              </Row>
              {note ? <Notice tone="warning" message={note} /> : null}
            </Card>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
              {[
                ['New requests', c.new_requests],
                ['In progress', c.in_progress],
                ['Active jobs', c.active_jobs],
                ['Needs attention', c.needs_attention],
                ['SLA breached', c.sla_breached],
                ['Completed today', c.completed_today],
              ].map(([label, v]) => (
                <Card key={String(label)} onPress={() => onOpenTab('inbox')} style={{ flexGrow: 1, minWidth: '30%', alignItems: 'center', padding: space.md }}>
                  <Text style={{ fontSize: font.xxl, fontWeight: '900', color: label === 'SLA breached' && Number(v) > 0 ? colors.danger : colors.forest }}>{String(v)}</Text>
                  <Text style={{ color: colors.textSecondary, fontSize: font.xs, textAlign: 'center' }}>{String(label)}</Text>
                </Card>
              ))}
            </View>
            <Card>
              <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>My performance (30 days)</Text>
              <KeyValue k="Requests handled" v={String(p.total_requests)} />
              <KeyValue k="Avg first response" v={p.avg_first_response_minutes != null ? `${p.avg_first_response_minutes} min` : '—'} />
              <KeyValue k="Avg resolution" v={p.avg_resolution_hours != null ? `${p.avg_resolution_hours} h` : '—'} />
              <KeyValue k="Portfolio size" v={String(p.portfolio_size)} />
            </Card>
            <Button title="Open request inbox" onPress={() => onOpenTab('inbox')} />
            {act.error ? <ErrorState message={act.error} /> : null}
            <Button title="Log out" variant="ghost" onPress={signOut} />
          </>
        )}
      </Screen>
    </View>
  );
}
