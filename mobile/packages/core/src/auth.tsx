import React, { createContext, ReactNode, useCallback, useContext, useEffect, useState } from 'react';
import { KeyboardAvoidingView, Platform, ScrollView, StyleSheet, Text, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { SafeAreaView } from 'react-native-safe-area-context';
import { api, ApiError, AppType } from './api';
import { colors, font, gradient, radius, space } from './theme';
import { Button, ErrorState, Loading, Notice, TextField } from './ui';

export type Me = { id: string; phone: string; full_name: string; email: string; roles: string[] };

type AuthState = {
  status: 'loading' | 'signedOut' | 'signedIn';
  me: Me | null;
  signOut: () => Promise<void>;
  reloadMe: () => Promise<void>;
  /** Re-issues tokens (e.g. after partner registration grants a new role). */
  refreshSession: () => Promise<void>;
};

const AuthContext = createContext<AuthState | null>(null);

export function useAuth(): AuthState {
  const a = useContext(AuthContext);
  if (!a) throw new Error('useAuth must be used inside <AuthProvider>');
  return a;
}

export function AuthProvider({ app, children, brand }: { app: AppType; children: ReactNode; brand: { title: string; tagline: string } }) {
  const [status, setStatus] = useState<AuthState['status']>('loading');
  const [me, setMe] = useState<Me | null>(null);

  const reloadMe = useCallback(async () => {
    try {
      setMe(await api.get<Me>('/api/v1/auth/me'));
      setStatus('signedIn');
    } catch {
      setStatus('signedOut');
    }
  }, []);

  useEffect(() => {
    api.onSessionExpired = () => {
      setMe(null);
      setStatus('signedOut');
    };
    api.load().then((has) => (has ? reloadMe() : setStatus('signedOut')));
  }, [reloadMe]);

  const signOut = useCallback(async () => {
    await api.clear();
    setMe(null);
    setStatus('signedOut');
  }, []);

  const refreshSession = useCallback(async () => {
    await api.refresh();
    await reloadMe();
  }, [reloadMe]);

  if (status === 'loading') return <Loading label="Starting BUZZER…" />;
  if (status === 'signedOut') {
    return <OtpLogin app={app} brand={brand} onSignedIn={reloadMe} />;
  }
  return <AuthContext.Provider value={{ status, me, signOut, reloadMe, refreshSession }}>{children}</AuthContext.Provider>;
}

function OtpLogin({ app, brand, onSignedIn }: { app: AppType; brand: { title: string; tagline: string }; onSignedIn: () => void }) {
  const [phone, setPhone] = useState('');
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [step, setStep] = useState<'phone' | 'code'>('phone');
  const [devCode, setDevCode] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const requestOtp = async () => {
    setError(null);
    if (!/^[6-9]\d{9}$/.test(phone.replace(/\D/g, '').slice(-10))) {
      setError('Enter a valid 10-digit mobile number');
      return;
    }
    setBusy(true);
    try {
      const r = await api.request<{ dev_code: string | null }>('POST', '/api/v1/auth/otp/request', { phone, app }, { auth: false });
      setDevCode(r.dev_code);
      setStep('code');
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not send OTP');
    } finally {
      setBusy(false);
    }
  };

  const verify = async () => {
    setError(null);
    setBusy(true);
    try {
      const r = await api.request<{ tokens: { access_token: string; refresh_token: string } }>('POST', '/api/v1/auth/otp/verify',
        { phone, code, app, full_name: name || undefined }, { auth: false });
      await api.setTokens(r.tokens);
      onSignedIn();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Verification failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <LinearGradient colors={gradient.primary} style={{ flex: 1 }} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }}>
      <SafeAreaView style={{ flex: 1 }}>
        <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1 }}>
          <ScrollView contentContainerStyle={styles.wrap} keyboardShouldPersistTaps="handled">
            <View style={styles.logo}><Text style={styles.logoB}>B</Text></View>
            <Text style={styles.brand}>{brand.title}</Text>
            <Text style={styles.tagline}>{brand.tagline}</Text>
            <View style={styles.card}>
              {step === 'phone' ? (
                <>
                  <Text style={styles.cardTitle}>{app === 'CUSTOMER' ? 'Sign up or log in with your mobile number' : 'Login with your mobile number'}</Text>
                  <TextField label="Mobile number" value={phone} onChangeText={setPhone} keyboardType="phone-pad" placeholder="98765 43210"
                    maxLength={14} autoComplete="tel" />
                  {app === 'CUSTOMER' || app === 'PARTNER' ? (
                    <TextField label="Your name (new users)" value={name} onChangeText={setName} placeholder="Full name" autoComplete="name" />
                  ) : null}
                  {error ? <ErrorState message={error} /> : null}
                  <Button title="Send OTP" onPress={requestOtp} loading={busy} style={{ marginTop: space.sm }} />
                </>
              ) : (
                <>
                  <Text style={styles.cardTitle}>Enter the 6-digit OTP</Text>
                  <Text style={{ color: colors.textSecondary, marginBottom: space.md }}>Sent to +91 {phone.replace(/\D/g, '').slice(-10)}</Text>
                  {devCode ? <Notice tone="warning" message={`DEV MODE - your OTP is ${devCode}. (Never shown in production.)`} /> : null}
                  <TextField label="OTP" value={code} onChangeText={setCode} keyboardType="number-pad" maxLength={6} autoComplete="one-time-code"
                    textContentType="oneTimeCode" style={{ letterSpacing: 8, fontSize: 22, textAlign: 'center' }} />
                  {error ? <ErrorState message={error} /> : null}
                  <Button title="Verify & continue" onPress={verify} loading={busy} disabled={code.length < 6} />
                  <Button title="Change number" variant="ghost" onPress={() => { setStep('phone'); setCode(''); setError(null); }} />
                </>
              )}
            </View>
            <Text style={styles.footer}>You Buzz. We Do.</Text>
          </ScrollView>
        </KeyboardAvoidingView>
      </SafeAreaView>
    </LinearGradient>
  );
}

const styles = StyleSheet.create({
  wrap: { flexGrow: 1, justifyContent: 'center', padding: space.xl, alignItems: 'center' },
  logo: { width: 72, height: 72, borderRadius: radius.lg, backgroundColor: '#B8F07A', alignItems: 'center', justifyContent: 'center', marginBottom: space.md },
  logoB: { fontSize: 40, fontWeight: '900', color: colors.forest },
  brand: { color: colors.white, fontSize: font.display, fontWeight: '900', letterSpacing: 1 },
  tagline: { color: '#D7F2E1', fontSize: font.md, marginBottom: space.xl, textAlign: 'center' },
  card: { backgroundColor: colors.white, borderRadius: radius.xl, padding: space.xl, width: '100%', maxWidth: 420 },
  cardTitle: { fontSize: font.lg, fontWeight: '700', color: colors.text, marginBottom: space.md },
  footer: { color: '#D7F2E1', marginTop: space.xl, fontWeight: '600' },
});

