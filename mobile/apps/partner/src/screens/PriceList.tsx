import React, { useMemo, useState } from 'react';
import { Text, TextInput, View } from 'react-native';
import {
  api, Button, Card, colors, EmptyState, ErrorState, font, money, GradientHeader, Loading, Notice, radius, Row, Screen, space, StatusBadge,
  useAction, useNav, useQuery,
} from '@buzzer/core';

type Item = {
  service_item_id: string; name: string; menu_group: string | null; pricing_type: string; catalogue_price: number | null; unit: string | null;
  subcategory_id: string; subcategory_name: string; category_name: string; price: number | null; notes: string | null;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | null; approved_price: number | null; review_note: string | null;
};

/**
 * Partner "Services & prices": the partner sets their own price for every menu item they are approved for.
 * Admin approves the prices; approved prices let agents send customers instant quotations.
 */
export function PriceListScreen() {
  const nav = useNav();
  const q = useQuery(() => api.get<Item[]>('/api/v1/partner/price-list'));
  const act = useAction();
  const [edits, setEdits] = useState<Record<string, { price: string; notes: string }>>({});
  const [msg, setMsg] = useState<string | null>(null);

  const sections = useMemo(() => {
    const out: { title: string; items: Item[] }[] = [];
    (q.data ?? []).forEach((i) => {
      const title = `${i.category_name} › ${i.subcategory_name}`;
      const last = out[out.length - 1];
      if (last && last.title === title) last.items.push(i);
      else out.push({ title, items: [i] });
    });
    return out;
  }, [q.data]);

  const changed = Object.entries(edits).filter(([id, e]) => {
    const it = q.data?.find((x) => x.service_item_id === id);
    return e.price.trim() !== '' && (Number(e.price) !== Number(it?.price ?? NaN) || (e.notes || '') !== (it?.notes ?? ''));
  });
  const invalid = changed.some(([, e]) => !(Number(e.price) >= 0) || isNaN(Number(e.price)));

  const value = (i: Item) => edits[i.service_item_id] ?? { price: i.price != null ? String(Number(i.price)) : '', notes: i.notes ?? '' };
  const set = (i: Item, patch: Partial<{ price: string; notes: string }>) => setEdits({ ...edits, [i.service_item_id]: { ...value(i), ...patch } });
  const applyListed = (i: Item) => i.catalogue_price != null && set(i, { price: String(Number(i.catalogue_price)) });

  const submit = async () => {
    const prices = changed.map(([id, e]) => ({ service_item_id: id, price: Number(e.price), notes: e.notes.trim() || null }));
    if (await act.run(() => api.put('/api/v1/partner/price-list', { prices }))) {
      setEdits({});
      setMsg(`${prices.length} price${prices.length === 1 ? '' : 's'} sent for admin approval. Unchanged approved prices stay live.`);
      q.refresh();
    }
  };

  const counts = (q.data ?? []).reduce((a, i) => ({ ...a, [i.status ?? 'NOT_SET']: (a[i.status ?? 'NOT_SET'] ?? 0) + 1 }), {} as Record<string, number>);

  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="Services & prices" subtitle="Your price for each service you offer" onBack={nav.pop} />
      <Screen refreshing={q.refreshing} onRefresh={q.refresh}
        footer={changed.length ? <Button title={`Submit ${changed.length} price${changed.length === 1 ? '' : 's'} for approval`} loading={act.busy} disabled={invalid} onPress={submit} /> : undefined}>
        {q.loading ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : !(q.data ?? []).length ? (
          <EmptyState title="No approved services yet" message="Request services from the Account tab. Once an admin approves them you can set your prices here." />
        ) : (
          <>
            <Notice tone="info" message="Set your own price per item. After admin approval, BUZZER agents can send customers an instant quotation from your price list — no back-and-forth needed." />
            <Row style={{ flexWrap: 'wrap', gap: space.sm, marginBottom: space.sm }}>
              <Text style={{ color: colors.textSecondary }}>Approved {counts.APPROVED ?? 0}</Text>
              <Text style={{ color: colors.textSecondary }}>• Pending {counts.PENDING ?? 0}</Text>
              <Text style={{ color: colors.textSecondary }}>• Rejected {counts.REJECTED ?? 0}</Text>
              <Text style={{ color: colors.textSecondary }}>• Not set {counts.NOT_SET ?? 0}</Text>
            </Row>
            {sections.map((s) => (
              <Card key={s.title}>
                <Text style={{ fontWeight: '800', color: colors.text, marginBottom: space.sm }}>{s.title}</Text>
                {s.items.map((i, idx) => {
                  const v = value(i);
                  const showGroup = i.menu_group && i.menu_group !== s.items[idx - 1]?.menu_group;
                  return (
                    <View key={i.service_item_id}>
                      {showGroup ? <Text style={{ color: colors.textSecondary, fontWeight: '700', fontSize: font.sm, marginTop: space.sm, textTransform: 'uppercase' }}>{i.menu_group}</Text> : null}
                      <View style={{ paddingVertical: space.sm, borderBottomWidth: idx === s.items.length - 1 ? 0 : 1, borderBottomColor: colors.border }}>
                        <Row style={{ justifyContent: 'space-between' }}>
                          <Text style={{ color: colors.text, fontWeight: '600', flex: 1 }}>{i.name}</Text>
                          {i.status ? <StatusBadge status={i.status} /> : <Text style={{ color: colors.textSecondary, fontSize: font.sm }}>not set</Text>}
                        </Row>
                        <Text style={{ color: colors.textSecondary, fontSize: font.sm }}>
                          BUZZER listed: {i.catalogue_price != null ? money(i.catalogue_price) : 'on quote'}{i.unit ? ` ${i.unit}` : ''}
                          {i.approved_price != null ? `  •  Your live price: ${money(i.approved_price)}` : ''}
                        </Text>
                        <Row style={{ marginTop: 6, gap: space.sm }}>
                          <Text style={{ color: colors.text, fontWeight: '700' }}>₹</Text>
                          <TextInput value={v.price} onChangeText={(t) => set(i, { price: t.replace(/[^0-9.]/g, '') })} keyboardType="decimal-pad" placeholder="Your price"
                            placeholderTextColor={colors.textSecondary} accessibilityLabel={`Your price for ${i.name}`}
                            style={{ flex: 1, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, paddingHorizontal: 10, paddingVertical: 8, color: colors.text, backgroundColor: colors.white }} />
                          {i.catalogue_price != null ? <Button title="Use listed" small variant="ghost" onPress={() => applyListed(i)} /> : null}
                        </Row>
                        <TextInput value={v.notes} onChangeText={(t) => set(i, { notes: t })} placeholder="Notes (optional, e.g. materials extra)" placeholderTextColor={colors.textSecondary}
                          style={{ marginTop: 6, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, paddingHorizontal: 10, paddingVertical: 6, color: colors.text, backgroundColor: colors.white, fontSize: font.sm }} />
                        {i.review_note ? <Text style={{ color: i.status === 'REJECTED' ? colors.danger : colors.textSecondary, fontSize: font.sm, marginTop: 4 }}>Admin: {i.review_note}</Text> : null}
                      </View>
                    </View>
                  );
                })}
              </Card>
            ))}
            <Notice tone="neutral" message="Prefer a spreadsheet? Ask your BUZZER agent or admin for your Excel price sheet — the same list can be uploaded in bulk." />
          </>
        )}
        {msg ? <Notice tone="success" message={msg} /> : null}
        {act.error ? <ErrorState message={act.error} /> : null}
      </Screen>
    </View>
  );
}
