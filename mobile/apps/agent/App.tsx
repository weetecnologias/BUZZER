import React, { useState } from 'react';
import { View } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { AuthProvider, BottomTabs, NotificationsInbox, StackNavigator, useNav } from '@buzzer/core';
import { DashboardScreen } from './src/screens/Dashboard';
import { InboxScreen } from './src/screens/Inbox';
import { AgentRequestScreen } from './src/screens/AgentRequest';
import { CustomersScreen, CustomerDetailScreen, RegisterCustomerScreen } from './src/screens/Customers';
import { CreateRequestScreen } from './src/screens/CreateRequest';
import { PrepareDraftScreen } from './src/screens/Drafts';

const TABS = [
  { key: 'home', label: 'Dashboard', glyph: '📊' },
  { key: 'inbox', label: 'Requests', glyph: '📥' },
  { key: 'customers', label: 'Customers', glyph: '👥' },
  { key: 'alerts', label: 'Alerts', glyph: '🔔' },
];

function Tabs() {
  const [tab, setTab] = useState('home');
  const nav = useNav();
  return (
    <View style={{ flex: 1 }}>
      <View style={{ flex: 1 }}>
        {tab === 'home' && <DashboardScreen onOpenTab={setTab} />}
        {tab === 'inbox' && <InboxScreen />}
        {tab === 'customers' && <CustomersScreen />}
        {tab === 'alerts' && <NotificationsInbox onOpen={(n) => { if (n.ref_type === 'REQUEST' && n.ref_id) nav.push('Request', { id: n.ref_id }); }} />}
      </View>
      <BottomTabs tabs={TABS} active={tab} onChange={setTab} />
    </View>
  );
}

export default function App() {
  return (
    <SafeAreaProvider>
      <StatusBar style="light" />
      <AuthProvider app="AGENT" brand={{ title: 'BUZZER Agent', tagline: 'Your customers. Your portfolio. One workspace.' }}>
        <StackNavigator
          initial={{ name: 'Tabs' }}
          screens={{
            Tabs: () => <Tabs />,
            Request: ({ params }) => <AgentRequestScreen params={params} />,
            Customer: ({ params }) => <CustomerDetailScreen params={params} />,
            RegisterCustomer: () => <RegisterCustomerScreen />,
            CreateRequest: ({ params }) => <CreateRequestScreen params={params} />,
            PrepareDraft: ({ params }) => <PrepareDraftScreen params={params} />,
          }}
        />
      </AuthProvider>
    </SafeAreaProvider>
  );
}
