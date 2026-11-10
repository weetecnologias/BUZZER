import React, { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import {
  api, Button, Card, Chip, colors, dateTime, EmptyState, ErrorState, font, GradientHeader, KeyValue, Loading, Notice, radius, Row, Screen, space,
  StatusBadge, TextField, useAction, useNav, useOnRefocus, useQuery,
} from '@buzzer/core';

const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
type Day = { on: boolean; start: string; end: string };

const isoDay = (offset: number) => {
  const d = new Date(Date.now() + offset * 86400000);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

/**
 * Working hours (one window per weekday here; the API supports up to three), days off and upcoming jobs. Customers can
 * book you instantly only inside your hours, outside days off and not overlapping another job (with travel time).
 */
export function CalendarScreen() {
  const nav = useNav();
  const q = useQuery(() => api.get<any>('/api/v1/partner/schedule'));
  useOnRefocus(q.refresh);
  const act = useAction();
  const [week, setWeek] = useState<Day[]>(DAYS.map(() => ({ on: false, start: '09:00', end: '19:00' })));
  const [from, setFrom] = useState(isoDay(1));
  const [to, setTo] = useState(isoDay(1));
  const [reason, setReason] = useState('');
  const [saved, setSaved] = useState<string | null>(null);
  const [conflicts, setConflicts] = useState<any[]>([]);
  const s = q.data;

  useEffect(() => {
    if (!s) return;
    const next = DAYS.map(() => ({ on: false, start: '09:00', end: '19:00' }));
    (s.weekly ?? []).forEach((w: any) => {
      const d = next[w.weekday - 1];
      if (!d.on) next[w.weekday - 1] = { on: true, start: w.start_time, end: w.end_time };
    });
    setWeek(next);
  }, [s]);

  const save = async () => {
    setSaved(null);
    const r = await act.run(() => api.put<any>('/api/v1/partner/schedule', {
      weekly: week.map((d, i) => ({ d, i })).filter(({ d }) => d.on).map(({ d, i }) => ({ weekday: i + 1, start_time: d.start, end_time: d.end })),
    }));
    if (r) {
      q.setData(r);
      setSaved('Working hours saved. Customers now see your new availability.');
    }
  };
  const addOff = async () => {
    const r = await act.run(() => api.post<any>('/api/v1/partner/schedule/time-off', { starts_on: from, ends_on: to, reason: reason.trim() || null }));
    if (r) {
      q.setData(r);
      setConflicts(r.conflicts ?? []);
      setReason('');
    }
  };
  const set = (i: number, patch: Partial<Day>) => setWeek((w) => w.map((d, j) => (j === i ? { ...d, ...patch } : d)));
  const fix = (t: string) => t.replace(/[^0-9:]/g, '').slice(0, 5);

  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="My calendar" subtitle={s ? `Times in ${s.time_zone}` : 'Working hours and days off'} onBack={nav.pop} />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        {q.loading ? <Loading /> : q.error || !s ? <ErrorState message={q.error ?? 'Not found'} onRetry={q.reload} /> : (
          <>
            <Card>
              <Text style={styles.title}>Working hours</Text>
              {s.using_defaults ? <Notice tone="info" message="You haven't set hours yet - Monday to Saturday 9 AM to 7 PM is assumed." /> : null}
              {week.map((d, i) => (
                <Row key={DAYS[i]} style={{ paddingVertical: 4 }}>
                  <Pressable accessibilityRole="switch" accessibilityState={{ checked: d.on }} accessibilityLabel={`${DAYS[i]} working`}
                    onPress={() => set(i, { on: !d.on })} style={[styles.day, d.on && styles.dayOn]}>
                    <Text style={[styles.dayText, d.on && { color: colors.white }]}>{DAYS[i]}</Text>
                  </Pressable>
                  {d.on ? (
                    <>
                      <TextInput value={d.start} onChangeText={(t) => set(i, { start: fix(t) })} style={styles.time} accessibilityLabel={`${DAYS[i]} start`} />
                      <Text style={{ color: colors.textSecondary }}>to</Text>
                      <TextInput value={d.end} onChangeText={(t) => set(i, { end: fix(t) })} style={styles.time} accessibilityLabel={`${DAYS[i]} end`} />
                    </>
                  ) : <Text style={{ color: colors.textSecondary }}>Not working</Text>}
                </Row>
              ))}
              <Button title="Save working hours" onPress={save} loading={act.busy} disabled={!week.some((d) => d.on)} />
              {saved ? <Notice tone="success" message={saved} /> : null}
            </Card>

            <Card>
              <Text style={styles.title}>Days off</Text>
              {(s.time_off ?? []).length === 0 ? <Text style={{ color: colors.textSecondary }}>No days off planned.</Text> : (s.time_off ?? []).map((t: any) => (
                <Row key={t.id} style={{ justifyContent: 'space-between', paddingVertical: 4 }}>
                  <Text style={{ color: colors.text, flex: 1 }}>🌴 {t.starts_on === t.ends_on ? t.starts_on : `${t.starts_on} → ${t.ends_on}`}{t.reason ? ` · ${t.reason}` : ''}</Text>
                  <Button title="Remove" small variant="ghost" onPress={async () => { const r = await act.run(() => api.del<any>(`/api/v1/partner/schedule/time-off/${t.id}`)); if (r) q.setData(r); }} />
                </Row>
              ))}
              <Row style={{ flexWrap: 'wrap', marginTop: space.sm }}>
                {[[0, 'Today'], [1, 'Tomorrow'], [2, 'Day after']].map(([o, l]) => (
                  <Chip key={String(l)} label={String(l)} selected={from === isoDay(o as number) && to === isoDay(o as number)} onPress={() => { setFrom(isoDay(o as number)); setTo(isoDay(o as number)); }} />
                ))}
              </Row>
              <Row>
                <View style={{ flex: 1 }}><TextField label="From (YYYY-MM-DD)" value={from} onChangeText={setFrom} /></View>
                <View style={{ flex: 1 }}><TextField label="To" value={to} onChangeText={setTo} /></View>
              </Row>
              <TextField label="Reason (optional)" value={reason} onChangeText={setReason} maxLength={200} />
              <Button title="Add days off" variant="secondary" onPress={addOff} loading={act.busy} />
              {conflicts.length ? (
                <Notice tone="warning" message={`You already have ${conflicts.length} booking(s) on those days (${conflicts.map((c) => c.request_number).join(', ')}). Propose a new time or cancel them from My jobs.`} />
              ) : null}
            </Card>
            {act.error ? <Notice tone="danger" message={act.error} /> : null}

            <Text style={[styles.title, { fontSize: font.lg }]}>Upcoming jobs</Text>
            {(s.upcoming_jobs ?? []).length === 0 ? <EmptyState title="Nothing booked yet" message="Bookings appear here as customers book your free slots." /> : (s.upcoming_jobs ?? []).map((j: any) => (
              <Card key={j.assignment_id} onPress={() => nav.push('JobDetail', { id: j.assignment_id })}>
                <Row style={{ justifyContent: 'space-between' }}>
                  <Text style={{ fontWeight: '800', color: colors.text, flex: 1 }}>{j.service_name ?? 'Job'}</Text>
                  <StatusBadge status={j.assignment_status === 'ACTIVE' ? 'PENDING' : j.status} />
                </Row>
                <KeyValue k="When" v={j.starts_at ? dateTime(j.starts_at) : 'To be agreed'} />
                <KeyValue k="Where" v={j.locality_name ?? '—'} />
              </Card>
            ))}
          </>
        )}
      </Screen>
    </View>
  );
}

const styles = StyleSheet.create({
  title: { fontWeight: '800', color: colors.text, marginBottom: space.sm },
  day: { width: 54, paddingVertical: 8, borderRadius: radius.pill, borderWidth: 1, borderColor: colors.border, alignItems: 'center' },
  dayOn: { backgroundColor: colors.emerald, borderColor: colors.emerald },
  dayText: { fontWeight: '800', color: colors.text },
  time: { width: 70, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, paddingVertical: 6, textAlign: 'center', color: colors.text, backgroundColor: colors.white },
});
