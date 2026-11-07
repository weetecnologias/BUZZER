import React, { useState } from 'react';
import { Text, View } from 'react-native';
import {
  api, Button, Card, Chip, colors, ErrorState, font, GradientHeader, Label, Loading, Notice, Row, Screen, space, TextField, Toggle,
  useAction, useAuth, useQuery,
} from '@buzzer/core';

/** Partner onboarding: business/individual, services offered (needs admin approval), service area, terms acceptance. */
export function RegistrationScreen() {
  const { refreshSession, signOut, me } = useAuth();
  const cities = useQuery(() => api.get<any[]>('/api/v1/locations/cities'));
  const terms = useQuery(() => api.get<any>('/api/v1/partner/terms'));
  const cats = useQuery(() => api.get<any[]>('/api/v1/catalogue/categories'));
  const [type, setType] = useState<'INDIVIDUAL' | 'BUSINESS'>('INDIVIDUAL');
  const [displayName, setDisplayName] = useState(me?.full_name ?? '');
  const [businessName, setBusinessName] = useState('');
  const [email, setEmail] = useState('');
  const [address, setAddress] = useState('');
  const [cityId, setCityId] = useState<string | null>(null);
  const [catId, setCatId] = useState<string | null>(null);
  const [subIds, setSubIds] = useState<string[]>([]);
  const [subNames, setSubNames] = useState<Record<string, string>>({});
  const [areaIds, setAreaIds] = useState<string[]>([]);
  const [accepted, setAccepted] = useState(false);
  const act = useAction();
  const city = cityId ?? cities.data?.[0]?.id ?? null;
  const subs = useQuery(() => (catId ? api.get<any[]>(`/api/v1/catalogue/categories/${catId}/subcategories`) : Promise.resolve([])), [catId]);
  const localities = useQuery(() => (city ? api.get<any[]>(`/api/v1/locations/cities/${city}/localities`) : Promise.resolve([])), [city]);

  const submit = async () => {
    const ok = await act.run(() => api.post('/api/v1/partner/registration', {
      partner_type: type, display_name: displayName, business_name: type === 'BUSINESS' ? businessName : null, email: email || null,
      city_id: city, address, subcategory_ids: subIds, locality_ids: areaIds, terms_version_id: accepted ? terms.data?.id : null,
    }));
    if (ok) await refreshSession(); // new PARTNER role is picked up from a refreshed token
  };

  if (cities.loading || terms.loading || cats.loading) return <Loading />;
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="Become a BUZZER Partner" subtitle="Registration → KYC → service approval" />
      <Screen>
        <Notice tone="info" message="After you register, our team verifies your KYC and approves each service you offer. You only receive leads for approved services." />
        <Card>
          <Label>You are</Label>
          <Row style={{ marginVertical: space.sm }}>
            <Chip label="Individual professional" selected={type === 'INDIVIDUAL'} onPress={() => setType('INDIVIDUAL')} />
            <Chip label="Registered business" selected={type === 'BUSINESS'} onPress={() => setType('BUSINESS')} />
          </Row>
          <TextField label="Display name *" value={displayName} onChangeText={setDisplayName} />
          {type === 'BUSINESS' ? <TextField label="Registered business name *" value={businessName} onChangeText={setBusinessName} /> : null}
          <TextField label="Email" value={email} onChangeText={setEmail} keyboardType="email-address" autoCapitalize="none" />
          <TextField label="Business / home address" value={address} onChangeText={setAddress} multiline />
          <Label>City</Label>
          <Row style={{ marginVertical: space.sm, flexWrap: 'wrap' }}>
            {(cities.data ?? []).map((c) => <Chip key={c.id} label={c.name} selected={city === c.id} onPress={() => setCityId(c.id)} />)}
          </Row>
        </Card>
        <Card>
          <Text style={{ fontWeight: '700', color: colors.text }}>Services you offer *</Text>
          <Text style={{ color: colors.textSecondary, fontSize: font.xs, marginBottom: space.sm }}>Pick a category, then the exact services. Each needs admin approval.</Text>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
            {(cats.data ?? []).map((c) => <Chip key={c.id} label={c.name} selected={catId === c.id} onPress={() => setCatId(c.id)} />)}
          </View>
          {subs.loading ? null : (
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, marginTop: space.md }}>
              {(subs.data ?? []).map((s) => (
                <Chip key={s.id} label={`${subIds.includes(s.id) ? '✓ ' : ''}${s.name}`} selected={subIds.includes(s.id)} onPress={() => {
                  setSubIds((x) => (x.includes(s.id) ? x.filter((y) => y !== s.id) : [...x, s.id]));
                  setSubNames((n) => ({ ...n, [s.id]: s.name }));
                }} />
              ))}
            </View>
          )}
          {subIds.length ? <Text style={{ color: colors.emerald, marginTop: space.sm, fontWeight: '600' }}>Selected: {subIds.map((i) => subNames[i]).join(', ')}</Text> : null}
        </Card>
        <Card>
          <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>Service areas (leave empty to cover the whole city)</Text>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
            {(localities.data ?? []).map((l) => (
              <Chip key={l.id} label={l.name} selected={areaIds.includes(l.id)} onPress={() => setAreaIds((x) => (x.includes(l.id) ? x.filter((y) => y !== l.id) : [...x, l.id]))} />
            ))}
          </View>
        </Card>
        {terms.data ? (
          <Card>
            <Text style={{ fontWeight: '700', color: colors.text }}>{terms.data.title} (v{terms.data.version})</Text>
            <Text style={{ color: colors.textSecondary, marginVertical: space.sm }}>{terms.data.summary}</Text>
            <Toggle label="I accept the partner terms" value={accepted} onChange={setAccepted} />
          </Card>
        ) : <ErrorState message="Partner terms are not available right now." />}
        {act.error ? <ErrorState message={act.error} /> : null}
        <Button title="Submit registration" onPress={submit} loading={act.busy} disabled={!accepted || !subIds.length || !displayName.trim()} />
        <Button title="Log out" variant="ghost" onPress={signOut} />
      </Screen>
    </View>
  );
}
