import React, { createContext, ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { BackHandler, Platform, View } from 'react-native';

/**
 * Minimal, dependency-free stack navigator shared by the three mobile apps.
 * Screens lower in the stack stay mounted (hidden), so going back preserves form state.
 * Android hardware back pops the stack. useIsFocused() lets a screen refresh when it returns to the top.
 */
export type Route = { name: string; params?: Record<string, any>; key: number };

type NavApi = {
  route: Route;
  push: (name: string, params?: Record<string, any>) => void;
  replace: (name: string, params?: Record<string, any>) => void;
  pop: () => void;
  reset: (name: string, params?: Record<string, any>) => void;
  canGoBack: boolean;
};

const NavContext = createContext<NavApi | null>(null);
const FocusContext = createContext<boolean>(true);

export function useNav(): NavApi {
  const n = useContext(NavContext);
  if (!n) throw new Error('useNav must be used inside <StackNavigator>');
  return n;
}

export function useIsFocused(): boolean {
  return useContext(FocusContext);
}

/** Runs the callback whenever the screen comes back to the top of the stack (not on first mount). */
export function useOnRefocus(cb: () => void) {
  const focused = useIsFocused();
  const first = useRef(true);
  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    if (focused) cb();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focused]);
}

let counter = 0;

export function StackNavigator({ initial, screens }: { initial: Omit<Route, 'key'>; screens: Record<string, (p: { params: any }) => ReactNode> }) {
  const [stack, setStack] = useState<Route[]>([{ ...initial, key: ++counter }]);

  const push = useCallback((name: string, params?: Record<string, any>) => setStack((s) => [...s, { name, params, key: ++counter }]), []);
  const replace = useCallback((name: string, params?: Record<string, any>) => setStack((s) => [...s.slice(0, -1), { name, params, key: ++counter }]), []);
  const pop = useCallback(() => setStack((s) => (s.length > 1 ? s.slice(0, -1) : s)), []);
  const reset = useCallback((name: string, params?: Record<string, any>) => setStack([{ name, params, key: ++counter }]), []);

  useEffect(() => {
    if (Platform.OS !== 'android') return;
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (stack.length > 1) {
        pop();
        return true;
      }
      return false;
    });
    return () => sub.remove();
  }, [stack.length, pop]);

  const top = stack[stack.length - 1];
  const api = useMemo<NavApi>(() => ({ route: top, push, replace, pop, reset, canGoBack: stack.length > 1 }), [top, push, replace, pop, reset, stack.length]);

  return (
    <NavContext.Provider value={api}>
      {stack.map((r, i) => {
        const Render = screens[r.name];
        const focused = i === stack.length - 1;
        return (
          <FocusContext.Provider key={r.key} value={focused}>
            <View style={focused ? { flex: 1 } : { display: 'none' }} accessibilityElementsHidden={!focused} importantForAccessibility={focused ? 'auto' : 'no-hide-descendants'}>
              {Render ? Render({ params: r.params ?? {} }) : null}
            </View>
          </FocusContext.Provider>
        );
      })}
    </NavContext.Provider>
  );
}
