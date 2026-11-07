import React, { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import {
  api, Badge, Button, callPhone, openWhatsApp, Card, colors, ConfirmDialog, dateTime, ErrorState, font, GradientHeader, KeyValue, Loading, money, Notice, radius,
  preferenceLines, Row, Screen, space, StatusBadge, statusLabel, TextField, Timeline, useAction, useNav, useOnRefocus, useQuery, AttachmentList,
} from '@buzzer/core';

export function AgentRequestScreen({ params }: { params: { id: string } }) {
  const nav = useNav();
  const q = useQuery(() => api.get<any>(`/api/v1/agent/requests/${params.id}`), [params.id]);
  useOnRefocus(q.refresh);
  const status = q.data?.status as string | undefined;
  const canInvite = !!status && ['AGENT_REVIEW', 'PARTNER_QUOTATION', 'CUSTOMER_APPROVAL', 'PARTNER_DECLINED', 'NO_SHOW'].includes(status);
  const eligible = useQuery(() => (canInvite ? api.get<any[]>(`/api/v1/agent/requests/${params.id}/eligible-partners`) : Promise.resolve([])), [params.id, canInvite]);
  const canInstant = canInvite || status === 'REQUEST_CREATED';
  const instant = useQuery(() => (canInstant ? api.get<any[]>(`/api/v1/agent/requests/${params.id}/instant-quotes`) : Promise.resolve([])), [params.id, canInstant]);
  const [transferOpen, setTransferOpen] = useState(false);
  const [transferTo, setTransferTo] = useState<string | null>(null);
  const colleagues = useQuery(() => (transferOpen ? api.get<any[]>('/api/v1/agent/colleagues') : Promise.resolve([])), [transferOpen]);
  const act = useAction();
  const [selectedPartners, setSelectedPartners] = useState<string[]>([]);
  const [selectedQuotes, setSelectedQuotes] = useState<string[]>([]);
  const [note, setNote] = useState('');
  const [message, setMessage] = useState('');
  const [override, setOverride] = useState<null | 'START' | 'COMPLETE'>(null);
  const [reason, setReason] = useState('');
  const [reassignQuote, setReassignQuote] = useState<string | null>(null);

  const d = q.data;
  const reload = () => {
    q.refresh();
    eligible.refresh();
    instant.refresh();
  };
  const post = async (path: string, body: object = {}) => {
    if (await act.run(() => api.post(`/api/v1/agent/requests/${params.id}/${path}`, body))) reload();
  };

  if (q.loading) return <View style={{ flex: 1 }}><GradientHeader title="Request" onBack={nav.pop} /><Loading /></View>;
  if (!d) return <View style={{ flex: 1 }}><GradientHeader title="Request" onBack={nav.pop} /><Screen><ErrorState message={q.error ?? 'Not found'} onRetry={q.reload} /></Screen></View>;
  const answers = d.answers ?? {};
  const quotes: any[] = d.quotations ?? [];

  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title={d.service_name || d.subcategory_name || d.category_name} subtitle={`#${d.request_number}`} onBack={nav.pop} />
      <Screen refreshing={q.refreshing} onRefresh={reload}>
        {d.handling_mode === 'AUTOMATED' ? (
          <Card style={{ borderLeftWidth: 5, borderLeftColor: colors.secondary, backgroundColor: colors.paleMint }}>
            <Text style={{ fontWeight: '800', color: colors.forest }}>🤖 Running automatically</Text>
            <Text style={{ color: colors.text, marginTop: 4 }}>
              Sent straight to verified partners. Their quotes go directly to {d.customer_name ?? 'the customer'}, who approves and pays. Nothing to do unless you want to step in.
            </Text>
            <Button title="Take over this ticket" small variant="outline" style={{ marginTop: space.sm, alignSelf: 'flex-start' }} loading={act.busy}
              onPress={async () => { if (await act.run(() => api.post(`/api/v1/agent/requests/${params.id}/take-over`, {}))) reload(); }} />
          </Card>
        ) : d.handed_to_agent_at ? (
          <Notice tone="warning" message={`Handed to you automatically: ${d.handover_reason}. Source partners and share quotes.`} />
        ) : null}
        <Card>
          <Row style={{ justifyContent: 'space-between' }}>
            <Text style={{ fontWeight: '800', color: colors.text, fontSize: font.lg }}>{d.customer_name ?? 'Customer'}</Text>
            <StatusBadge status={d.status} />
          </Row>
          <Row style={{ marginVertical: space.sm }}>
            <Button title="📞 Call" small variant="secondary" onPress={() => callPhone(d.customer_phone)} />
            <Button title="💬 WhatsApp" small variant="secondary" onPress={() => openWhatsApp(d.customer_phone)} />
          </Row>
          {d.agent_source === 'ON_DUTY' ? <Notice tone="info" message="On-duty request: this customer is exploring and has no dedicated agent yet. Great service here is the best reason for them to subscribe." /> : null}
          {preferenceLines(d.customer_preferences).map(([k, v]) => <KeyValue key={k} k={k} v={v} />)}
          {d.request_type_label ? <KeyValue k="Type" v={d.request_type_label} /> : null}
          <KeyValue k="Category" v={`${d.category_name}${d.subcategory_name ? ' › ' + d.subcategory_name : ''}`} />
          <KeyValue k="Created" v={`${dateTime(d.created_at)} via ${d.created_via === 'AGENT_APP' ? 'agent' : 'app'}`} />
          <KeyValue k="Priority" v={d.priority} />
          <KeyValue k="First-response SLA" v={d.first_agent_response_at ? `responded ${dateTime(d.first_agent_response_at)}` : `due ${dateTime(d.sla_due_at)}`} />
          {d.address_snapshot ? <KeyValue k="Address" v={[d.address_snapshot.line1, d.address_snapshot.locality_name, d.address_snapshot.pincode].filter(Boolean).join(', ')} /> : null}
          {Object.entries(answers).map(([k, v]) => <KeyValue key={k} k={k.replace(/_/g, ' ')} v={Array.isArray(v) ? v.join(', ') : String(v)} />)}
          {Array.isArray(d.line_items) && d.line_items.length ? (
            <View style={{ marginTop: space.sm, padding: space.sm, borderRadius: 12, backgroundColor: colors.paleMint }}>
              <Text style={{ fontWeight: "700", color: colors.forest, marginBottom: 4 }}>
                {d.booking_mode === "SELF" ? "Customer booked at listed prices — assign a partner at these rates" : d.booking_mode === "AGENT_DIRECT" ? "Written directly to you" : d.booking_mode === "DIRECT_QUOTE" ? "Customer asked partners for quotes" : "Customer asked you to arrange / quote"}
              </Text>
              {d.line_items.map((l: any, i: number) => (
                <KeyValue key={l.key ?? l.service_item_id ?? i} k={`${l.quantity} × ${l.name}${l.custom ? ' (extra, not on menu)' : ''}`}
                  v={l.line_total == null ? "Quote" : (l.estimated ? "from " : "") + money(l.line_total)} />
              ))}
              {d.estimated_total != null ? <KeyValue k="Estimated total" v={money(d.estimated_total) + (d.has_quote_items ? " + quoted items" : "")} /> : null}
            </View>
          ) : null}

          {d.consent_method ? <KeyValue k="Consent" v={`${d.consent_method} • ${dateTime(d.consent_at)}`} /> : null}
        </Card>
        {d.advisory_notice ? <Notice tone="warning" message={d.advisory_notice} /> : null}
        {act.error ? <ErrorState message={act.error} /> : null}

        {status === 'REQUEST_CREATED' ? <Button title="Start review (first response)" onPress={() => post('acknowledge')} loading={act.busy} /> : null}
        {!['SETTLED', 'CANCELLED'].includes(status ?? '') ? (
          <Button title="↪ Transfer to another agent / manager" variant="ghost" small onPress={() => { setTransferTo(null); setReason(''); setTransferOpen(true); }} />
        ) : null}

        {canInstant && (instant.data ?? []).length ? (
          <Card>
            <Text style={{ fontWeight: '700', color: colors.text }}>⚡ Instant quotes from partner price lists</Text>
            <Text style={{ color: colors.textSecondary, fontSize: font.xs, marginBottom: space.sm }}>
              Built from each partner's admin-approved prices for exactly these items. Tap “Create quote” and it appears under Quotations, ready to share — no waiting for the partner.
            </Text>
            {(instant.data ?? []).map((o) => (
              <View key={o.partner_id} style={{ padding: space.sm, borderRadius: radius.md, borderWidth: 1, borderColor: o.complete ? colors.emerald : colors.border, marginBottom: 6,
                backgroundColor: o.complete ? colors.paleMint : colors.white }}>
                <Row style={{ justifyContent: 'space-between' }}>
                  <Text style={{ fontWeight: '700', color: colors.text, flex: 1 }}>{o.partner_name}{Number(o.rating_count) ? `  ★ ${o.rating_avg}` : ''}</Text>
                  <Text style={{ fontWeight: '900', color: o.complete ? colors.forest : colors.textSecondary, fontSize: font.lg }}>{o.complete ? money(o.total) : 'incomplete'}</Text>
                </Row>
                {(o.breakdown ?? []).map((b: any, i: number) => (
                  <Text key={i} style={{ color: colors.textSecondary, fontSize: font.xs }}>{b.quantity} × {b.name} @ {money(b.unit_price)} = {money(b.line_total)}</Text>
                ))}
                {(o.missing ?? []).length ? <Text style={{ color: colors.warning, fontSize: font.xs }}>No price for: {o.missing.map((m: any) => m.name ?? m).join(', ')} — request a normal quotation instead.</Text> : null}
                {o.complete ? (
                  <Button title={o.already_invited ? 'Already contacted' : 'Create quote'} small variant="secondary" style={{ marginTop: 6 }} disabled={!!o.already_invited} loading={act.busy}
                    onPress={() => post('instant-quotes', { partner_ids: [o.partner_id] })} />
                ) : null}
              </View>
            ))}
            {(instant.data ?? []).filter((o) => o.complete && !o.already_invited).length > 1 ? (
              <Button title={`Create all ${(instant.data ?? []).filter((o) => o.complete && !o.already_invited).length} instant quotes`} loading={act.busy}
                onPress={() => post('instant-quotes', { partner_ids: (instant.data ?? []).filter((o) => o.complete && !o.already_invited).map((o) => o.partner_id) })} />
            ) : null}
          </Card>
        ) : null}

        {canInvite ? (
          <Card>
            <Text style={{ fontWeight: '700', color: colors.text }}>Eligible partners</Text>
            <Text style={{ color: colors.textSecondary, fontSize: font.xs, marginBottom: space.sm }}>Only verified partners approved for this exact service and area are listed.</Text>
            {eligible.loading ? <Loading /> : (eligible.data ?? []).length === 0 ? (
              <Notice tone="warning" message="No eligible partner available. Escalate to admin to onboard partners for this service." />
            ) : (eligible.data ?? []).map((p) => {
              const on = selectedPartners.includes(p.id);
              return (
                <Pressable key={p.id} disabled={p.already_invited} onPress={() => setSelectedPartners((s) => (on ? s.filter((x) => x !== p.id) : [...s, p.id]))}
                  style={{ padding: space.sm, borderRadius: radius.md, borderWidth: 1, borderColor: on ? colors.emerald : colors.border, marginBottom: 6,
                    backgroundColor: p.already_invited ? colors.paleMint : on ? colors.lightGreen : colors.white }}>
                  <Row style={{ justifyContent: 'space-between' }}>
                    <Text style={{ fontWeight: '700', color: colors.text }}>{on ? '☑ ' : p.already_invited ? '✓ ' : '☐ '}{p.display_name}</Text>
                    {p.already_invited ? <Badge label="Invited" tone="info" /> : Number(p.rating_count) ? <Badge label={`★ ${p.rating_avg}`} tone="success" /> : <Badge label="New" tone="neutral" />}
                  </Row>
                  <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{p.partner_type} • {p.active_jobs} active jobs{p.service_areas ? ` • ${p.service_areas}` : ' • whole city'}</Text>
                </Pressable>
              );
            })}
            <Button title={`Request quotations from ${selectedPartners.length} partner(s)`} disabled={!selectedPartners.length} loading={act.busy}
              onPress={async () => { await post('invitations', { partner_ids: selectedPartners }); setSelectedPartners([]); }} />
          </Card>
        ) : null}

        {(d.invitations ?? []).length ? (
          <Card>
            <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>Partner outreach</Text>
            {d.invitations.map((i: any) => <KeyValue key={i.id} k={i.partner_name} v={statusLabel(i.status)} />)}
          </Card>
        ) : null}

        {quotes.length ? (
          <Card>
            <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>Quotations — compare</Text>
            {quotes.map((x) => {
              const on = selectedQuotes.includes(x.id);
              const shareable = ['SUBMITTED', 'SHARED'].includes(x.status) && !x.expired;
              return (
                <Pressable key={x.id} disabled={!shareable} onPress={() => setSelectedQuotes((s) => (on ? s.filter((y) => y !== x.id) : [...s, x.id]))}
                  style={{ padding: space.md, borderRadius: radius.md, borderWidth: 1, borderColor: on ? colors.emerald : colors.border, marginBottom: 8, backgroundColor: on ? colors.lightGreen : colors.white }}>
                  <Row style={{ justifyContent: 'space-between' }}>
                    <Text style={{ fontWeight: '700', color: colors.text, flex: 1 }}>{shareable ? (on ? '☑ ' : '☐ ') : ''}{x.partner_name}</Text>
                    <Text style={{ fontWeight: '900', color: colors.forest, fontSize: font.lg }}>{money(x.total_amount)}</Text>
                  </Row>
                  <Text style={{ color: colors.text }}>{x.scope_of_work}</Text>
                  <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>
                    Labour {money(x.labour_amount)} • Materials {money(x.materials_amount)} • Visit {money(x.visit_fee)} • Tax {money(x.tax_amount)}
                    {x.warranty ? ` • Warranty: ${x.warranty}` : ''} • valid till {dateTime(x.valid_until)}
                  </Text>
                  <Row style={{ marginTop: 4 }}><StatusBadge status={x.status} />{x.expired ? <Badge label="Expired" tone="danger" /> : null}{x.pay_on_delivery_allowed ? <Badge label="POD ok" tone="info" /> : null}</Row>
                  {['PARTNER_DECLINED', 'NO_SHOW'].includes(status!) && x.status === 'CLOSED' ? (
                    <Button title="Reassign to this partner" small variant="outline" style={{ marginTop: 6 }} onPress={() => setReassignQuote(x.id)} />
                  ) : null}
                </Pressable>
              );
            })}
            {['PARTNER_QUOTATION', 'CUSTOMER_APPROVAL'].includes(status!) ? (
              <>
                <TextField label="Note for customer (optional)" value={note} onChangeText={setNote} placeholder="e.g. Quick Fix has the best reviews" />
                <Button title={`Share ${selectedQuotes.length} quotation(s) with customer`} disabled={!selectedQuotes.length} loading={act.busy}
                  onPress={async () => { await post('share-quotations', { quotation_ids: selectedQuotes, note: note || null }); setSelectedQuotes([]); }} />
              </>
            ) : null}
          </Card>
        ) : null}

        {(d.assignments ?? []).length ? (
          <Card>
            <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>Assignments (history preserved)</Text>
            {d.assignments.map((a: any) => <KeyValue key={a.id} k={`${a.assignment_number} • ${a.partner_name}`} v={`${a.status}${a.cancel_reason ? ' — ' + a.cancel_reason : ''}`} />)}
          </Card>
        ) : null}

        {status === 'PARTNER_ARRIVED' || status === 'COMPLETION_PENDING' ? (
          <Card>
            <Text style={{ fontWeight: '700', color: colors.text }}>Customer phone unavailable?</Text>
            <Text style={{ color: colors.textSecondary, marginBottom: space.sm }}>Verify with the customer by call, then record a controlled override (fully audited).</Text>
            <Button title={status === 'PARTNER_ARRIVED' ? 'Override START verification' : 'Override COMPLETION verification'} variant="outline"
              onPress={() => setOverride(status === 'PARTNER_ARRIVED' ? 'START' : 'COMPLETE')} />
          </Card>
        ) : null}

        <Card>
          <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>Messages & call log</Text>
          {(d.messages ?? []).map((m: any) => (
            <View key={m.id} style={{ marginBottom: 6, padding: space.sm, borderRadius: radius.md, backgroundColor: m.sender_role === 'CUSTOMER' ? colors.paleMint : colors.lightGreen }}>
              <Text style={{ color: colors.text }}>{m.body}</Text>
              <Text style={{ color: colors.textSecondary, fontSize: 11 }}>{m.sender_role} • {dateTime(m.created_at)}</Text>
            </View>
          ))}
          <TextField placeholder="Message the customer…" value={message} onChangeText={setMessage} />
          <Row>
            <Button title="Send message" small style={{ flex: 1 }} disabled={!message.trim()} onPress={async () => { await post('messages', { body: message.trim() }); setMessage(''); }} />
            <Button title="Log call" small variant="outline" style={{ flex: 1 }} onPress={async () => { await post('call-log', { outcome: 'CONNECTED', note: message || null }); setMessage(''); }} />
          </Row>
        </Card>

        {(d.attachments ?? []).length ? (
          <Card>
            <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>📎 Photos & documents</Text>
            <AttachmentList items={d.attachments} />
          </Card>
        ) : null}
        {d.parent || (d.children ?? []).length ? (
          <Card>
            <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>🔗 Related requests</Text>
            {d.parent ? <Pressable onPress={() => nav.push('Request', { id: d.parent.id })}><Text style={{ color: colors.emerald, fontWeight: '700' }}>↑ Part of #{d.parent.request_number}</Text></Pressable> : null}
            {(d.children ?? []).map((ch: any) => (
              <Pressable key={ch.id} onPress={() => nav.push('Request', { id: ch.id })}>
                <Text style={{ color: colors.emerald, fontWeight: '700', paddingVertical: 2 }}>↳ #{ch.request_number} • {ch.subcategory_name ?? ch.category_name} • {statusLabel(ch.status)}</Text>
              </Pressable>
            ))}
          </Card>
        ) : null}
        <Card>
          <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>Timeline — how this ticket moved</Text>
          <Timeline history={d.history} initial={8} />
        </Card>
      </Screen>

      <ConfirmDialog visible={!!override} title="Record verification override" confirmLabel="Record override" loading={act.busy}
        onCancel={() => setOverride(null)} onConfirm={async () => { await post('verify-override', { purpose: override, reason }); setOverride(null); setReason(''); }}>
        <TextField label="How did the customer confirm?" value={reason} onChangeText={setReason} multiline placeholder="Called customer at 10:42, confirmed partner on site…" />
      </ConfirmDialog>
      <ConfirmDialog visible={!!reassignQuote} title="Reassign request" confirmLabel="Reassign" loading={act.busy}
        message="A new assignment ID is created; the previous assignment stays in history." onCancel={() => setReassignQuote(null)}
        onConfirm={async () => { await post('reassign', { quotation_id: reassignQuote, reason }); setReassignQuote(null); setReason(''); }}>
        <TextField label="Reason" value={reason} onChangeText={setReason} />
      </ConfirmDialog>
      <ConfirmDialog visible={transferOpen} title="Transfer request" confirmLabel="Transfer" loading={act.busy}
        message="The request, its open exceptions and the conversation move to the selected person. The transfer is recorded in the timeline."
        onCancel={() => setTransferOpen(false)}
        onConfirm={async () => {
          if (!transferTo || reason.trim().length < 3) return;
          if (await act.run(() => api.post(`/api/v1/agent/requests/${params.id}/transfer`, { agent_id: transferTo, reason: reason.trim() }))) {
            setTransferOpen(false);
            setReason('');
            nav.pop();
          }
        }}>
        {colleagues.loading ? <Loading /> : (colleagues.data ?? []).filter((c) => c.agent_id !== d.agent_id).map((c) => {
          const on = transferTo === c.agent_id;
          return (
            <Pressable key={c.agent_id} onPress={() => setTransferTo(c.agent_id)} accessibilityLabel={`Transfer to ${c.display_name}`}
              style={{ padding: space.sm, borderRadius: radius.md, borderWidth: 1, borderColor: on ? colors.emerald : colors.border, marginBottom: 6, backgroundColor: on ? colors.lightGreen : colors.white }}>
              <Text style={{ fontWeight: '700', color: colors.text }}>{on ? '● ' : '○ '}{c.display_name}{c.is_manager ? ' (manager)' : ''}</Text>
              <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{c.wing ?? 'General'} • {statusLabel(c.availability ?? 'OFFLINE')} • {c.open_requests} open</Text>
            </Pressable>
          );
        })}
        <TextField label="Reason (required)" value={reason} onChangeText={setReason} placeholder="e.g. Travel specialist, customer prefers Telugu" />
        {transferTo && reason.trim().length < 3 ? <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>Add a short reason to continue.</Text> : null}
      </ConfirmDialog>

    </View>
  );
}
