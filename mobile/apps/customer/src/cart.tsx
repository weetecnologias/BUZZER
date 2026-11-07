import React, { createContext, ReactNode, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { api } from '@buzzer/core';

export type CartLine = {
  service_item_id: string; name: string; quantity: number; pricing_type: string; unit: string | null; unit_price: number | null;
  original_price: number | null; deal_label: string | null; line_total: number | null; estimated: boolean; image_url: string | null;
  icon: string | null; subcategory_id: string; subcategory_name: string; subcategory_icon: string | null; category_id: string; category_name: string;
};

export type CartView = {
  items: CartLine[]; item_count: number; estimated_total: number; savings: number; has_quote_items: boolean; has_estimates: boolean;
  request_count: number;
};

type CartState = {
  cart: CartView | null;
  wishlist: Set<string>;
  qtyOf: (serviceItemId: string) => number;
  add: (serviceItemId: string) => Promise<void>;
  setQty: (serviceItemId: string, qty: number) => Promise<void>;
  toggleWish: (serviceItemId: string) => Promise<void>;
  refresh: () => Promise<void>;
  error: string | null;
  clearError: () => void;
};

const EMPTY: CartView = { items: [], item_count: 0, estimated_total: 0, savings: 0, has_quote_items: false, has_estimates: false, request_count: 0 };
const Ctx = createContext<CartState | null>(null);

/** Categories whose items are bought through the cart; others use a requirement form. */
export const CART_WORKFLOWS = ['MENU_DRIVEN', 'EVENT_BRIEF', 'CATEGORY_SPECIAL_REQUEST', 'API_PRICED'];

export function isCartable(item: { is_other?: boolean }, workflow?: string | null) {
  return !item.is_other && (!workflow || CART_WORKFLOWS.includes(workflow));
}

export function CartProvider({ children }: { children: ReactNode }) {
  const [cart, setCart] = useState<CartView | null>(null);
  const [wishlist, setWishlist] = useState<Set<string>>(new Set());
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [c, w] = await Promise.all([api.get<CartView>('/api/v1/customer/cart'), api.get<string[]>('/api/v1/customer/wishlist/ids')]);
      setCart(c);
      setWishlist(new Set(w));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not load your cart');
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const run = useCallback(async (fn: () => Promise<CartView>) => {
    setError(null);
    try {
      setCart(await fn());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Something went wrong');
    }
  }, []);

  const value = useMemo<CartState>(() => ({
    cart: cart ?? EMPTY,
    wishlist,
    error,
    clearError: () => setError(null),
    qtyOf: (id) => (cart?.items ?? []).find((l) => l.service_item_id === id)?.quantity ?? 0,
    add: (id) => run(() => api.post<CartView>('/api/v1/customer/cart/items', { service_item_id: id, quantity: 1 })),
    setQty: (id, qty) => run(() => api.put<CartView>(`/api/v1/customer/cart/items/${id}`, { quantity: qty })),
    toggleWish: async (id) => {
      const on = !wishlist.has(id);
      setWishlist((prev) => {
        const next = new Set(prev);
        if (on) next.add(id); else next.delete(id);
        return next;
      });
      try {
        if (on) await api.put(`/api/v1/customer/wishlist/${id}`, {});
        else await api.del(`/api/v1/customer/wishlist/${id}`);
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Could not update wishlist');
        refresh();
      }
    },
    refresh,
  }), [cart, wishlist, error, run, refresh]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useCart(): CartState {
  const c = useContext(Ctx);
  if (!c) throw new Error('useCart must be used inside <CartProvider>');
  return c;
}
