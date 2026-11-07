import React, { useState } from 'react';
import { Text, View } from 'react-native';
import {
  api, Button, Card, Chip, colors, ErrorState, font, GradientHeader, KeyValue, Loading, Notice, Row, Screen, ServicePicker, space, StatusBadge,
  TextField, useAction, useAuth, useNav, useQuery,
} from '@buzzer/core';

const DOC_TYPES = ['AADHAAR', 'PAN', 'GSTIN', 'DRIVING_LICENCE', 'TRADE_LICENCE', 'QUALIFICATION'];

export function AccountScreen() {
  const { signOut } = useAuth();
  const nav = useNav();
  const q = useQuery(() => api.get<any>('/api/v1/partner/me'));
  const act = useAction();
  const [docType, setDocType] = useState('AADHAAR');
  const [docNumber, setDocNumber] = useState('');
  const [picked, setPicked] = useState<{ categoryId?: string; subcategoryId?: string; serviceItemId?: string }>({});
  const [msg, setMsg] = useState<string | null>(null);
  const d = q.data;

  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="Account" subtitle="KYC, services and service areas" />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : (
          <>
            <Card>
              <Text style={{ fontWeight: '800', fontSize: font.lg, color: colors.text }}>{d.partner.display_name}</Text>
              {d.partner.business_name ? <Text style={{ color: colors.textSecondary }}>{d.partner.business_name}</Text> : null}
              <KeyValue k="Type" v={d.partner.partner_type} />
              <KeyValue k="City" v={d.partner.city_name} />
              <KeyValue k="Rating" v={Number(d.partner.rating_count) ? `★ ${d.partner.rating_avg} (${d.partner.rating_count})` : 'No ratings yet'} />
              <Row style={{ marginTop: space.sm }}><Text>Registration</Text><StatusBadge status={d.partner.registration_status} /><Text>KYC</Text><StatusBadge status={d.partner.kyc_status} /></Row>
              {d.partner.review_note ? <Notice tone="info" message={`Admin note: ${d.partner.review_note}`} /> : null}
            </Card>

            <Card>
              <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>KYC documents</Text>
              {(d.kyc_documents ?? []).map((k: any) => <KeyValue key={k.id} k={`${k.doc_type} ${k.doc_number_masked ?? ''}`} v={k.status} />)}
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, marginVertical: space.sm }}>
                {DOC_TYPES.map((t) => <Chip key={t} label={t.replace('_', ' ')} selected={docType === t} onPress={() => setDocType(t)} />)}
              </View>
              <TextField label="Document number" value={docNumber} onChangeText={setDocNumber} autoCapitalize="characters"
                hint="Only a masked number is stored. Document image upload is enabled once private storage is configured." />
              <Button title="Submit KYC document" variant="secondary" loading={act.busy} disabled={docNumber.trim().length < 4} onPress={async () => {
                if (await act.run(() => api.post('/api/v1/partner/kyc', { doc_type: docType, doc_number: docNumber }))) {
                  setDocNumber('');
                  setMsg('KYC submitted for verification');
                  q.refresh();
                }
              }} />
            </Card>

            <Card onPress={() => nav.push('PriceList')} accessibilityLabel="Manage services and prices">
              <Row style={{ justifyContent: 'space-between' }}>
                <View style={{ flex: 1 }}>
                  <Text style={{ fontWeight: '800', color: colors.text }}>🏷️ Services & prices</Text>
                  <Text style={{ color: colors.textSecondary }}>Set your price for each service. Approved prices let agents send instant quotes to customers.</Text>
                </View>
                <Text style={{ color: colors.emerald, fontWeight: '800', fontSize: font.lg }}>›</Text>
              </Row>
            </Card>

            <Card>
              <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>Services you offer</Text>
              {(d.capabilities ?? []).map((c: any) => (
                <Row key={c.id} style={{ justifyContent: 'space-between', paddingVertical: 4 }}>
                  <Text style={{ color: colors.text, flex: 1 }}>{c.category_name} › {c.subcategory_name}{c.service_name ? ` › ${c.service_name}` : ''}</Text>
                  <StatusBadge status={c.status} />
                </Row>
              ))}
              <Text style={{ fontWeight: '600', color: colors.text, marginTop: space.md }}>Request an additional service</Text>
              <ServicePicker value={picked} onChange={setPicked} />
              <Button title="Request approval" variant="outline" style={{ marginTop: space.sm }} disabled={!picked.subcategoryId} loading={act.busy} onPress={async () => {
                if (await act.run(() => api.post('/api/v1/partner/capabilities', { subcategory_id: picked.subcategoryId, service_item_id: picked.serviceItemId ?? null }))) {
                  setPicked({});
                  setMsg('Request sent. An admin must approve it before you receive these leads.');
                  q.refresh();
                }
              }} />
            </Card>

            {(d.service_areas ?? []).length ? (
              <Card>
                <Text style={{ fontWeight: '700', color: colors.text }}>Service areas</Text>
                <Text style={{ color: colors.textSecondary }}>{d.service_areas.map((a: any) => a.name).join(', ')}</Text>
              </Card>
            ) : <Notice tone="info" message="You cover the whole city." />}
            {msg ? <Notice tone="success" message={msg} /> : null}
            {act.error ? <ErrorState message={act.error} /> : null}
            <Button title="Log out" variant="ghost" onPress={signOut} />
          </>
        )}
      </Screen>
    </View>
  );
}
