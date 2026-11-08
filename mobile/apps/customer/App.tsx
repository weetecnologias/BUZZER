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
import { OnboardingGate, SubscribeAgentScreen } from './src/screens/Onboarding';
import { AssistanceScreen, CreateRequestScreen, ExploreScreen, OrdersScreen, UniversalSearchScreen } from './src/screens/Unified';
import { CompareQuotesScreen, OrderDetailScreen, ProductCartScreen, ProductScreen, RfqCreateScreen, ShopProfileScreen, ShopScreen } from './src/screens/Shop';

/** Unified BUZZER navigation: Home · Explore · My Requests · Orders · Account (notifications live in the header). */
const TABS = [
  { key: 'home', label: 'Home', glyph: '🏠' },
  { key: 'explore', label: 'Explore', glyph: '🧭' },
  { key: 'requests', label: 'My Requests', glyph: '📋' },
  { key: 'orders', label: 'Orders', glyph: '📦' },
  { key: 'account', label: 'Account', glyph: '👤' },
];

function Tabs({ params }: { params: { tab?: string; vertical?: string } }) {
  const [tab, setTab] = useState(params.tab ?? 'home');
  const [vertical, setVertical] = useState<string | undefined>(params.vertical);
  const open = (t: string, opts?: { vertical?: string }) => {
    if (opts?.vertical) setVertical(opts.vertical);
    setTab(t === 'alerts' ? 'home' : t);
  };
  return (
    <View style={{ flex: 1 }}>
      <View style={{ flex: 1 }}>
        {tab === 'home' && <HomeScreen onOpenTab={open} />}
        {tab === 'explore' && <ExploreScreen vertical={vertical} onVertical={setVertical} />}
        {tab === 'requests' && <RequestsScreen />}
        {tab === 'orders' && <OrdersScreen />}
        {tab === 'account' && <ProfileScreen />}
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
        <OnboardingGate>
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
            SubscribeAgent: () => <SubscribeAgentScreen />,
            Search: ({ params }) => <UniversalSearchScreen params={params} />,
            CreateRequest: ({ params }) => <CreateRequestScreen params={params} />,
            Assistance: () => <AssistanceScreen />,
            Notifications: () => <NotificationsScreen />,
            Shop: ({ params }) => <ShopScreen params={params ?? {}} />,
            Product: ({ params }) => <ProductScreen params={params} />,
            ShopProfile: ({ params }) => <ShopProfileScreen params={params} />,
            ProductCart: () => <ProductCartScreen />,
            OrderDetail: ({ params }) => <OrderDetailScreen params={params} />,
            RfqCreate: ({ params }) => <RfqCreateScreen params={params ?? {}} />,
            CompareQuotes: ({ params }) => <CompareQuotesScreen params={params} />,
          }}
        />
        </CartProvider>
        </OnboardingGate>
      </AuthProvider>
    </SafeAreaProvider>
  );
}
