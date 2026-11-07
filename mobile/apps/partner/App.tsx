import React, { useState } from 'react';
import { View } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { AuthProvider, BottomTabs, NotificationsInbox, StackNavigator, useAuth, useNav } from '@buzzer/core';
import { RegistrationScreen } from './src/screens/Registration';
import { DashboardScreen } from './src/screens/Dashboard';
import { LeadsScreen, LeadDetailScreen } from './src/screens/Leads';
import { JobsScreen, JobDetailScreen } from './src/screens/Jobs';
import { EarningsScreen } from './src/screens/Earnings';
import { AccountScreen } from './src/screens/Account';
import { PriceListScreen } from './src/screens/PriceList';

const TABS = [
  { key: 'home', label: 'Dashboard', glyph: '🏠' },
  { key: 'leads', label: 'Leads', glyph: '📥' },
  { key: 'jobs', label: 'Jobs', glyph: '🧰' },
  { key: 'earnings', label: 'Earnings', glyph: '💰' },
  { key: 'account', label: 'Account', glyph: '👤' },
];

function Tabs() {
  const [tab, setTab] = useState('home');
  return (
    <View style={{ flex: 1 }}>
      <View style={{ flex: 1 }}>
        {tab === 'home' && <DashboardScreen onOpenTab={setTab} />}
        {tab === 'leads' && <LeadsScreen />}
        {tab === 'jobs' && <JobsScreen />}
        {tab === 'earnings' && <EarningsScreen />}
        {tab === 'account' && <AccountScreen />}
      </View>
      <BottomTabs tabs={TABS} active={tab} onChange={setTab} />
    </View>
  );
}

/** Partners without the PARTNER role (new sign-ups) are taken to registration first. */
function Root() {
  const { me } = useAuth();
  if (!me?.roles?.includes('PARTNER')) return <RegistrationScreen />;
  return (
    <StackNavigator
      initial={{ name: 'Tabs' }}
      screens={{
        Tabs: () => <Tabs />,
        LeadDetail: ({ params }) => <LeadDetailScreen params={params} />,
        JobDetail: ({ params }) => <JobDetailScreen params={params} />,
        Notifications: () => <NotificationsWrapper />,
        PriceList: () => <PriceListScreen />,
      }}
    />
  );
}

function NotificationsWrapper() {
  const nav = useNav();
  return <NotificationsInbox onBack={nav.pop} />;
}

export default function App() {
  return (
    <SafeAreaProvider>
      <StatusBar style="light" />
      <AuthProvider app="PARTNER" brand={{ title: 'BUZZER Partner', tagline: 'Grow your business with verified leads' }}>
        <Root />
      </AuthProvider>
    </SafeAreaProvider>
  );
}
