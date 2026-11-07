import React, { useState } from 'react';
import { Text, TextInput, View } from 'react-native';
import {
  api, Badge, Button, callPhone, Card, Chip, colors, dateTime, EmptyState, ErrorState, font, GradientHeader, KeyValue, Loading, Notice, radius, Row,
  Screen, space, StatusBadge, TextField, useAction, useNav, useOnRefocus, useQuery,
} from '@buzzer/core';

export function CustomersScreen() {
  const nav = useNav();
  const [q, setQ] = useState('');
  const list = useQuery(() => api.get<any[]>(`/api/v1/agent/customers${q.trim() ? `?q=${encodeURIComponent(q.trim())}` : ''}`), [q]);
  useOnRefocus(list.refresh);
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="My customers" subtitle="Your dedicated portfolio" />
      <Screen refreshing={list.refreshing} onRefresh={list.refresh}>
        <Button title="+ Register a customer" onPress={() => nav.push('RegisterCustomer')} />
        <TextInput value={q} onChangeText={setQ} placeholder="Search name or phone" placeholderTextColor={colors.textSecondary} accessibilityLabel="Search customers"
          style={{ backgroundColor: colors.white, borderRadius: radius.md, borderWidth: 1, borderColor: colors.border, padding: space.md, fontSize: font.md, color: colors.text }} />
        {list.loading ? <Loading /> : list.error ? <ErrorState message={list.error} onRetry={list.reload} /> : (list.data ?? []).length === 0 ? (
          <EmptyState title="No customers yet" message="Customers allocated to you appear here." />
        ) : (list.data ?? []).map((c) => (
          <Card key={c.id} onPress={() => nav.push('Customer', { id: c.id, name: c.full_name })} accessibilityLabel={c.full_name ?? c.phone}>
            <Row style={{ justifyContent: 'space-between' }}>
              <Text style={{ fontWeight: '700', color: colors.text }}>{c.full_name ?? 'Unnamed customer'}</Text>
              <Badge label={c.link_role} tone={c.link_role === 'PRIMARY' ? 'success' : 'info'} />
            </Row>
            <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{c.phone} • {c.open_requests} open request(s){c.agent_name ? ` • agent ${c.agent_name}` : ''}</Text>
          </Card>
        ))}
      </Screen>
    </View>
  );
}

export function CustomerDetailScreen({ params }: { params: { id: string; name?: string } }) {
  const nav = useNav();
  const q = useQuery(() => api.get<any>(`/api/v1/agent/customers/${params.id}`), [params.id]);
  useOnRefocus(q.refresh);
  const d = q.data;
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title={params.name ?? 'Customer'} onBack={nav.pop} />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : (
          <>
            <Card>
              <KeyValue k="Phone" v={d.profile.phone} />
              <KeyValue k="Language" v={d.profile.preferred_language ?? '—'} />
              <KeyValue k="City" v={d.profile.city_name ?? '—'} />
              <KeyValue k="Customer since" v={dateTime(d.profile.created_at)} />
              <Row style={{ marginTop: space.sm }}>
                <Button title="📞 Call" small variant="secondary" onPress={() => callPhone(d.profile.phone)} />
                <Button title="+ New request" small onPress={() => nav.push('CreateRequest', { customerId: params.id, name: params.name, addresses: d.addresses })} />
              </Row>
            </Card>
            <Text style={{ fontWeight: '700', color: colors.text, fontSize: font.lg }}>Requests</Text>
            {(d.requests ?? []).length === 0 ? <EmptyState title="No requests yet" /> : d.requests.map((r: any) => (
              <Card key={r.id} onPress={() => nav.push('Request', { id: r.id })}>
                <Row style={{ justifyContent: 'space-between' }}>
                  <Text style={{ fontWeight: '700', color: colors.text, flex: 1 }}>{r.service_name || r.subcategory_name || r.category_name}</Text>
                  <StatusBadge status={r.status} />
                </Row>
                <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>#{r.request_number} • {dateTime(r.created_at)}</Text>
              </Card>
            ))}
          </>
        )}
      </Screen>
    </View>
  );
}

export function RegisterCustomerScreen() {
  const nav = useNav();
  const [phone, setPhone] = useState('');
  const [name, setName] = useState('');
  const [language, setLanguage] = useState('');
  const [consent, setConsent] = useState('PHONE_CALL');
  const act = useAction();
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="Register customer" subtitle="You become their primary agent" onBack={nav.pop} />
      <Screen>
        <Card>
          <TextField label="Mobile number *" value={phone} onChangeText={setPhone} keyboardType="phone-pad" />
          <TextField label="Full name *" value={name} onChangeText={setName} />
          <TextField label="Preferred language" value={language} onChangeText={setLanguage} />
          <Text style={{ fontWeight: '600', color: colors.text, marginBottom: 6 }}>How did the customer consent?</Text>
          <Row style={{ flexWrap: 'wrap' }}>
            {['PHONE_CALL', 'WHATSAPP', 'IN_PERSON', 'EMAIL'].map((c) => <Chip key={c} label={c.replace('_', ' ')} selected={consent === c} onPress={() => setConsent(c)} />)}
          </Row>
        </Card>
        <Notice tone="info" message="The customer receives an SMS welcome and can sign in to the BUZZER app with this number." />
        {act.error ? <ErrorState message={act.error} /> : null}
        <Button title="Register customer" loading={act.busy} disabled={!phone || !name.trim()} onPress={async () => {
          const r = await act.run(() => api.post<{ customer_user_id: string }>('/api/v1/agent/customers', { phone, full_name: name, preferred_language: language || null, consent_method: consent }));
          if (r) nav.replace('Customer', { id: r.customer_user_id, name });
        }} />
      </Screen>
    </View>
  );
}
