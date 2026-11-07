import React, { useState } from 'react';
import { Text, View } from 'react-native';
import {
  api, Button, Card, Chip, colors, ConfirmDialog, dateTime, EmptyState, ErrorState, font, GradientHeader, KeyValue, Loading, money, Notice,
  Row, Screen, space, StatusBadge, TextField, Toggle, useAction, useNav, useOnRefocus, useQuery,
} from '@buzzer/core';

export function LeadsScreen() {
  const nav = useNav();
  const [status, setStatus] = useState<string>('INVITED');
  const q = useQuery(() => api.get<any[]>(`/api/v1/partner/leads${status ? `?status=${status}` : ''}`), [status]);
  useOnRefocus(q.refresh);
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="Leads" subtitle="Requests matching your approved services" />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        <Row style={{ flexWrap: 'wrap' }}>
          {[['INVITED', 'New'], ['QUOTED', 'Quoted'], ['CLOSED', 'Closed'], ['', 'All']].map(([k, l]) => <Chip key={k} label={l} selected={status === k} onPress={() => setStatus(k)} />)}
        </Row>
        {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : (q.data ?? []).length === 0 ? (
          <EmptyState title="No leads here" message="New leads appear when a BUZZER agent invites you to quote." />
        ) : (q.data ?? []).map((l) => (
          <Card key={l.invitation_id} onPress={() => nav.push('LeadDetail', { lead: l })} accessibilityLabel={`Lead ${l.request_number}`}>
            <Row style={{ justifyContent: 'space-between' }}>
              <Text style={{ fontWeight: '700', color: colors.text, flex: 1 }}>{l.service_name || l.subcategory_name || l.category_name}</Text>
              <StatusBadge status={l.status} />
            </Row>
            <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>#{l.request_number} • {l.locality_name ?? 'Location shared after assignment'} • {dateTime(l.created_at)}</Text>
            {l.quoted_amount ? <Text style={{ color: colors.emerald, marginTop: 4, fontWeight: '600' }}>Your quote: {money(l.quoted_amount)}</Text> : null}
          </Card>
        ))}
      </Screen>
    </View>
  );
}

export function LeadDetailScreen({ params }: { params: { lead: any } }) {
  const nav = useNav();
  const l = params.lead;
  const answers = l.answers ? JSON.parse(l.answers) : {};
  const lines: any[] = l.line_items ? JSON.parse(l.line_items) : [];
  const listed = lines.length && lines.every((x) => x.line_total != null) ? String(lines.reduce((t, x) => t + Number(x.line_total), 0)) : '';
  const [f, setF] = useState({ scope_of_work: lines.map((x) => `${x.quantity} x ${x.name}`).join(', '), labour_amount: listed, materials_amount: '', visit_fee: '', tax_amount: '', discount_amount: '',
    warranty: '', exclusions: '', cancellation_terms: '', pay_on_delivery_allowed: false, valid_hours: '48' });
  const [declineOpen, setDeclineOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [done, setDone] = useState<string | null>(null);
  const act = useAction();
  const n = (v: string) => (v.trim() === '' ? 0 : Number(v));
  const total = n(f.labour_amount) + n(f.materials_amount) + n(f.visit_fee) + n(f.tax_amount) - n(f.discount_amount);

  const submit = async () => {
    const validUntil = new Date(Date.now() + Math.max(1, n(f.valid_hours)) * 3600_000).toISOString();
    const r = await act.run(() => api.post<{ quotation_number: string; total_amount: number }>(`/api/v1/partner/leads/${l.invitation_id}/quote`, {
      scope_of_work: f.scope_of_work, labour_amount: n(f.labour_amount), materials_amount: n(f.materials_amount), visit_fee: n(f.visit_fee),
      tax_amount: n(f.tax_amount), discount_amount: n(f.discount_amount), warranty: f.warranty || null, exclusions: f.exclusions || null,
      cancellation_terms: f.cancellation_terms || null, pay_on_delivery_allowed: f.pay_on_delivery_allowed, valid_until: validUntil,
    }));
    if (r) setDone(`Quotation ${r.quotation_number} sent for ${money(r.total_amount)}. The agent will share it with the customer.`);
  };

  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title={l.service_name || l.subcategory_name || l.category_name} subtitle={`#${l.request_number}`} onBack={nav.pop} />
      <Screen>
        <Card>
          <KeyValue k="Category" v={`${l.category_name}${l.subcategory_name ? ' › ' + l.subcategory_name : ''}`} />
          <KeyValue k="Area" v={l.locality_name ?? '—'} />
          <KeyValue k="Preferred time" v={dateTime(l.preferred_start)} />
          <KeyValue k="Priority" v={l.priority} />
          {Object.entries(answers).map(([k, v]) => <KeyValue key={k} k={k.replace(/_/g, ' ')} v={Array.isArray(v) ? v.join(', ') : String(v)} />)}
          {Array.isArray(lines) && lines.length ? (
            <View style={{ marginTop: space.sm, padding: space.sm, borderRadius: 12, backgroundColor: colors.paleMint }}>
              <Text style={{ fontWeight: "700", color: colors.forest, marginBottom: 4 }}>
                {l.booking_mode === "SELF" ? "Customer booked at these listed prices" : "Requested items"}
              </Text>
              {lines.map((l: any) => (
                <KeyValue key={l.service_item_id} k={`${l.quantity} × ${l.name}`} v={l.line_total == null ? "Quote" : (l.estimated ? "from " : "") + money(l.line_total)} />
              ))}
              {l.estimated_total != null ? <KeyValue k="Listed total" v={money(l.estimated_total)} /> : null}
            </View>
          ) : null}

          {l.description ? <Text style={{ marginTop: space.sm, color: colors.text }}>{l.description}</Text> : null}
          <Notice tone="neutral" message="Customer contact and full address are shared only after you are assigned and confirm the booking." />
        </Card>
        {done ? <Notice tone="success" message={done} /> : l.status !== 'INVITED' ? (
          <Notice tone="info" message={`This lead is ${l.status.toLowerCase()}.`} />
        ) : (
          <>
            <Card>
              <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>Your quotation</Text>
              <TextField label="Scope of work *" value={f.scope_of_work} onChangeText={(t) => setF({ ...f, scope_of_work: t })} multiline />
              <Row>
                <View style={{ flex: 1 }}><TextField label="Labour ₹" keyboardType="numeric" value={f.labour_amount} onChangeText={(t) => setF({ ...f, labour_amount: t })} /></View>
                <View style={{ flex: 1 }}><TextField label="Materials ₹" keyboardType="numeric" value={f.materials_amount} onChangeText={(t) => setF({ ...f, materials_amount: t })} /></View>
              </Row>
              <Row>
                <View style={{ flex: 1 }}><TextField label="Visit fee ₹" keyboardType="numeric" value={f.visit_fee} onChangeText={(t) => setF({ ...f, visit_fee: t })} /></View>
                <View style={{ flex: 1 }}><TextField label="Taxes ₹" keyboardType="numeric" value={f.tax_amount} onChangeText={(t) => setF({ ...f, tax_amount: t })} /></View>
              </Row>
              <Row>
                <View style={{ flex: 1 }}><TextField label="Discount ₹" keyboardType="numeric" value={f.discount_amount} onChangeText={(t) => setF({ ...f, discount_amount: t })} /></View>
                <View style={{ flex: 1 }}><TextField label="Valid for (hours)" keyboardType="numeric" value={f.valid_hours} onChangeText={(t) => setF({ ...f, valid_hours: t })} /></View>
              </Row>
              <TextField label="Warranty" value={f.warranty} onChangeText={(t) => setF({ ...f, warranty: t })} />
              <TextField label="Exclusions" value={f.exclusions} onChangeText={(t) => setF({ ...f, exclusions: t })} />
              <TextField label="Cancellation terms" value={f.cancellation_terms} onChangeText={(t) => setF({ ...f, cancellation_terms: t })} />
              <Toggle label="Allow pay on delivery" value={f.pay_on_delivery_allowed} onChange={(v) => setF({ ...f, pay_on_delivery_allowed: v })} />
              <Row style={{ justifyContent: 'space-between', marginTop: space.sm }}>
                <Text style={{ color: colors.textSecondary }}>Total (calculated by BUZZER)</Text>
                <Text style={{ fontSize: font.xl, fontWeight: '900', color: colors.forest }}>{money(total)}</Text>
              </Row>
            </Card>
            {act.error ? <ErrorState message={act.error} /> : null}
            <Button title="Send quotation" onPress={submit} loading={act.busy} disabled={f.scope_of_work.trim().length < 5 || total <= 0} />
            <Button title="Decline this lead" variant="ghost" onPress={() => setDeclineOpen(true)} />
          </>
        )}
      </Screen>
      <ConfirmDialog visible={declineOpen} title="Decline lead?" danger confirmLabel="Decline" loading={act.busy} onCancel={() => setDeclineOpen(false)}
        onConfirm={async () => {
          const ok = await act.run(() => api.post(`/api/v1/partner/leads/${l.invitation_id}/decline`, { reason }));
          setDeclineOpen(false);
          if (ok) nav.pop();
        }}>
        <TextField label="Reason (optional)" value={reason} onChangeText={setReason} />
      </ConfirmDialog>
    </View>
  );
}
