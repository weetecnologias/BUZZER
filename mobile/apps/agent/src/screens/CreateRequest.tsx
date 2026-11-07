import React, { useEffect, useRef, useState } from 'react';
import { Text, View } from 'react-native';
import {
  api, Button, Card, Chip, colors, DynamicForm, ErrorState, FormSchema, GradientHeader, Label, newIdempotencyKey, normaliseAnswers, Notice,
  Row, Screen, ServicePicker, space, TextField, useAction, useNav, validateForm,
} from '@buzzer/core';

/** Agent creates a request on behalf of a customer; consent method + timestamp are recorded and the customer is asked to confirm. */
export function CreateRequestScreen({ params }: { params: { customerId: string; name?: string; addresses: any[] } }) {
  const nav = useNav();
  const [picked, setPicked] = useState<{ categoryId?: string; subcategoryId?: string; serviceItemId?: string; serviceName?: string }>({});
  const [schema, setSchema] = useState<FormSchema>(null);
  const [values, setValues] = useState<Record<string, any>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [addressId, setAddressId] = useState<string | null>(params.addresses?.find((a) => a.is_default)?.id ?? params.addresses?.[0]?.id ?? null);
  const [consent, setConsent] = useState('PHONE_CALL');
  const [priority, setPriority] = useState('NORMAL');
  const act = useAction();
  const key = useRef(newIdempotencyKey()).current;
  const [addr, setAddr] = useState({ line1: '', pincode: '' });

  useEffect(() => {
    setSchema(null);
    setValues({});
    if (picked.serviceItemId) api.get<any>(`/api/v1/catalogue/services/${picked.serviceItemId}`).then((d) => setSchema(d.form_schema)).catch(() => undefined);
  }, [picked.serviceItemId]);

  const submit = async () => {
    const e = validateForm(schema, values);
    setErrors(e);
    if (Object.keys(e).length) return;
    const r = await act.run(() => api.post<{ id: string }>('/api/v1/agent/requests', {
      customer_user_id: params.customerId, service_item_id: picked.serviceItemId, address_id: addressId,
      answers: normaliseAnswers(schema, values), priority, consent_method: consent,
    }, key));
    if (r) nav.replace('Request', { id: r.id });
  };

  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="New request" subtitle={`On behalf of ${params.name ?? 'customer'}`} onBack={nav.pop} />
      <Screen>
        <Card><ServicePicker value={picked} onChange={setPicked} /></Card>
        {schema ? <Card><DynamicForm schema={schema} values={values} errors={{ ...errors, ...act.fieldErrors }} onChange={(k, v) => setValues((s) => ({ ...s, [k]: v }))} /></Card> : null}
        <Card>
          <Label>Address</Label>
          {(params.addresses ?? []).length ? (
            <View style={{ gap: space.sm, marginTop: space.sm }}>
              {params.addresses.map((a) => <Chip key={a.id} label={`${a.label}: ${a.line1}`} selected={addressId === a.id} onPress={() => setAddressId(a.id)} />)}
            </View>
          ) : (
            <>
              <Notice tone="warning" message="This customer has no saved address. Add one now." />
              <TextField label="House / building" value={addr.line1} onChangeText={(t) => setAddr({ ...addr, line1: t })} />
              <TextField label="Pincode" value={addr.pincode} onChangeText={(t) => setAddr({ ...addr, pincode: t })} keyboardType="number-pad" maxLength={6} />
              <Button title="Save address" small variant="secondary" onPress={async () => {
                const cities = await api.get<any[]>('/api/v1/locations/cities');
                const r = await act.run(() => api.post<{ id: string }>(`/api/v1/agent/customers/${params.customerId}/addresses`, { label: 'HOME', line1: addr.line1, pincode: addr.pincode, city_id: cities[0]?.id }));
                if (r) setAddressId(r.id);
              }} />
              {addressId ? <Text style={{ color: colors.emerald }}>Address saved ✓</Text> : null}
            </>
          )}
          <Label>Priority</Label>
          <Row style={{ marginTop: space.sm, flexWrap: 'wrap' }}>{['LOW', 'NORMAL', 'HIGH', 'URGENT'].map((p) => <Chip key={p} label={p} selected={priority === p} onPress={() => setPriority(p)} />)}</Row>
          <Label>Customer consent</Label>
          <Row style={{ marginTop: space.sm, flexWrap: 'wrap' }}>{['PHONE_CALL', 'WHATSAPP', 'IN_PERSON', 'IN_APP_CHAT', 'EMAIL'].map((c) => <Chip key={c} label={c.replace(/_/g, ' ')} selected={consent === c} onPress={() => setConsent(c)} />)}</Row>
        </Card>
        {act.error ? <ErrorState message={act.error} /> : null}
        <Button title="Create request" onPress={submit} loading={act.busy} disabled={!picked.serviceItemId || !addressId} />
      </Screen>
    </View>
  );
}
