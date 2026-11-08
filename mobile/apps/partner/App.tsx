import React, { useState } from 'react';
import { View } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { api, AuthProvider, BottomTabs, NotificationsInbox, StackNavigator, useAuth, useNav, useQuery } from '@buzzer/core';
import { RegistrationScreen } from './src/screens/Registration';
import { DashboardScreen } from './src/screens/Dashboard';
import { LeadsScreen, LeadDetailScreen } from './src/screens/Leads';
import { JobsScreen, JobDetailScreen } from './src/screens/Jobs';
import { EarningsScreen } from './src/screens/Earnings';
import { AccountScreen } from './src/screens/Account';
import { PriceListScreen } from './src/screens/PriceList';
import { CatalogueScreen, ListingFormScreen, LocationFormScreen, ProposeProductScreen, ShopHomeScreen, ShopOrderScreen } from './src/screens/Shop';

/** Role-based tabs: shops (retail, wholesale, distributor) get a Shop tab; service providers keep Jobs. */
function tabsFor(roles: { role: string; status: string }[]) {
  const has = (r: string[]) => roles.some((x) => r.includes(x.role) && x.status !== 'REJECTED');
  const shop = has(['RETAIL_SHOP', 'WHOLESALER', 'DISTRIBUTOR']);
  const service = has(['SERVICE_PROVIDER', 'TASK_PARTNER']) || !shop;
  return [
    { key: 'home', label: shop && service ? 'Home' : 'Dashboard', glyph: '🏠' },
    { key: 'leads', label: shop && !service ? 'RFQ inbox' : 'Leads', glyph: '📥' },
    ...(shop ? [{ key: 'shop', label: 'Shop', glyph: '🏪' }] : []),
    ...(service ? [{ key: 'jobs', label: 'Jobs', glyph: '🧰' }] : []),
    { key: 'earnings', label: shop && service ? 'Money' : 'Earnings', glyph: '💰' },
    { key: 'account', label: 'Account', glyph: '👤' },
  ];
}

function Tabs() {
  const [tab, setTab] = useState('home');
  const me = useQuery(() => api.get<any>('/api/v1/partner/me'));
  const TABS = tabsFor(me.data?.roles ?? []);
  return (
    <View style={{ flex: 1 }}>
      <View style={{ flex: 1 }}>
        {tab === 'home' && <DashboardScreen onOpenTab={setTab} />}
        {tab === 'leads' && <LeadsScreen />}
        {tab === 'jobs' && <JobsScreen />}
        {tab === 'shop' && <ShopHomeScreen />}
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
        ShopOrder: ({ params }) => <ShopOrderScreen params={params} />,
        Catalogue: () => <CatalogueScreen />,
        ListingForm: ({ params }) => <ListingFormScreen params={params} />,
        ProposeProduct: () => <ProposeProductScreen />,
        LocationForm: ({ params }) => <LocationFormScreen params={params ?? {}} />,
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
