import React, { useState } from 'react';
import { View } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { AuthProvider, BottomTabs, StackNavigator } from '@buzzer/core';
import { HomeScreen } from './src/screens/Home';
import { CategoriesScreen, CategoryScreen } from './src/screens/Browse';
import { NewRequestScreen } from './src/screens/NewRequest';
import { RequestsScreen } from './src/screens/Requests';
import { RequestDetailScreen } from './src/screens/RequestDetail';
import { AddressesScreen, AddressFormScreen } from './src/screens/Addresses';
import { ProfileScreen } from './src/screens/Profile';
import { NotificationsScreen } from './src/screens/Notifications';
import { CartScreen, WishlistScreen, WriteToAgentScreen } from './src/screens/Cart';
import { CartProvider } from './src/cart';

const TABS = [
  { key: 'home', label: 'Home', glyph: '🏠' },
  { key: 'requests', label: 'Requests', glyph: '📋' },
  { key: 'alerts', label: 'Updates', glyph: '🔔' },
  { key: 'profile', label: 'Profile', glyph: '👤' },
];

function Tabs({ params }: { params: { tab?: string } }) {
  const [tab, setTab] = useState(params.tab ?? 'home');
  return (
    <View style={{ flex: 1 }}>
      <View style={{ flex: 1 }}>
        {tab === 'home' && <HomeScreen onOpenTab={setTab} />}
        {tab === 'requests' && <RequestsScreen />}
        {tab === 'alerts' && <NotificationsScreen />}
        {tab === 'profile' && <ProfileScreen />}
      </View>
      <BottomTabs tabs={TABS} active={tab} onChange={setTab} />
    </View>
  );
}

export default function App() {
  return (
    <SafeAreaProvider>
      <StatusBar style="light" />
      <AuthProvider app="CUSTOMER" brand={{ title: 'BUZZER', tagline: 'Your Personal Assistant for Everyday Life' }}>
        <CartProvider>
        <StackNavigator
          initial={{ name: 'Tabs' }}
          screens={{
            Tabs: ({ params }) => <Tabs params={params} />,
            Categories: () => <CategoriesScreen />,
            Category: ({ params }) => <CategoryScreen params={params} />,
            NewRequest: ({ params }) => <NewRequestScreen params={params} />,
            RequestDetail: ({ params }) => <RequestDetailScreen params={params} />,
            Addresses: () => <AddressesScreen />,
            AddressForm: ({ params }) => <AddressFormScreen params={params} />,
            Cart: () => <CartScreen />,
            Wishlist: () => <WishlistScreen />,
            WriteToAgent: ({ params }) => <WriteToAgentScreen params={params} />,
          }}
        />
        </CartProvider>
      </AuthProvider>
    </SafeAreaProvider>
  );
}
