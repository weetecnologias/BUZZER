import React, { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { colors, font, radius, space } from './theme';
import { ago, dateTime, statusLabel } from './format';

export type HistoryEntry = {
  from_status: string | null; to_status: string; actor_role: string | null; actor_name?: string | null; reason: string | null; created_at: string;
};

const ICON: Record<string, string> = {
  REQUEST_CREATED: '📝', AGENT_REVIEW: '👀', PARTNER_QUOTATION: '📨', CUSTOMER_APPROVAL: '💬', PAYMENT_PENDING: '💳', PAYMENT_CONFIRMED: '✅',
  PARTNER_ASSIGNED: '🤝', PARTNER_CONFIRMED: '👍', PARTNER_ARRIVED: '📍', SERVICE_STARTED: '🛠️', IN_PROGRESS: '🛠️', COMPLETION_PENDING: '⏳',
  COMPLETED: '🎉', SETTLEMENT_PENDING: '🧾', SETTLED: '🏁', CANCELLED: '✖️', REJECTED: '✖️', EXPIRED: '⌛', PARTNER_DECLINED: '↩️', NO_SHOW: '🚫',
  DISPUTED: '⚠️', REFUND_PENDING: '💸', REFUNDED: '💸', RESCHEDULING_REQUESTED: '📅',
};

function iconFor(h: HistoryEntry): string {
  const r = (h.reason ?? '').toLowerCase();
  if (r.includes('transferred')) return '🔀';
  if (r.includes('handed to')) return '🧑‍💼';
  if (r.includes('revise')) return '✏️';
  if (r.includes('quote')) return '🏷️';
  if (r.includes('escalat') || r.includes('breach')) return '⏰';
  if (h.actor_role === 'SYSTEM' && h.from_status === h.to_status) return '🤖';
  return ICON[h.to_status] ?? '•';
}

function who(h: HistoryEntry): string {
  switch (h.actor_role) {
    case 'SYSTEM': return '🤖 Automatic';
    case 'CUSTOMER': return h.actor_name ? `Customer • ${h.actor_name}` : 'Customer';
    case 'AGENT':
    case 'AGENT_MANAGER': return h.actor_name ? `Agent • ${h.actor_name}` : 'Agent';
    case 'PARTNER': return h.actor_name ? `Partner • ${h.actor_name}` : 'Partner';
    case 'ADMIN':
    case 'SUPER_ADMIN':
    case 'SUPPORT': return h.actor_name ? `BUZZER team • ${h.actor_name}` : 'BUZZER team';
    default: return h.actor_role ?? '';
  }
}

/**
 * Request timeline: newest first, one line per event with an icon, a plain-language title, who did it and when.
 * Automatic steps (dispatch to partners, quotes shared, hand-overs) are marked 🤖 so people see what happened without asking.
 */
export function Timeline({ history, initial = 6 }: { history: HistoryEntry[] | null | undefined; initial?: number }) {
  const [all, setAll] = useState(false);
  const items = [...(history ?? [])].reverse();
  const shown = all ? items : items.slice(0, initial);
  if (!items.length) return <Text style={{ color: colors.textSecondary }}>No activity yet.</Text>;
  return (
    <View>
      {shown.map((h, i) => {
        const statusChanged = h.from_status !== h.to_status;
        const title = statusChanged ? statusLabel(h.to_status) : (h.reason ?? statusLabel(h.to_status));
        const detail = statusChanged ? h.reason : null;
        const last = i === shown.length - 1;
        return (
          <View key={`${h.created_at}-${i}`} style={styles.row}>
            <View style={styles.rail}>
              <View style={[styles.dot, i === 0 && styles.dotLatest, h.actor_role === 'SYSTEM' && styles.dotSystem]}>
                <Text style={{ fontSize: 13 }}>{iconFor(h)}</Text>
              </View>
              {!last ? <View style={styles.line} /> : null}
            </View>
            <View style={[styles.body, i === 0 && styles.bodyLatest]}>
              <Text style={styles.title}>{title}</Text>
              {detail ? <Text style={styles.detail}>{detail}</Text> : null}
              <Text style={styles.meta}>{who(h)} • {ago(h.created_at)} • {dateTime(h.created_at)}</Text>
            </View>
          </View>
        );
      })}
      {items.length > initial ? (
        <Pressable accessibilityRole="button" onPress={() => setAll(!all)} style={{ paddingVertical: 6 }}>
          <Text style={{ color: colors.emerald, fontWeight: '700' }}>{all ? 'Show less' : `Show all ${items.length} events`}</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', gap: space.sm },
  rail: { width: 28, alignItems: 'center' },
  dot: { width: 28, height: 28, borderRadius: 14, backgroundColor: colors.lightGreen, alignItems: 'center', justifyContent: 'center' },
  dotLatest: { backgroundColor: colors.secondary },
  dotSystem: { borderWidth: 1, borderColor: colors.secondary },
  line: { flex: 1, width: 2, backgroundColor: colors.border, marginVertical: 2 },
  body: { flex: 1, paddingBottom: space.md },
  bodyLatest: { backgroundColor: colors.paleMint, borderRadius: radius.md, padding: space.sm, marginBottom: space.sm },
  title: { fontWeight: '700', color: colors.text },
  detail: { color: colors.text, fontSize: font.sm, marginTop: 2 },
  meta: { color: colors.textSecondary, fontSize: font.xs, marginTop: 2 },
});
