import React, { useEffect, useState } from 'react';
import { Linking, Pressable, Text, View } from 'react-native';
import { CurrentLocation, getCurrentLocation } from '../location';
import {
  api, Badge, Button, Card, Chip, colors, ConfirmDialog, EmptyState, ErrorState, font, GradientHeader, Label, Loading, Notice, Row, Screen,
  space, TextField, useAction, useNav, useOnRefocus, useQuery,
} from '@buzzer/core';

export function AddressesScreen() {
  const nav = useNav();
  const q = useQuery(() => api.get<any[]>('/api/v1/customer/addresses'));
  useOnRefocus(q.reload);
  const act = useAction();
  const [deleting, setDeleting] = useState<any>(null);
  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="Saved addresses" onBack={nav.pop} />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}>
        {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : (q.data ?? []).length === 0 ? (
          <EmptyState title="No addresses yet" message="Add Home, Work or another address." />
        ) : (q.data ?? []).map((a) => (
          <Card key={a.id}>
            <Row style={{ justifyContent: 'space-between' }}>
              <Badge label={a.label} tone="success" />
              {a.is_default ? <Badge label="Default" tone="info" /> : null}
            </Row>
            <Text style={{ color: colors.text, marginTop: space.sm, fontWeight: '600' }}>{a.line1}</Text>
            {a.line2 ? <Text style={{ color: colors.text }}>{a.line2}</Text> : null}
            <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{[a.landmark, a.locality_name, a.city_name, a.pincode].filter(Boolean).join(', ')}</Text>
            <Row style={{ marginTop: space.sm }}>
              <Button title="Edit" small variant="outline" onPress={() => nav.push('AddressForm', { address: a })} />
              <Button title="Delete" small variant="ghost" onPress={() => setDeleting(a)} />
            </Row>
          </Card>
        ))}
        <Button title="+ Add address" onPress={() => nav.push('AddressForm', {})} />
      </Screen>
      <ConfirmDialog visible={!!deleting} title="Delete address?" danger confirmLabel="Delete" loading={act.busy}
        message="Past requests keep their copy of this address." onCancel={() => setDeleting(null)}
        onConfirm={async () => {
          await act.run(() => api.del(`/api/v1/customer/addresses/${deleting.id}`));
          setDeleting(null);
          q.reload();
        }} />
    </View>
  );
}

export function AddressFormScreen({ params }: { params: { address?: any } }) {
  const nav = useNav();
  const a = params.address;
  const cities = useQuery(() => api.get<any[]>('/api/v1/locations/cities'));
  const [cityId, setCityId] = useState<string | null>(a?.city_id ?? null);
  const effectiveCity = cityId ?? cities.data?.[0]?.id ?? null;
  const localities = useQuery(() => (effectiveCity ? api.get<any[]>(`/api/v1/locations/cities/${effectiveCity}/localities`) : Promise.resolve([])), [effectiveCity]);
  const [form, setForm] = useState({ label: a?.label ?? 'HOME', line1: a?.line1 ?? '', line2: a?.line2 ?? '', landmark: a?.landmark ?? '',
    pincode: a?.pincode ?? '', locality_id: a?.locality_id ?? null as string | null, is_default: a?.is_default ?? false,
    latitude: a?.latitude ?? null, longitude: a?.longitude ?? null });
  const act = useAction();
  const set = (k: string, v: any) => setForm((f) => ({ ...f, [k]: v }));
  const [locating, setLocating] = useState(false);
  const [located, setLocated] = useState<CurrentLocation | null>(null);
  const [locError, setLocError] = useState<string | null>(null);

  const useCurrentLocation = async () => {
    setLocating(true);
    setLocError(null);
    try {
      const loc = await getCurrentLocation();
      setLocated(loc);
      if (loc.city_id) setCityId(loc.city_id);
      setForm((f) => ({
        ...f,
        latitude: Number(loc.latitude.toFixed(7)),
        longitude: Number(loc.longitude.toFixed(7)),
        locality_id: loc.locality_id ?? f.locality_id,
        pincode: loc.postalCode ?? loc.pincode ?? f.pincode,
        line2: f.line2 || loc.street || "",
        landmark: f.landmark || loc.landmark || "",
      }));
    } catch (e) {
      setLocError(e instanceof Error ? e.message : "Could not get your location");
    } finally {
      setLocating(false);
    }
  };

  // New address: fetch the current location straight away (the user still confirms the house number).
  useEffect(() => {
    if (!a) useCurrentLocation();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const save = async () => {
    const body = { ...form, city_id: effectiveCity };
    const ok = a ? await act.run(() => api.put(`/api/v1/customer/addresses/${a.id}`, body)) : await act.run(() => api.post('/api/v1/customer/addresses', body));
    if (ok) nav.pop();
  };

  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title={a ? 'Edit address' : 'Add address'} onBack={nav.pop} />
      <Screen>
        <Card style={{ borderColor: colors.secondary }}>
          <Button title={locating ? "Finding your location…" : located ? "📍 Update current location" : "📍 Use my current location"}
            onPress={useCurrentLocation} loading={locating} variant={located ? "secondary" : "primary"} />
          {locError ? <View style={{ marginTop: space.sm }}><ErrorState message={locError} /></View> : null}
          {located ? (
            <View style={{ marginTop: space.sm, gap: 4 }}>
              {located.in_service_area ? (
                <Text style={{ color: colors.emerald, fontWeight: "700" }}>
                  ✓ {located.locality_name ? located.locality_name + ", " : ""}{located.city_name}{located.pincode ? " " + (located.postalCode ?? located.pincode) : ""}
                </Text>
              ) : (
                <Notice tone="warning" message={"You appear to be outside our service area" + (located.city_name ? " (nearest city: " + located.city_name + ", " + located.city_distance_km + " km away)" : "") + ". You can still enter an address manually."} />
              )}
              <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>
                Pin {located.latitude.toFixed(5)}, {located.longitude.toFixed(5)}{located.accuracyMeters ? " • ±" + Math.round(located.accuracyMeters) + " m" : ""}
              </Text>
              <Pressable onPress={() => Linking.openURL("https://www.google.com/maps/search/?api=1&query=" + located.latitude + "," + located.longitude)} accessibilityRole="link">
                <Text style={{ color: colors.emerald, fontWeight: "600", fontSize: font.xs }}>Check the pin on Google Maps ↗</Text>
              </Pressable>
              <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>Now add your house / flat number below.</Text>
            </View>
          ) : null}
        </Card>
        <Card>
          <Label>Save as</Label>
          <Row style={{ marginVertical: space.sm }}>
            {['HOME', 'WORK', 'OTHER'].map((l) => <Chip key={l} label={l[0] + l.slice(1).toLowerCase()} selected={form.label === l} onPress={() => set('label', l)} />)}
          </Row>
          <TextField label="House / flat / building *" value={form.line1} onChangeText={(t) => set('line1', t)} />
          <TextField label="Street / area" value={form.line2} onChangeText={(t) => set('line2', t)} />
          <TextField label="Landmark" value={form.landmark} onChangeText={(t) => set('landmark', t)} />
          <TextField label="Pincode *" value={form.pincode} onChangeText={(t) => set('pincode', t.replace(/\D/g, ''))} keyboardType="number-pad" maxLength={6} />
          <Label>City</Label>
          <Row style={{ marginVertical: space.sm, flexWrap: 'wrap' }}>
            {(cities.data ?? []).map((c) => <Chip key={c.id} label={c.name} selected={effectiveCity === c.id} onPress={() => setCityId(c.id)} />)}
          </Row>
          <Label>Locality</Label>
          {localities.loading ? <Loading /> : (
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, marginTop: space.sm }}>
              {(localities.data ?? []).map((l) => (
                <Chip key={l.id} label={l.name} selected={form.locality_id === l.id} onPress={() => { set('locality_id', l.id); if (!form.pincode && l.pincode) set('pincode', l.pincode); }} />
              ))}
            </View>
          )}
          <Button title={form.is_default ? '✓ Default address' : 'Make default'} variant="ghost" small onPress={() => set('is_default', !form.is_default)} />
        </Card>
        {act.error ? <ErrorState message={act.error} /> : null}
        <Button title="Save address" onPress={save} loading={act.busy} />
      </Screen>
    </View>
  );
}
