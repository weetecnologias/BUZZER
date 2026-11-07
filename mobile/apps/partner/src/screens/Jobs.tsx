import React, { useState } from 'react';
import { Text, View } from 'react-native';
import {
  api, Button, callPhone, Card, Chip, colors, ConfirmDialog, dateTime, EmptyState, ErrorState, font, GradientHeader, KeyValue, Loading, money, Notice,
  Row, Screen, space, StatusBadge, statusLabel, TextField, useAction, useNav, useOnRefocus, useQuery,
} from '@buzzer/core';

export function JobsScreen() {
  const nav = useNav();
  const [activeOnly, setActiveOnly] = useState(true);
  const q = useQuery(() => api.get<any[]>(`/api/v1/partner/assignments?activeOnly=${activeOnly}`), [activeOnly]);
  useOnRefocus(q.refresh);
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="My jobs" subtitle="Assigned bookings" />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        <Row>
          <Chip label="Active" selected={activeOnly} onPress={() => setActiveOnly(true)} />
          <Chip label="All" selected={!activeOnly} onPress={() => setActiveOnly(false)} />
        </Row>
        {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : (q.data ?? []).length === 0 ? (
          <EmptyState title="No jobs yet" message="When a customer accepts your quotation, the job appears here." />
        ) : (q.data ?? []).map((j) => (
          <Card key={j.id} onPress={() => nav.push('JobDetail', { id: j.id })} accessibilityLabel={`Job ${j.assignment_number}`}>
            <Row style={{ justifyContent: 'space-between' }}>
              <Text style={{ fontWeight: '700', color: colors.text, flex: 1 }}>{j.service_name || j.category_name}</Text>
              <StatusBadge status={j.request_status} />
            </Row>
            <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>#{j.request_number} • {j.assignment_number}</Text>
            {j.scheduled_at ? <Text style={{ color: colors.emerald, marginTop: 4 }}>📅 {dateTime(j.scheduled_at)}</Text> : null}
          </Card>
        ))}
      </Screen>
    </View>
  );
}

export function JobDetailScreen({ params }: { params: { id: string } }) {
  const nav = useNav();
  const q = useQuery(async () => {
    const all = await api.get<any[]>('/api/v1/partner/assignments');
    return all.find((x) => x.id === params.id) ?? null;
  }, [params.id]);
  const act = useAction();
  const [otp, setOtp] = useState('');
  const [dialog, setDialog] = useState<null | 'decline' | 'cancel' | 'reschedule'>(null);
  const [reason, setReason] = useState('');
  const [newTime, setNewTime] = useState('');
  const j = q.data;

  const call = async (path: string, body: object = {}) => {
    if (await act.run(() => api.post(`/api/v1/partner/assignments/${params.id}/${path}`, body))) {
      setOtp('');
      q.refresh();
    }
  };

  if (q.loading) return <View style={{ flex: 1 }}><GradientHeader title="Job" onBack={nav.pop} /><Loading /></View>;
  if (!j) return <View style={{ flex: 1 }}><GradientHeader title="Job" onBack={nav.pop} /><Screen><ErrorState message={q.error ?? 'Job not found'} onRetry={q.reload} /></Screen></View>;
  const price = j.price_snapshot ? JSON.parse(j.price_snapshot) : {};
  const addr = j.address ? JSON.parse(j.address) : null;
  const rs: string = j.request_status;

  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title={j.service_name || j.category_name} subtitle={`#${j.request_number}`} onBack={nav.pop} />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        <Card>
          <Row style={{ justifyContent: 'space-between' }}><Text style={{ fontWeight: '700', color: colors.text }}>{j.assignment_number}</Text><StatusBadge status={rs} /></Row>
          <KeyValue k="Agreed price" v={money(price.total)} />
          {price.scope ? <KeyValue k="Scope" v={price.scope} /> : null}
          {j.booking_number ? <KeyValue k="Booking" v={j.booking_number} /> : null}
          {j.scheduled_at ? <KeyValue k="Scheduled" v={dateTime(j.scheduled_at)} /> : null}
        </Card>
        {j.customer_name || j.customer_phone ? (
          <Card>
            <Text style={{ fontWeight: '700', color: colors.text }}>Customer</Text>
            <KeyValue k="Name" v={j.customer_name ?? '—'} />
            {addr ? <KeyValue k="Address" v={[addr.line1, addr.line2, addr.landmark, addr.locality_name, addr.pincode].filter(Boolean).join(', ')} /> : null}
            {j.customer_phone ? <Button title="Call customer" variant="secondary" small onPress={() => callPhone(j.customer_phone)} /> : null}
          </Card>
        ) : null}
        {act.error ? <ErrorState message={act.error} /> : null}

        {j.status === 'ACTIVE' ? (
          <Card>
            <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>Your quotation was accepted</Text>
            <Button title="Confirm booking" onPress={() => call('confirm')} loading={act.busy} />
            <Button title="Decline" variant="ghost" onPress={() => setDialog('decline')} />
          </Card>
        ) : null}
        {rs === 'PARTNER_CONFIRMED' ? (
          <Card>
            <Button title="I have reached the location" onPress={() => call('arrived')} loading={act.busy} />
            <Button title="Propose a new time" variant="outline" onPress={() => setDialog('reschedule')} style={{ marginTop: space.sm }} />
            <Button title="Cancel booking" variant="ghost" onPress={() => setDialog('cancel')} />
          </Card>
        ) : null}
        {rs === 'RESCHEDULING_REQUESTED' ? <Notice tone="warning" message="A reschedule is pending the other party's response." /> : null}
        {rs === 'PARTNER_ARRIVED' ? (
          <Card>
            <Text style={{ fontWeight: '700', color: colors.text }}>Start the job</Text>
            <Text style={{ color: colors.textSecondary, marginBottom: space.sm }}>Ask the customer for the 4-digit START OTP shown in their app.</Text>
            <TextField label="Start OTP" value={otp} onChangeText={setOtp} keyboardType="number-pad" maxLength={4} style={{ letterSpacing: 8, fontSize: 22, textAlign: 'center' }} />
            <Button title="Verify & start" onPress={() => call('start', { otp })} loading={act.busy} disabled={otp.length !== 4} />
          </Card>
        ) : null}
        {['SERVICE_STARTED', 'IN_PROGRESS'].includes(rs) ? (
          <Card><Button title="Work finished — request completion" onPress={() => call('request-completion')} loading={act.busy} /></Card>
        ) : null}
        {rs === 'COMPLETION_PENDING' ? (
          <Card>
            <Text style={{ fontWeight: '700', color: colors.text }}>Complete the job</Text>
            <Text style={{ color: colors.textSecondary, marginBottom: space.sm }}>Ask the customer to check the work and share the COMPLETION OTP.</Text>
            <TextField label="Completion OTP" value={otp} onChangeText={setOtp} keyboardType="number-pad" maxLength={4} style={{ letterSpacing: 8, fontSize: 22, textAlign: 'center' }} />
            <Button title="Verify & complete" onPress={() => call('complete', { otp })} loading={act.busy} disabled={otp.length !== 4} />
          </Card>
        ) : null}
        {['COMPLETED', 'SETTLEMENT_PENDING', 'SETTLED'].includes(rs) ? <Notice tone="success" message={`${statusLabel(rs)}. Your earning is recorded in the Earnings tab.`} /> : null}
      </Screen>

      <ConfirmDialog visible={dialog === 'decline' || dialog === 'cancel'} danger title={dialog === 'decline' ? 'Decline assignment?' : 'Cancel booking?'}
        message={dialog === 'cancel' ? 'Cancellations are monitored. Repeated cancellations trigger a review.' : 'The agent will reassign this request.'}
        confirmLabel={dialog === 'decline' ? 'Decline' : 'Cancel booking'} loading={act.busy} onCancel={() => setDialog(null)}
        onConfirm={async () => {
          await call(dialog === 'decline' ? 'decline' : 'cancel', { reason });
          setDialog(null);
        }}>
        <TextField label="Reason (required)" value={reason} onChangeText={setReason} />
      </ConfirmDialog>
      <ConfirmDialog visible={dialog === 'reschedule'} title="Propose a new time" confirmLabel="Send proposal" loading={act.busy} onCancel={() => setDialog(null)}
        onConfirm={async () => {
          const d = new Date(newTime.replace(' ', 'T'));
          if (Number.isNaN(d.getTime())) {
            act.setError('Use format YYYY-MM-DD HH:mm');
            setDialog(null);
            return;
          }
          if (await act.run(() => api.post(`/api/v1/partner/requests/${j.request_id}/reschedule`, { proposed_time: d.toISOString(), reason }))) q.refresh();
          setDialog(null);
        }}>
        <TextField label="New date & time" placeholder="2026-11-12 16:00" value={newTime} onChangeText={setNewTime} />
        <TextField label="Reason" value={reason} onChangeText={setReason} />
      </ConfirmDialog>
    </View>
  );
}
