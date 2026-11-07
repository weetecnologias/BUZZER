import React, { useRef, useState } from 'react';
import { Platform, Pressable, Text, View } from 'react-native';
import * as DocumentPicker from 'expo-document-picker';
import {
  api, Badge, Button, callPhone, Card, colors, ConfirmDialog, dateTime, ErrorState, font, GradientHeader, KeyValue, Label, Loading, money,
  newIdempotencyKey, Notice, radius, Row, Screen, space, StatusBadge, statusLabel, TextField, Timeline, useAction, useNav, useOnRefocus, useQuery,
  AttachmentList, Toggle,
} from '@buzzer/core';

const CLOSED_STATES = ['SETTLED', 'CANCELLED', 'REJECTED', 'EXPIRED', 'REFUNDED'];

/** Photos and PDFs for a request: pick, choose whether quoting partners may see it, upload. */
function Attachments({ requestId, items, requestType, closed, onChange }: {
  requestId: string; items: any[]; requestType: string; closed: boolean; onChange: () => void;
}) {
  const act = useAction();
  const [share, setShare] = useState(requestType === 'PRODUCT_RFQ' || requestType === 'SERVICE_BOOKING');
  const pick = async () => {
    const res = await DocumentPicker.getDocumentAsync({ type: ['image/*', 'application/pdf'], multiple: false, copyToCacheDirectory: true });
    if (res.canceled || !res.assets?.length) return;
    const a = res.assets[0];
    const form = new FormData();
    if (Platform.OS === 'web' && (a as any).file) form.append('file', (a as any).file, a.name);
    else form.append('file', { uri: a.uri, name: a.name, type: a.mimeType ?? 'application/octet-stream' } as any);
    form.append('visibility', share ? 'PARTNERS' : 'PRIVATE');
    if (await act.run(() => api.upload(`/api/v1/customer/requests/${requestId}/attachments`, form))) onChange();
  };
  return (
    <Card>
      <Row style={{ justifyContent: 'space-between', marginBottom: space.sm }}>
        <Text style={{ fontWeight: '700', color: colors.text }}>📎 Photos & documents</Text>
        <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{items.length}/10</Text>
      </Row>
      <AttachmentList items={items} canDelete={(x) => x.uploader_role === 'CUSTOMER' && !closed}
        onDelete={async (x) => { if (await act.run(() => api.del(`/api/v1/customer/requests/${requestId}/attachments/${x.id}`))) onChange(); }} />
      {!closed && items.length < 10 ? (
        <View style={{ marginTop: space.sm }}>
          {requestType === 'PRODUCT_RFQ' || requestType === 'SERVICE_BOOKING' ? (
            <Toggle label="Share with partners who are quoting" value={share} onChange={setShare} />
          ) : null}
          <Button title={act.busy ? 'Uploading…' : '+ Add photo or PDF'} variant="outline" small loading={act.busy} onPress={pick} />
          <Text style={{ color: colors.textSecondary, fontSize: font.xs, marginTop: 4 }}>PNG, JPG, WebP or PDF up to 5 MB. Only you, your agent{share ? ' and quoting partners' : ''} can see it.</Text>
        </View>
      ) : null}
      {act.error ? <ErrorState message={act.error} /> : null}
    </Card>
  );
}

/** Item-by-item quote from a partner (direct "Get quotes" requests). */
function ItemLines({ lines }: { lines: any[] }) {
  return (
    <View style={{ marginVertical: space.sm, borderRadius: radius.md, backgroundColor: colors.paleMint, padding: space.sm }}>
      {lines.map((l) => (
        <Row key={l.key} style={{ justifyContent: 'space-between', alignItems: 'flex-start', paddingVertical: 3 }}>
          <Text style={{ width: 20, color: l.available ? colors.success : colors.danger, fontWeight: '900' }}>{l.available ? '✓' : '✕'}</Text>
          <View style={{ flex: 1 }}>
            <Text style={{ color: l.available ? colors.text : colors.textSecondary, textDecorationLine: l.available ? 'none' : 'line-through' }}>
              {l.quantity} × {l.name}{l.custom ? ' (extra)' : ''}
            </Text>
            {l.note ? <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{l.note}</Text> : null}
          </View>
          <Text style={{ fontWeight: '700', color: l.available ? colors.text : colors.textSecondary }}>
            {l.available ? `${money(l.unit_price)} × ${l.quantity} = ${money(l.line_total)}` : 'Not available'}
          </Text>
        </Row>
      ))}
    </View>
  );
}

type Detail = Record<string, any>;

const CANCELLABLE = ['REQUEST_CREATED', 'AGENT_REVIEW', 'PARTNER_QUOTATION', 'CUSTOMER_APPROVAL', 'PAYMENT_PENDING', 'PAYMENT_CONFIRMED',
  'PARTNER_ASSIGNED', 'PARTNER_CONFIRMED', 'RESCHEDULING_REQUESTED', 'PARTNER_DECLINED'];

export function RequestDetailScreen({ params }: { params: { id: string; justCreated?: string } }) {
  const nav = useNav();
  const q = useQuery(() => api.get<Detail>(`/api/v1/customer/requests/${params.id}`), [params.id]);
  const act = useAction();
  useOnRefocus(q.refresh);
  const [accepting, setAccepting] = useState<{ quote: any; method: 'ONLINE' | 'PAY_ON_DELIVERY' } | null>(null);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [otp, setOtp] = useState<{ purpose: string; otp: string; expires_in_minutes: number } | null>(null);
  const [message, setMessage] = useState('');
  const [rating, setRating] = useState(0);
  const [comment, setComment] = useState('');
  const [revising, setRevising] = useState<any>(null);
  const [revisionNote, setRevisionNote] = useState('');
  const acceptKey = useRef(newIdempotencyKey());

  const d = q.data;
  const reload = () => q.refresh();

  const doAccept = async () => {
    if (!accepting) return;
    const ok = await act.run(() => api.post(`/api/v1/customer/requests/${params.id}/quotations/${accepting.quote.id}/accept`,
      { payment_method: accepting.method }, acceptKey.current));
    setAccepting(null);
    if (ok) {
      acceptKey.current = newIdempotencyKey();
      reload();
    }
  };

  const simulate = async (paymentId: string, outcome: 'SUCCESS' | 'FAILURE') => {
    if (await act.run(() => api.post(`/api/v1/customer/payments/${paymentId}/simulate`, { outcome }))) reload();
  };

  const showOtp = async (purpose: 'START' | 'COMPLETE') => {
    const r = await act.run(() => api.get<{ purpose: string; otp: string; expires_in_minutes: number }>(`/api/v1/customer/requests/${params.id}/service-otp?purpose=${purpose}`));
    if (r) setOtp(r);
  };

  if (q.loading) return <View style={{ flex: 1 }}><GradientHeader title="Request" onBack={nav.pop} /><Loading /></View>;
  if (q.error || !d) return <View style={{ flex: 1 }}><GradientHeader title="Request" onBack={nav.pop} /><Screen><ErrorState message={q.error ?? 'Not found'} onRetry={q.reload} /></Screen></View>;

  const status: string = d.status;
  const quotations: any[] = d.quotations ?? [];
  const shared = quotations.filter((x) => x.status === 'SHARED' && !x.expired);
  const awaitingRevision = quotations.filter((x) => x.status === 'REVISION_REQUESTED');
  const automated = d.handling_mode === 'AUTOMATED' || d.booking_mode === 'DIRECT_QUOTE';
  const cheapest = shared.length ? Math.min(...shared.map((x) => Number(x.total_amount))) : 0;
  const lastPayment = (d.payments ?? [])[0];
  const booking = d.booking;
  const pendingProposal = (d.reschedule_proposals ?? []).find((p: any) => p.status === 'PROPOSED' && p.proposed_role === 'PARTNER');

  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title={d.service_name || d.subcategory_name || d.category_name} subtitle={`#${d.request_number}`} onBack={nav.pop} />
      <Screen refreshing={q.refreshing} onRefresh={reload}>
        {params.justCreated ? <Notice tone="success" message={`Request ${params.justCreated} submitted. ${automated ? 'Verified partners are being asked for quotes.' : 'Your agent has been notified.'}`} /> : null}
        {d.created_via === 'AGENT_APP' && !d.customer_confirmed_at ? (
          <Card style={{ borderColor: colors.warning }}>
            <Text style={{ fontWeight: '700', color: colors.text }}>Your agent created this request for you</Text>
            <Text style={{ color: colors.textSecondary, marginVertical: space.sm }}>Please check the details below and confirm, or send a correction.</Text>
            <TextField placeholder="Correction (optional)" value={message} onChangeText={setMessage} />
            <Button title="Confirm details" onPress={async () => {
              if (await act.run(() => api.post(`/api/v1/customer/requests/${params.id}/confirm`, { correction: message || null }))) {
                setMessage('');
                reload();
              }
            }} loading={act.busy} />
          </Card>
        ) : null}

        <Card>
          <Row style={{ justifyContent: 'space-between' }}>
            <Label>Status</Label>
            <StatusBadge status={status} />
          </Row>
          <KeyValue k="Request ID" v={d.request_number} />
          {d.request_type_label ? <KeyValue k="Type" v={d.request_type_label} /> : null}
          <KeyValue k="Created" v={dateTime(d.created_at)} />
          {d.agent_name ? <KeyValue k="Your agent" v={d.agent_name} /> : null}
          {d.preferred_start ? <KeyValue k="Preferred time" v={dateTime(d.preferred_start)} /> : null}
          {d.address_snapshot ? <KeyValue k="Address" v={`${d.address_snapshot.line1}${d.address_snapshot.locality_name ? ', ' + d.address_snapshot.locality_name : ''}`} /> : null}
        </Card>
        {Array.isArray(d.line_items) && d.line_items.length ? (
          <Card>
            <Row style={{ justifyContent: 'space-between', marginBottom: space.sm }}>
              <Text style={{ fontWeight: '700', color: colors.text }}>Booked items</Text>
              <Badge label={d.booking_mode === 'SELF' ? 'Booked at listed prices' : d.booking_mode === 'DIRECT_QUOTE' ? 'Quotes from partners' : 'Agent arranging'}
                tone={d.booking_mode === 'AGENT_ASSISTED' ? 'info' : 'success'} />
            </Row>
            {d.line_items.map((l: any, i: number) => (
              <KeyValue key={l.key ?? l.service_item_id ?? i} k={`${l.quantity} × ${l.name}${l.custom ? ' (extra)' : ''}`}
                v={l.line_total == null ? 'Quote' : (l.estimated ? 'from ' : 'listed ') + money(l.line_total)} />
            ))}
            {d.estimated_total != null ? <KeyValue k={d.has_quote_items ? 'Estimated total (+ quoted items)' : 'Estimated total'} v={money(d.estimated_total)} /> : null}
          </Card>
        ) : d.booking_mode === 'AGENT_DIRECT' ? (
          <Card><Text style={{ fontWeight: '700', color: colors.text }}>Your message to your agent</Text><Text style={{ color: colors.text, marginTop: 4 }}>{d.description}</Text></Card>
        ) : null}
        {d.advisory_notice ? <Notice tone="warning" message={d.advisory_notice} /> : null}
        {act.error ? <ErrorState message={act.error} /> : null}

        {/* Quotations: compare and decide */}
        {status === 'CUSTOMER_APPROVAL' && shared.length ? (
          <>
            <Text style={{ fontSize: font.lg, fontWeight: '700', color: colors.text }}>Compare quotations</Text>
            {shared.map((x, i) => (
              <Card key={x.id} style={i === 0 ? { borderColor: colors.secondary, borderWidth: 2 } : undefined}>
                <Row style={{ justifyContent: 'space-between' }}>
                  <Text style={{ fontWeight: '800', fontSize: font.lg, color: colors.text, flex: 1 }}>{x.partner_name}</Text>
                  <Text style={{ fontWeight: '900', fontSize: font.xl, color: colors.forest }}>{money(x.total_amount)}</Text>
                </Row>
                <Row style={{ marginVertical: 4, flexWrap: 'wrap' }}>
                  {shared.length > 1 && Number(x.total_amount) === cheapest ? <Badge label="Lowest price" tone="success" /> : null}
                  {Array.isArray(x.line_items) && x.line_items.every((l: any) => l.available) ? <Badge label="All items available" tone="success" /> : null}
                  {x.revision_no > 1 ? <Badge label={`Revised (v${x.revision_no})`} tone="info" /> : null}
                  {Number(x.rating_count) > 0 ? <Badge label={`★ ${x.rating_avg} (${x.rating_count})`} tone="info" /> : <Badge label="New partner" tone="neutral" />}
                </Row>
                {Array.isArray(x.line_items) && x.line_items.length ? (
                  <>
                    <ItemLines lines={x.line_items} />
                    <KeyValue k="Items" v={money(x.materials_amount)} />
                    <KeyValue k="Delivery" v={Number(x.visit_fee) ? money(x.visit_fee) : 'Free'} />
                    {x.estimated_arrival ? <KeyValue k="Delivery by" v={dateTime(x.estimated_arrival)} /> : null}
                    {x.partner_note ? <Text style={{ color: colors.text, marginTop: 4 }}>💬 {x.partner_note}</Text> : null}
                  </>
                ) : (
                  <>
                    <Text style={{ color: colors.text, marginVertical: space.sm }}>{x.scope_of_work}</Text>
                    <KeyValue k="Labour" v={money(x.labour_amount)} />
                    <KeyValue k="Materials" v={money(x.materials_amount)} />
                    <KeyValue k="Visit fee" v={money(x.visit_fee)} />
                    <KeyValue k="Taxes" v={money(x.tax_amount)} />
                  </>
                )}
                {Number(x.discount_amount) > 0 ? <KeyValue k="Discount" v={`- ${money(x.discount_amount)}`} /> : null}
                {x.warranty ? <KeyValue k="Warranty" v={x.warranty} /> : null}
                {x.exclusions ? <KeyValue k="Exclusions" v={x.exclusions} /> : null}
                {x.cancellation_terms ? <KeyValue k="Cancellation" v={x.cancellation_terms} /> : null}
                <KeyValue k="Valid until" v={dateTime(x.valid_until)} />
                {x.agent_note ? <Notice tone="info" message={`Agent note: ${x.agent_note}`} /> : null}
                <View style={{ gap: space.sm, marginTop: space.md }}>
                  <Button title="Accept & pay online" onPress={() => setAccepting({ quote: x, method: 'ONLINE' })} />
                  {x.pay_on_delivery_allowed ? <Button title="Accept & pay on delivery" variant="secondary" onPress={() => setAccepting({ quote: x, method: 'PAY_ON_DELIVERY' })} /> : null}
                  {Array.isArray(x.line_items) && x.line_items.length ? (
                    <Button title="✏️ Ask to revise (price, missing items, timing)" variant="outline" small onPress={() => { setRevisionNote(''); setRevising(x); }} />
                  ) : null}
                  <Row>
                    <Button title="Ask a question" variant="outline" small style={{ flex: 1 }} onPress={async () => {
                      if (await act.run(() => api.post(`/api/v1/customer/requests/${params.id}/quotations/${x.id}/reject`, { reason: 'Please clarify the scope and price', clarification: true }))) reload();
                    }} />
                    <Button title="Reject" variant="ghost" small style={{ flex: 1 }} onPress={async () => {
                      if (await act.run(() => api.post(`/api/v1/customer/requests/${params.id}/quotations/${x.id}/reject`, { reason: 'Not suitable', clarification: false }))) reload();
                    }} />
                  </Row>
                </View>
              </Card>
            ))}
          </>
        ) : ['REQUEST_CREATED', 'AGENT_REVIEW', 'PARTNER_QUOTATION'].includes(status) ? (
          <Notice tone="info" message={d.handling_mode === 'AUTOMATED'
            ? 'Sent to verified partners near you. Each one confirms availability and price for your items — quotes appear here as they arrive, usually within the hour.'
            : d.handover_reason
              ? `${d.agent_name ?? 'Our team'} is now finding the right partner for you (${d.handover_reason.toLowerCase()}).`
              : 'Your agent is contacting verified partners and collecting quotations. You\'ll be notified as soon as they are ready.'} />
        ) : null}
        {awaitingRevision.length && ['PARTNER_QUOTATION', 'CUSTOMER_APPROVAL'].includes(status) ? awaitingRevision.map((x) => (
          <Card key={x.id} style={{ opacity: 0.8 }}>
            <Text style={{ fontWeight: '700', color: colors.text }}>⏳ Waiting for {x.partner_name} to revise</Text>
            <Text style={{ color: colors.textSecondary, fontSize: font.sm }}>You asked: “{x.revision_note}”</Text>
          </Card>
        )) : null}

        {/* Payment */}
        {status === 'PAYMENT_PENDING' && lastPayment ? (
          <Card>
            <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>Payment</Text>
            <KeyValue k="Amount" v={money(lastPayment.amount)} />
            <KeyValue k="Status" v={statusLabel(lastPayment.status)} />
            {lastPayment.failure_reason ? <Notice tone="danger" message={`${lastPayment.failure_reason}. Your request is safe — you can retry.`} /> : null}
            {lastPayment.test_mode ? (
              <>
                <Notice tone="warning" message="TEST MODE: no real payment gateway is connected yet. Use these buttons to simulate the gateway's response." />
                {lastPayment.status === 'PENDING' ? (
                  <Row style={{ marginTop: space.sm }}>
                    <Button title="Simulate success" small style={{ flex: 1 }} onPress={() => simulate(lastPayment.id, 'SUCCESS')} loading={act.busy} />
                    <Button title="Simulate failure" variant="outline" small style={{ flex: 1 }} onPress={() => simulate(lastPayment.id, 'FAILURE')} />
                  </Row>
                ) : null}
              </>
            ) : null}
            {lastPayment.status === 'FAILED' ? (
              <View style={{ gap: space.sm, marginTop: space.sm }}>
                <Button title="Retry payment" onPress={async () => { if (await act.run(() => api.post(`/api/v1/customer/requests/${params.id}/payments/retry`, {}, newIdempotencyKey()))) reload(); }} />
                <Button title="Choose a different quotation" variant="outline" onPress={async () => { if (await act.run(() => api.post(`/api/v1/customer/requests/${params.id}/payments/withdraw`, {}))) reload(); }} />
              </View>
            ) : null}
          </Card>
        ) : null}

        {/* Booking & service verification */}
        {booking ? (
          <Card>
            <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>Booking {booking.booking_number}</Text>
            <KeyValue k="Partner" v={booking.partner_name} />
            {booking.scheduled_at ? <KeyValue k="Scheduled" v={dateTime(booking.scheduled_at)} /> : null}
            <KeyValue k="Booking status" v={statusLabel(booking.status)} />
            {booking.partner_phone && ['CONFIRMED', 'ARRIVED', 'STARTED', 'COMPLETION_PENDING'].includes(booking.status) ? (
              <Button title={`Call ${booking.partner_name}`} variant="secondary" small onPress={() => callPhone(booking.partner_phone)} style={{ marginTop: space.sm }} />
            ) : null}
            {['PARTNER_CONFIRMED', 'PARTNER_ARRIVED'].includes(status) ? (
              <Button title="Show START OTP for partner" style={{ marginTop: space.sm }} onPress={() => showOtp('START')} />
            ) : null}
            {status === 'COMPLETION_PENDING' ? (
              <Button title="Work done? Show COMPLETION OTP" style={{ marginTop: space.sm }} onPress={() => showOtp('COMPLETE')} />
            ) : null}
            {otp ? (
              <View style={{ backgroundColor: colors.lightGreen, borderRadius: radius.lg, padding: space.lg, alignItems: 'center', marginTop: space.md }}>
                <Text style={{ color: colors.forest, fontWeight: '600' }}>{otp.purpose === 'START' ? 'Start' : 'Completion'} OTP — share only with your partner</Text>
                <Text style={{ fontSize: 40, fontWeight: '900', letterSpacing: 10, color: colors.forest }}>{otp.otp}</Text>
                <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>Valid for {otp.expires_in_minutes} minutes, single use</Text>
              </View>
            ) : null}
          </Card>
        ) : null}

        {pendingProposal ? (
          <Card style={{ borderColor: colors.warning }}>
            <Text style={{ fontWeight: '700', color: colors.text }}>Partner proposed a new time</Text>
            <KeyValue k="Original" v={dateTime(pendingProposal.original_time)} />
            <KeyValue k="Proposed" v={dateTime(pendingProposal.proposed_time)} />
            {pendingProposal.reason ? <Text style={{ color: colors.textSecondary }}>{pendingProposal.reason}</Text> : null}
            <Row style={{ marginTop: space.sm }}>
              <Button title="Accept" small style={{ flex: 1 }} onPress={async () => { if (await act.run(() => api.post(`/api/v1/customer/requests/${params.id}/reschedule/${pendingProposal.id}`, { accept: true }))) reload(); }} />
              <Button title="Keep original" variant="outline" small style={{ flex: 1 }} onPress={async () => { if (await act.run(() => api.post(`/api/v1/customer/requests/${params.id}/reschedule/${pendingProposal.id}`, { accept: false }))) reload(); }} />
            </Row>
          </Card>
        ) : null}

        {/* Review */}
        {['COMPLETED', 'SETTLEMENT_PENDING', 'SETTLED'].includes(status) ? (
          d.review ? (
            <Card><Text style={{ fontWeight: '700', color: colors.text }}>Your rating: {'★'.repeat(d.review.rating)}</Text>{d.review.comment ? <Text>{d.review.comment}</Text> : null}</Card>
          ) : (
            <Card>
              <Text style={{ fontWeight: '700', color: colors.text }}>Rate your experience</Text>
              <Row style={{ marginVertical: space.sm }}>
                {[1, 2, 3, 4, 5].map((n) => (
                  <Pressable key={n} onPress={() => setRating(n)} accessibilityLabel={`${n} stars`} hitSlop={6}>
                    <Text style={{ fontSize: 32, color: n <= rating ? '#E0A800' : colors.border }}>★</Text>
                  </Pressable>
                ))}
              </Row>
              <TextField placeholder="Tell us more (optional)" value={comment} onChangeText={setComment} multiline />
              <Button title="Submit review" disabled={rating === 0} loading={act.busy} onPress={async () => {
                if (await act.run(() => api.post(`/api/v1/customer/requests/${params.id}/review`, { rating, comment: comment || null }))) reload();
              }} />
            </Card>
          )
        ) : null}

        {/* Invoices */}
        {(d.invoices ?? []).length ? (
          <Card>
            <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>Invoices & receipts</Text>
            {(d.invoices ?? []).map((inv: any) => <KeyValue key={inv.id} k={`${inv.invoice_type.replace('_', ' ')} ${inv.invoice_number}`} v={money(inv.total_amount)} />)}
          </Card>
        ) : null}

        {/* Messages with agent */}
        <Card>
          <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>Messages with your agent</Text>
          {(d.messages ?? []).length === 0 ? <Text style={{ color: colors.textSecondary }}>No messages yet.</Text> : (d.messages ?? []).map((m: any) => (
            <View key={m.id} style={{ alignSelf: m.sender_role === 'CUSTOMER' ? 'flex-end' : 'flex-start', backgroundColor: m.sender_role === 'CUSTOMER' ? colors.lightGreen : colors.paleMint,
              borderRadius: radius.md, padding: space.sm, marginBottom: 6, maxWidth: '85%' }}>
              <Text style={{ color: colors.text }}>{m.body}</Text>
              <Text style={{ color: colors.textSecondary, fontSize: 11 }}>{m.sender_name} • {dateTime(m.created_at)}</Text>
            </View>
          ))}
          <TextField placeholder="Write a message…" value={message} onChangeText={setMessage} />
          <Button title="Send" small variant="secondary" disabled={!message.trim()} onPress={async () => {
            if (await act.run(() => api.post(`/api/v1/customer/requests/${params.id}/messages`, { body: message.trim() }))) {
              setMessage('');
              reload();
            }
          }} />
        </Card>

        <Attachments requestId={params.id} items={d.attachments ?? []} requestType={d.request_type} closed={CLOSED_STATES.includes(status)} onChange={reload} />

        {d.parent || (d.children ?? []).length ? (
          <Card>
            <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>🔗 Related requests</Text>
            {d.parent ? (
              <Pressable accessibilityRole="button" onPress={() => nav.push('RequestDetail', { id: d.parent.id })} style={{ paddingVertical: 4 }}>
                <Text style={{ color: colors.emerald, fontWeight: '700' }}>↑ Part of #{d.parent.request_number} ({statusLabel(d.parent.status)})</Text>
              </Pressable>
            ) : null}
            {(d.children ?? []).map((ch: any) => (
              <Pressable key={ch.id} accessibilityRole="button" onPress={() => nav.push('RequestDetail', { id: ch.id })} style={{ paddingVertical: 4 }}>
                <Text style={{ color: colors.emerald, fontWeight: '700' }}>↳ #{ch.request_number} • {ch.subcategory_name ?? ch.category_name} • {statusLabel(ch.status)}</Text>
              </Pressable>
            ))}
          </Card>
        ) : null}

        {/* Timeline */}
        <Card>
          <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>Timeline</Text>
          <Timeline history={d.history} />
        </Card>

        {CANCELLABLE.includes(status) ? <Button title="Cancel request" variant="ghost" onPress={() => setCancelOpen(true)} /> : null}
      </Screen>

      <ConfirmDialog visible={!!accepting} title="Confirm quotation" loading={act.busy}
        message={accepting ? `${accepting.quote.partner_name} • ${money(accepting.quote.total_amount)} • ${accepting.method === 'ONLINE' ? 'Pay online now' : 'Pay the partner on delivery'}` : ''}
        confirmLabel="Accept" onConfirm={doAccept} onCancel={() => setAccepting(null)} />
      <ConfirmDialog visible={!!revising} title={`Ask ${revising?.partner_name ?? 'the partner'} to revise`} confirmLabel="Send" loading={act.busy}
        message="They are notified and can send an updated quote. Other quotes stay open."
        onCancel={() => setRevising(null)}
        onConfirm={async () => {
          if (revisionNote.trim().length < 3) return;
          const ok = await act.run(() => api.post(`/api/v1/customer/requests/${params.id}/quotations/${revising.id}/revise`, { note: revisionNote.trim() }));
          setRevising(null);
          if (ok) reload();
        }}>
        <TextField label="What should change?" value={revisionNote} onChangeText={setRevisionNote} multiline
          placeholder="e.g. Please include the ceiling roses / lower the delivery charge / deliver by 6 pm" />
      </ConfirmDialog>
      <ConfirmDialog visible={cancelOpen} title="Cancel this request?" danger confirmLabel="Cancel request" loading={act.busy}
        message="If you have paid, refund eligibility is reviewed according to the cancellation policy." onCancel={() => setCancelOpen(false)}
        onConfirm={async () => {
          const ok = await act.run(() => api.post(`/api/v1/customer/requests/${params.id}/cancel`, { reason }));
          setCancelOpen(false);
          if (ok) reload();
        }}>
        <TextField label="Reason" value={reason} onChangeText={setReason} placeholder="Why are you cancelling?" />
      </ConfirmDialog>
    </View>
  );
}
