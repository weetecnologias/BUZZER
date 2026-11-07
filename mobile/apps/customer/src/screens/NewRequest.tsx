import React, { useMemo, useRef, useState } from 'react';
import { Text, View } from 'react-native';
import {
  api, Button, Card, Chip, colors, DynamicForm, ErrorState, FormSchema, font, GradientHeader, Label, Loading, newIdempotencyKey,
  normaliseAnswers, Notice, Screen, space, TextField, useAction, useNav, useOnRefocus, useQuery, validateForm,
} from '@buzzer/core';

type Detail = { id: string; name: string; category_name: string; subcategory_name: string; advisory_notice: string | null; form_schema: FormSchema };

export function NewRequestScreen({ params }: { params: { serviceItemId?: string; categoryId?: string; title: string } }) {
  const nav = useNav();
  const detail = useQuery<Detail | { form_schema: FormSchema; advisory_notice?: string | null }>(
    () => params.serviceItemId
      ? api.get<Detail>(`/api/v1/catalogue/services/${params.serviceItemId}`)
      : api.get<{ form_schema: FormSchema }>(`/api/v1/catalogue/categories/${params.categoryId}/form`),
    [params.serviceItemId, params.categoryId]);
  const addresses = useQuery(() => api.get<any[]>('/api/v1/customer/addresses'));
  useOnRefocus(addresses.reload); // pick up an address added on the next screen
  const [values, setValues] = useState<Record<string, any>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [addressId, setAddressId] = useState<string | null>(null);
  const [when, setWhen] = useState('');
  const [priority, setPriority] = useState('NORMAL');
  const action = useAction();
  // One idempotency key per form instance: a double-tap or retry never creates a duplicate request.
  const idemKey = useRef(newIdempotencyKey()).current;

  const schema = detail.data?.form_schema ?? null;
  const selectedAddress = useMemo(() => addressId ?? (addresses.data ?? []).find((a) => a.is_default)?.id ?? null, [addressId, addresses.data]);

  const submit = async () => {
    const e = validateForm(schema, values);
    if (!selectedAddress) e.__address = 'Add an address so the partner knows where to come';
    let preferredStart: string | undefined;
    if (when.trim()) {
      const d = new Date(when.trim().replace(' ', 'T'));
      if (Number.isNaN(d.getTime())) e.__when = 'Use format YYYY-MM-DD HH:mm';
      else preferredStart = d.toISOString();
    }
    setErrors(e);
    if (Object.keys(e).length) return;
    const res = await action.run(() => api.post<{ id: string; request_number: string }>('/api/v1/customer/requests', {
      service_item_id: params.serviceItemId, category_id: params.serviceItemId ? undefined : params.categoryId,
      address_id: selectedAddress, answers: normaliseAnswers(schema, values), preferred_start: preferredStart, priority,
    }, idemKey));
    if (res) nav.replace('RequestDetail', { id: res.id, justCreated: res.request_number });
    else setErrors((prev) => ({ ...prev, ...action.fieldErrors }));
  };

  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title={params.title} subtitle={(detail.data as Detail)?.subcategory_name ? `${(detail.data as Detail).category_name} › ${(detail.data as Detail).subcategory_name}` : 'Tell us what you need'} onBack={nav.pop} />
      <Screen>
        {detail.loading ? <Loading /> : detail.error ? <ErrorState message={detail.error} onRetry={detail.reload} /> : (
          <>
            {detail.data?.advisory_notice ? <Notice tone="warning" message={detail.data.advisory_notice} /> : null}
            <Card>
              <DynamicForm schema={schema} values={values} errors={{ ...errors, ...action.fieldErrors }}
                onChange={(k, v) => setValues((s) => ({ ...s, [k]: v }))} />
            </Card>
            <Card>
              <Label>Service address</Label>
              {addresses.loading ? <Loading /> : (
                <View style={{ gap: space.sm, marginTop: space.sm }}>
                  {(addresses.data ?? []).map((a) => (
                    <Chip key={a.id} label={`${a.label}: ${a.line1}${a.locality_name ? ', ' + a.locality_name : ''}`} selected={selectedAddress === a.id} onPress={() => setAddressId(a.id)} />
                  ))}
                  <Button title="+ Add new address" variant="secondary" small onPress={() => nav.push('AddressForm', {})} />
                  {errors.__address ? <Text style={{ color: colors.danger, fontSize: font.xs }}>{errors.__address}</Text> : null}
                </View>
              )}
            </Card>
            <Card>
              <TextField label="Preferred date & time (optional)" placeholder="2026-11-12 10:30" value={when} onChangeText={setWhen} error={errors.__when}
                hint="Your agent confirms the final slot with the partner." />
              <Label>Priority</Label>
              <View style={{ flexDirection: 'row', gap: space.sm, marginTop: space.sm, flexWrap: 'wrap' }}>
                {['NORMAL', 'HIGH', 'URGENT'].map((p) => <Chip key={p} label={p === 'NORMAL' ? 'Normal' : p === 'HIGH' ? 'Soon' : 'Urgent'} selected={priority === p} onPress={() => setPriority(p)} />)}
              </View>
            </Card>
            {action.error ? <ErrorState message={action.error} /> : null}
            <Button title="Submit request" onPress={submit} loading={action.busy} />
            <Text style={{ color: colors.textSecondary, fontSize: font.xs, textAlign: 'center' }}>
              You'll get a Request ID immediately. Your agent will collect quotations from verified partners.
            </Text>
          </>
        )}
      </Screen>
    </View>
  );
}
