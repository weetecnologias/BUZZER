import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import {
  api, Badge, Button, Card, Chip, colors, DynamicForm, EmptyState, ErrorState, FormSchema, font, GradientHeader, Loading, money, newIdempotencyKey,
  normaliseAnswers, Notice, radius, Row, Screen, space, useAction, useNav, useOnRefocus, useQuery, validateForm,
} from '@buzzer/core';

type Slot = { start: string; end: string; label: string; providers: number; from_price: number };
type Day = { date: string; label: string; slots: Slot[] };
type Provider = {
  id: string; display_name: string; business_name: string | null; rating_avg: number; rating_count: number; jobs_completed: number; distance_km: number | null;
  unit_price: number; total: number; price_source: 'PROVIDER_PRICE' | 'LISTED_PRICE'; service_areas: string | null;
};

const time12 = (iso: string) => new Date(iso).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });

/**
 * Instant booking: pick the address, a day and a time, then a verified professional (rating, jobs done, distance, price).
 * The professional accepts within the hour; nothing is charged online until you choose to pay online.
 */
export function BookServiceScreen({ params }: { params: { serviceItemId: string; title: string } }) {
  const nav = useNav();
  const detail = useQuery(() => api.get<any>(`/api/v1/catalogue/services/${params.serviceItemId}`), [params.serviceItemId]);
  const addresses = useQuery(() => api.get<any[]>('/api/v1/customer/addresses'));
  useOnRefocus(addresses.reload);
  const [addressId, setAddressId] = useState<string | null>(null);
  const address = addressId ?? (addresses.data ?? []).find((a) => a.is_default)?.id ?? (addresses.data ?? [])[0]?.id ?? null;
  const slots = useQuery(() => (address ? api.get<{ days: Day[]; matching_providers: number; time_zone: string }>(
    `/api/v1/customer/services/${params.serviceItemId}/slots?addressId=${address}`) : Promise.resolve(null)), [address]);
  const days = (slots.data?.days ?? []).filter((d) => d.slots.length);
  const [day, setDay] = useState<string | null>(null);
  const [slot, setSlot] = useState<Slot | null>(null);
  const [provider, setProvider] = useState<Provider | null>(null);
  const [quantity, setQuantity] = useState(1);
  const [values, setValues] = useState<Record<string, any>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [method, setMethod] = useState<'ONLINE' | 'PAY_ON_DELIVERY'>('PAY_ON_DELIVERY');
  const action = useAction();
  const key = useRef(newIdempotencyKey()).current;
  const currentDay = days.find((d) => d.date === day) ?? days[0];
  useEffect(() => { setSlot(null); setProvider(null); }, [address, currentDay?.date]);
  const providers = useQuery(() => (slot && address
    ? api.get<Provider[]>(`/api/v1/customer/services/${params.serviceItemId}/providers?addressId=${address}&slot=${encodeURIComponent(slot.start)}&quantity=${quantity}`)
    : Promise.resolve([] as Provider[])), [slot?.start, address, quantity]);
  useEffect(() => { setProvider(null); }, [slot?.start]);
  const schema: FormSchema = detail.data?.form_schema ?? null;
  const podAllowed = detail.data?.pay_on_delivery_allowed !== false;
  const perUnit = ['PER_UNIT', 'HOURLY'].includes(detail.data?.pricing_type);
  const chosen = useMemo(() => (providers.data ?? []).find((p) => p.id === provider?.id) ?? null, [providers.data, provider?.id]);

  const book = async () => {
    const e = validateForm(schema, values);
    setErrors(e);
    if (Object.keys(e).length || !chosen || !slot || !address) return;
    const r = await action.run(() => api.post<any>('/api/v1/customer/bookings', {
      service_item_id: params.serviceItemId, partner_id: chosen.id, slot_start: slot.start, address_id: address, quantity,
      answers: normaliseAnswers(schema, values), payment_method: podAllowed ? method : 'ONLINE',
    }, key));
    if (r) nav.replace('RequestDetail', { id: r.id, justCreated: r.request_number });
    else setErrors((prev) => ({ ...prev, ...action.fieldErrors }));
  };

  return (
    <View style={{ flex: 1, backgroundColor: colors.paleMint }}>
      <GradientHeader title={params.title} subtitle="Pick a time and a professional" onBack={nav.pop} />
      <Screen footer={chosen && slot ? (
        <View style={{ gap: 6 }}>
          <Row style={{ justifyContent: 'space-between' }}>
            <Text style={{ color: colors.textSecondary }}>{chosen.display_name} · {currentDay?.label} {time12(slot.start)}</Text>
            <Text style={{ fontWeight: '900', color: colors.forest, fontSize: font.lg }}>{money(chosen.total)}</Text>
          </Row>
          <Button title={podAllowed && method === 'PAY_ON_DELIVERY' ? 'Book now · pay after the service' : 'Book & pay online'} onPress={book} loading={action.busy} />
        </View>
      ) : undefined}>
        {detail.loading || addresses.loading ? <Loading /> : detail.error ? <ErrorState message={detail.error} onRetry={detail.reload} /> : (
          <>
            {detail.data?.advisory_notice ? <Notice tone="warning" message={detail.data.advisory_notice} /> : null}
            <Text style={styles.section}>Where?</Text>
            {(addresses.data ?? []).length ? (
              <Row style={{ flexWrap: 'wrap' }}>
                {(addresses.data ?? []).map((a) => <Chip key={a.id} label={`${a.label}: ${a.locality_name ?? a.line1}`} selected={address === a.id} onPress={() => setAddressId(a.id)} />)}
              </Row>
            ) : <Button title="＋ Add your address" variant="secondary" onPress={() => nav.push('AddressForm', {})} />}

            {address ? (
              <>
                <Text style={styles.section}>When?</Text>
                {slots.loading ? <Loading label="Checking who's free…" /> : slots.error ? <ErrorState message={slots.error} onRetry={slots.reload} /> : !days.length ? (
                  <EmptyState title="No free slots this week" message="No verified professional near you is free in the next days. Send a request instead and we'll arrange it."
                    action="Send a request" onAction={() => nav.replace('NewRequest', { serviceItemId: params.serviceItemId, title: params.title })} />
                ) : (
                  <>
                    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: space.sm }}>
                      {days.map((d) => <Chip key={d.date} label={d.label} selected={currentDay?.date === d.date} onPress={() => setDay(d.date)} />)}
                    </ScrollView>
                    <View style={styles.slots}>
                      {(currentDay?.slots ?? []).map((s) => (
                        <Pressable key={s.start} accessibilityRole="button" accessibilityLabel={`${time12(s.start)}, ${s.providers} professionals free`}
                          onPress={() => setSlot(s)} style={[styles.slot, slot?.start === s.start && styles.slotOn]}>
                          <Text style={[styles.slotTime, slot?.start === s.start && { color: colors.white }]}>{time12(s.start)}</Text>
                          <Text style={[styles.slotSub, slot?.start === s.start && { color: '#E3F5EA' }]}>{s.providers} pro{s.providers === 1 ? '' : 's'} · from {money(s.from_price)}</Text>
                        </Pressable>
                      ))}
                    </View>
                  </>
                )}
              </>
            ) : null}

            {slot ? (
              <>
                <Text style={styles.section}>Who?</Text>
                {perUnit ? (
                  <Row>
                    <Text style={{ color: colors.text }}>Quantity ({detail.data?.unit ?? 'units'})</Text>
                    <Button title="−" small variant="outline" onPress={() => setQuantity((q) => Math.max(1, q - 1))} />
                    <Text style={{ fontWeight: '800', color: colors.text, minWidth: 24, textAlign: 'center' }}>{quantity}</Text>
                    <Button title="＋" small variant="outline" onPress={() => setQuantity((q) => Math.min(50, q + 1))} />
                  </Row>
                ) : null}
                {providers.loading ? <Loading /> : (providers.data ?? []).length === 0 ? (
                  <Notice tone="warning" message="That time was just taken - please pick another slot." />
                ) : (providers.data ?? []).map((p) => (
                  <Card key={p.id} onPress={() => setProvider(p)} accessibilityLabel={`Choose ${p.display_name}`}
                    style={provider?.id === p.id ? { borderColor: colors.secondary, borderWidth: 2 } : undefined}>
                    <Row style={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
                      <View style={{ flex: 1 }}>
                        <Text style={{ fontWeight: '800', color: colors.text, fontSize: font.md }}>{provider?.id === p.id ? '✓ ' : ''}{p.display_name}</Text>
                        <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>
                          {Number(p.rating_count) ? `★ ${Number(p.rating_avg).toFixed(1)} (${p.rating_count})` : 'New on BUZZER'} · {p.jobs_completed} job{Number(p.jobs_completed) === 1 ? '' : 's'} done
                          {p.distance_km != null ? ` · ${Number(p.distance_km).toFixed(1)} km` : ''}
                        </Text>
                      </View>
                      <Text style={{ fontWeight: '900', color: colors.forest, fontSize: font.lg }}>{money(p.total)}</Text>
                    </Row>
                    <Row style={{ flexWrap: 'wrap', marginTop: 4 }}>
                      <Badge label="✓ Verified" tone="success" />
                      <Badge label={p.price_source === 'PROVIDER_PRICE' ? 'Own price (BUZZER approved)' : 'BUZZER listed price'} tone="neutral" />
                    </Row>
                  </Card>
                ))}
              </>
            ) : null}

            {chosen ? (
              <>
                <Text style={styles.section}>Details</Text>
                <Card>
                  <DynamicForm schema={schema} values={values} errors={{ ...errors, ...action.fieldErrors }} onChange={(k, v) => setValues((s) => ({ ...s, [k]: v }))} />
                </Card>
                <Text style={styles.section}>Payment</Text>
                <Row style={{ flexWrap: 'wrap' }}>
                  {podAllowed ? <Chip label="💵 Pay after the service" selected={method === 'PAY_ON_DELIVERY'} onPress={() => setMethod('PAY_ON_DELIVERY')} /> : null}
                  <Chip label="💳 Pay online now" selected={method === 'ONLINE' || !podAllowed} onPress={() => setMethod('ONLINE')} />
                </Row>
                <Notice tone="info" message={`${chosen.display_name} confirms within the hour. If they can't, you pick another professional for the same time at the same price. You share a start and a finish code with them on the day.`} />
              </>
            ) : null}
            {action.error ? <Notice tone="danger" message={action.error} /> : null}
          </>
        )}
      </Screen>
    </View>
  );
}

/** Shown on a booking the professional declined or didn't confirm: choose someone else for the same time and price. */
export function ChooseAnotherProfessional({ requestId, onDone }: { requestId: string; onDone: () => void }) {
  const q = useQuery(() => api.get<Provider[]>(`/api/v1/customer/requests/${requestId}/alternatives`), [requestId]);
  const act = useAction();
  return (
    <Card style={{ borderColor: colors.warning, borderWidth: 2 }}>
      <Text style={{ fontWeight: '800', color: colors.text, fontSize: font.md }}>Choose another professional</Text>
      <Text style={{ color: colors.textSecondary, marginBottom: space.sm }}>Same time, same price - pick who should come instead.</Text>
      {q.loading ? <Loading /> : (q.data ?? []).length === 0 ? (
        <Notice tone="warning" message="Nobody else is free at that time. Cancel this booking and book another time, or ask your agent for help." />
      ) : (q.data ?? []).map((p) => (
        <Row key={p.id} style={{ justifyContent: 'space-between', paddingVertical: 6 }}>
          <View style={{ flex: 1 }}>
            <Text style={{ fontWeight: '700', color: colors.text }}>{p.display_name}</Text>
            <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>{Number(p.rating_count) ? `★ ${Number(p.rating_avg).toFixed(1)}` : 'New'} · {p.jobs_completed} jobs done</Text>
          </View>
          <Button title="Choose" small loading={act.busy} onPress={async () => {
            if (await act.run(() => api.post(`/api/v1/customer/requests/${requestId}/rebook`, { partner_id: p.id }))) onDone();
          }} />
        </Row>
      ))}
      {act.error ? <Notice tone="danger" message={act.error} /> : null}
    </Card>
  );
}

const styles = StyleSheet.create({
  section: { fontSize: font.lg, fontWeight: '800', color: colors.text, marginTop: space.sm },
  slots: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  slot: { width: '31%', minWidth: 96, backgroundColor: colors.white, borderRadius: radius.lg, paddingVertical: 10, alignItems: 'center', borderWidth: 1, borderColor: colors.border },
  slotOn: { backgroundColor: colors.emerald, borderColor: colors.emerald },
  slotTime: { fontWeight: '800', color: colors.text },
  slotSub: { fontSize: 10, color: colors.textSecondary },
});
