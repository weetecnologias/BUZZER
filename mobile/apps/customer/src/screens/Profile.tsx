import React, { useEffect, useState } from 'react';
import { Text, View } from 'react-native';
import {
  api, Avatar, Button, Card, colors, ErrorState, font, GradientHeader, KeyValue, Loading, Notice, Row, Screen, space, TextField, Toggle,
  useAction, useAuth, useNav, useQuery,
} from '@buzzer/core';

export function ProfileScreen() {
  const nav = useNav();
  const { signOut } = useAuth();
  const q = useQuery(() => api.get<any>('/api/v1/customer/profile'));
  const act = useAction();
  const [form, setForm] = useState<any>(null);
  const [saved, setSaved] = useState(false);
  useEffect(() => {
    if (q.data) setForm({ full_name: q.data.full_name ?? '', email: q.data.email ?? '', preferred_language: q.data.preferred_language ?? '',
      notify_push: q.data.notify_push, notify_sms: q.data.notify_sms, notify_whatsapp: q.data.notify_whatsapp, notify_email: q.data.notify_email });
  }, [q.data]);

  const save = async () => {
    setSaved(false);
    if (await act.run(() => api.put('/api/v1/customer/profile', form))) setSaved(true);
  };

  return (
    <View style={{ flex: 1 }}>
      <GradientHeader title="Profile" subtitle="Your details and preferences" />
      <Screen>
        {q.loading || !form ? <Loading /> : q.error ? <ErrorState message={q.error} onRetry={q.reload} /> : (
          <>
            <Card>
              <Row>
                <Avatar name={form.full_name || q.data.phone} size={56} />
                <View>
                  <Text style={{ fontWeight: '800', fontSize: font.lg, color: colors.text }}>{form.full_name || 'Add your name'}</Text>
                  <Text style={{ color: colors.textSecondary }}>{q.data.phone}</Text>
                </View>
              </Row>
              <KeyValue k="City" v={q.data.city_name ?? '—'} />
              <KeyValue k="Membership" v="Free (paid plans coming soon)" />
            </Card>
            <Card>
              <TextField label="Full name" value={form.full_name} onChangeText={(t) => setForm({ ...form, full_name: t })} />
              <TextField label="Email" value={form.email} onChangeText={(t) => setForm({ ...form, email: t })} keyboardType="email-address" autoCapitalize="none" />
              <TextField label="Preferred language" value={form.preferred_language} onChangeText={(t) => setForm({ ...form, preferred_language: t })}
                hint="e.g. Telugu, Hindi, English — helps us match your agent" />
            </Card>
            <Card>
              <Text style={{ fontWeight: '700', color: colors.text, marginBottom: space.sm }}>Notifications</Text>
              <Toggle label="Push notifications" value={!!form.notify_push} onChange={(v) => setForm({ ...form, notify_push: v })} />
              <Toggle label="SMS" value={!!form.notify_sms} onChange={(v) => setForm({ ...form, notify_sms: v })} />
              <Toggle label="WhatsApp" value={!!form.notify_whatsapp} onChange={(v) => setForm({ ...form, notify_whatsapp: v })} />
              <Toggle label="Email" value={!!form.notify_email} onChange={(v) => setForm({ ...form, notify_email: v })} />
            </Card>
            {act.error ? <ErrorState message={act.error} /> : null}
            {saved ? <Notice tone="success" message="Saved" /> : null}
            <Button title="Save changes" onPress={save} loading={act.busy} />
            <Button title="Saved addresses" variant="secondary" onPress={() => nav.push('Addresses')} />
            <Button title="♡ My wishlist" variant="secondary" onPress={() => nav.push('Wishlist')} />
            <Button title="🛒 My cart" variant="secondary" onPress={() => nav.push('Cart')} />
            <Button title="✍️ Write to my agent" variant="secondary" onPress={() => nav.push('WriteToAgent', {})} />
            <Button title="Log out" variant="ghost" onPress={signOut} />
          </>
        )}
      </Screen>
    </View>
  );
}
