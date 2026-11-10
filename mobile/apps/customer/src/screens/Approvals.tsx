import React, { useState } from 'react';
import { Text, View } from 'react-native';
import {
  ago, api, Button, Card, Chip, colors, dateTime, EmptyState, ErrorState, font, GradientHeader, KeyValue, Loading, money, Notice, Row, Screen, space,
  StatusBadge, TextField, useAction, useNav, useOnRefocus, useQuery,
} from '@buzzer/core';

const KIND: Record<string, string> = { RFQ: '🧾 Quotation request', PURCHASE: '🛍️ Products from a shop', BOOKING: '🛠️ Service booking' };

/** Home banner: things the customer's agent prepared that need their approval. */
export function ApprovalsBanner() {
  const nav = useNav();
  const q = useQuery(() => api.get<any[]>('/api/v1/customer/drafts?status=SENT'));
  useOnRefocus(q.refresh);
  const n = (q.data ?? []).length;
  if (!n) return null;
  return (
    <Card onPress={() => nav.push('Approvals')} style={{ borderColor: colors.secondary, borderWidth: 2 }} accessibilityLabel="Waiting for your approval">
      <Text style={{ fontWeight: '900', color: colors.forest, fontSize: font.md }}>✋ {n} item{n === 1 ? '' : 's'} waiting for your approval</Text>
      <Text style={{ color: colors.textSecondary }}>Your agent prepared {n === 1 ? (q.data ?? [])[0].title : 'a few things'} - nothing happens until you approve. ›</Text>
    </Card>
  );
}

export function ApprovalsScreen() {
  const nav = useNav();
  const q = useQuery(() => api.get<any[]>('/api/v1/customer/drafts'));
  useOnRefocus(q.refresh);
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="Approvals" subtitle="Prepared for you by your agent" onBack={nav.pop} />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        <Notice tone="info" message="Your agent can prepare quotation requests, shop orders and bookings for you, but only you can approve them and pay." />
        {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : (q.data ?? []).length === 0 ? (
          <EmptyState title="Nothing to approve" message="When your agent prepares something for you, it appears here." />
        ) : (q.data ?? []).map((d) => (
          <Card key={d.id} onPress={() => nav.push('Approval', { id: d.id })} accessibilityLabel={d.title}>
            <Row style={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <View style={{ flex: 1 }}>
                <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{KIND[d.kind]} · from {d.agent_name}</Text>
                <Text style={{ fontWeight: '800', color: colors.text }}>{d.title}</Text>
                <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{ago(d.created_at)}{d.estimated_total != null ? ` · ${money(d.estimated_total)}` : ''}</Text>
              </View>
              <StatusBadge status={d.status === 'SENT' ? 'PENDING' : d.status} />
            </Row>
          </Card>
        ))}
      </Screen>
    </View>
  );
}

export function ApprovalScreen({ params }: { params: { id: string } }) {
  const nav = useNav();
  const q = useQuery(() => api.get<any>(`/api/v1/customer/drafts/${params.id}`), [params.id]);
  const act = useAction();
  const [method, setMethod] = useState<'PAY_ON_DELIVERY' | 'ONLINE'>('PAY_ON_DELIVERY');
  const [note, setNote] = useState('');
  const d = q.data;
  const approve = async () => {
    const r = await act.run(() => api.post<any>(`/api/v1/customer/drafts/${params.id}/approve`, { payment_method: method }));
    if (!r) return;
    if (r.next === 'PRODUCT_CART') nav.replace('ProductCart');
    else if (r.result_request_id) nav.replace('RequestDetail', { id: r.result_request_id, justCreated: r.request_number });
    else q.refresh();
  };
  const reject = async () => {
    if (await act.run(() => api.post(`/api/v1/customer/drafts/${params.id}/reject`, { note: note.trim() || null }))) q.refresh();
  };
  if (q.loading) return <View style={{ flex: 1 }}><GradientHeader title="Approval" onBack={nav.pop} /><Loading /></View>;
  if (q.error || !d) return <View style={{ flex: 1 }}><GradientHeader title="Approval" onBack={nav.pop} /><Screen><ErrorState message={q.error ?? 'Not found'} onRetry={q.reload} /></Screen></View>;
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title={KIND[d.kind]} subtitle={`From ${d.agent_name}`} onBack={nav.pop} />
      <Screen>
        <Card>
          <Row style={{ justifyContent: 'space-between' }}>
            <Text style={{ fontWeight: '800', color: colors.text, flex: 1 }}>{d.title}</Text>
            <StatusBadge status={d.status === 'SENT' ? 'PENDING' : d.status} />
          </Row>
          {d.note ? <Text style={{ color: colors.text, marginTop: space.sm }}>💬 “{d.note}”</Text> : null}
          {(d.preview ?? []).map((l: any, i: number) => (
            <View key={i} style={{ paddingVertical: 6, borderBottomWidth: 1, borderBottomColor: colors.border }}>
              <Row style={{ justifyContent: 'space-between' }}>
                <Text style={{ color: colors.text, flex: 1 }}>{l.label}</Text>
                {l.amount != null ? <Text style={{ fontWeight: '700', color: colors.forest }}>{money(l.amount)}</Text> : null}
              </Row>
              {l.detail ? <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{l.detail}</Text> : null}
            </View>
          ))}
          {d.estimated_total != null ? <KeyValue k={d.kind === 'PURCHASE' ? 'Estimated total (prices checked again at checkout)' : 'Total'} v={money(d.estimated_total)} /> : null}
          <KeyValue k="Valid until" v={dateTime(d.expires_at)} />
        </Card>
        {d.status === 'SENT' ? (
          <>
            {d.kind === 'BOOKING' ? (
              <>
                <Text style={{ fontWeight: '700', color: colors.text }}>Payment</Text>
                <Row>
                  <Chip label="💵 Pay after the service" selected={method === 'PAY_ON_DELIVERY'} onPress={() => setMethod('PAY_ON_DELIVERY')} />
                  <Chip label="💳 Pay online" selected={method === 'ONLINE'} onPress={() => setMethod('ONLINE')} />
                </Row>
              </>
            ) : null}
            <Notice tone="info" message={d.kind === 'RFQ' ? 'Approving sends the request to nearby shops. You compare their quotes and choose.'
              : d.kind === 'PURCHASE' ? 'Approving puts these items in your cart - you choose pickup or delivery and pay at checkout.'
                : 'Approving books the professional for that time. They confirm within the hour.'} />
            {act.error ? <Notice tone="danger" message={act.error} /> : null}
            <Button title="Approve" onPress={approve} loading={act.busy} />
            <TextField label="Not right? Tell your agent why (optional)" value={note} onChangeText={setNote} maxLength={500} />
            <Button title="Say no" variant="outline" onPress={reject} />
          </>
        ) : d.status === 'APPROVED' && d.result_request_id ? (
          <Button title="Open the request" onPress={() => nav.push('RequestDetail', { id: d.result_request_id })} />
        ) : <Notice tone="neutral" message={d.status === 'EXPIRED' ? 'This expired - ask your agent to prepare it again.' : `This was ${d.status.toLowerCase()}.`} />}
      </Screen>
    </View>
  );
}
