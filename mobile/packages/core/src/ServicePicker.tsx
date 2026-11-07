import React, { useEffect, useState } from 'react';
import { Text, View } from 'react-native';
import { api } from './api';
import { colors, space } from './theme';
import { Chip, ErrorState, Label, Loading } from './ui';

export type Picked = { categoryId?: string; subcategoryId?: string; serviceItemId?: string; serviceName?: string };

type Item = { id: string; name: string };

/**
 * Contextual Category -> Subcategory -> Service picker. Each level only shows children of the selected
 * parent (selecting Plumbing shows only plumbing services).
 */
export function ServicePicker({ value, onChange }: { value: Picked; onChange: (p: Picked) => void }) {
  const [cats, setCats] = useState<Item[] | null>(null);
  const [subs, setSubs] = useState<Item[]>([]);
  const [svcs, setSvcs] = useState<Item[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.get<Item[]>('/api/v1/catalogue/categories').then(setCats).catch((e) => setError(e.message));
  }, []);
  useEffect(() => {
    setSubs([]);
    if (value.categoryId) api.get<Item[]>(`/api/v1/catalogue/categories/${value.categoryId}/subcategories`).then(setSubs).catch((e) => setError(e.message));
  }, [value.categoryId]);
  useEffect(() => {
    setSvcs([]);
    if (value.subcategoryId) api.get<Item[]>(`/api/v1/catalogue/subcategories/${value.subcategoryId}/services`).then(setSvcs).catch((e) => setError(e.message));
  }, [value.subcategoryId]);

  if (error) return <ErrorState message={error} />;
  if (!cats) return <Loading />;
  return (
    <View style={{ gap: space.md }}>
      <Label>Category</Label>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
        {cats.map((c) => (
          <Chip key={c.id} label={c.name} selected={value.categoryId === c.id} onPress={() => onChange({ categoryId: c.id })} />
        ))}
      </View>
      {subs.length ? (
        <>
          <Label>Subcategory</Label>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
            {subs.map((s) => (
              <Chip key={s.id} label={s.name} selected={value.subcategoryId === s.id}
                onPress={() => onChange({ categoryId: value.categoryId, subcategoryId: s.id })} />
            ))}
          </View>
        </>
      ) : null}
      {svcs.length ? (
        <>
          <Label>Service</Label>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
            {svcs.map((s) => (
              <Chip key={s.id} label={s.name} selected={value.serviceItemId === s.id}
                onPress={() => onChange({ ...value, serviceItemId: s.id, serviceName: s.name })} />
            ))}
          </View>
        </>
      ) : null}
      {value.serviceName ? <Text style={{ color: colors.emerald, fontWeight: '600' }}>Selected: {value.serviceName}</Text> : null}
    </View>
  );
}
