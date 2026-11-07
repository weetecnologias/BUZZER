import React, { ReactNode } from 'react';
import {
  ActivityIndicator,
  Modal,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleProp,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  TextInputProps,
  View,
  ViewStyle,
} from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { SafeAreaView } from 'react-native-safe-area-context';
import { colors, font, gradient, radius, shadow, space } from './theme';
import { statusLabel, statusTone, Tone } from './format';

// ----- Layout --------------------------------------------------------------------------------------

export function Screen({ children, scroll = true, refreshing, onRefresh, footer, padded = true }: {
  children: ReactNode;
  scroll?: boolean;
  refreshing?: boolean;
  onRefresh?: () => void;
  footer?: ReactNode;
  padded?: boolean;
}) {
  const content = padded ? <View style={styles.padded}>{children}</View> : children;
  return (
    <SafeAreaView style={styles.screen} edges={['left', 'right', 'bottom']}>
      {scroll ? (
        <ScrollView
          contentContainerStyle={{ paddingBottom: space.xxl }}
          keyboardShouldPersistTaps="handled"
          refreshControl={onRefresh ? <RefreshControl refreshing={!!refreshing} onRefresh={onRefresh} tintColor={colors.emerald} /> : undefined}
        >
          {content}
        </ScrollView>
      ) : (
        <View style={{ flex: 1 }}>{content}</View>
      )}
      {footer}
    </SafeAreaView>
  );
}

export function GradientHeader({ title, subtitle, onBack, right, children }: {
  title: string;
  subtitle?: string;
  onBack?: () => void;
  right?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <LinearGradient colors={gradient.header} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={styles.header}>
      <SafeAreaView edges={['top']}>
        <View style={styles.headerRow}>
          {onBack ? (
            <Pressable accessibilityRole="button" accessibilityLabel="Go back" onPress={onBack} hitSlop={12} style={styles.backBtn}>
              <Text style={styles.backText}>‹</Text>
            </Pressable>
          ) : null}
          <View style={{ flex: 1 }}>
            <Text style={styles.headerTitle} numberOfLines={1}>{title}</Text>
            {subtitle ? <Text style={styles.headerSubtitle} numberOfLines={1}>{subtitle}</Text> : null}
          </View>
          {right}
        </View>
        {children}
      </SafeAreaView>
    </LinearGradient>
  );
}

export function Card({ children, style, onPress, accessibilityLabel }: {
  children: ReactNode;
  style?: StyleProp<ViewStyle>;
  onPress?: () => void;
  accessibilityLabel?: string;
}) {
  if (onPress) {
    return (
      <Pressable accessibilityRole="button" accessibilityLabel={accessibilityLabel} onPress={onPress}
        style={({ pressed }) => [styles.card, style, pressed && { opacity: 0.85 }]}>
        {children}
      </Pressable>
    );
  }
  return <View style={[styles.card, style]}>{children}</View>;
}

export function SectionTitle({ title, action, onAction }: { title: string; action?: string; onAction?: () => void }) {
  return (
    <View style={styles.sectionRow}>
      <Text style={styles.sectionTitle}>{title}</Text>
      {action ? (
        <Pressable onPress={onAction} hitSlop={8} accessibilityRole="button">
          <Text style={styles.sectionAction}>{action}</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

export function Row({ children, style }: { children: ReactNode; style?: StyleProp<ViewStyle> }) {
  return <View style={[{ flexDirection: 'row', alignItems: 'center', gap: space.sm }, style]}>{children}</View>;
}

export function Label({ children }: { children: ReactNode }) {
  return <Text style={styles.label}>{children}</Text>;
}

export function Body({ children, muted, bold, style }: { children: ReactNode; muted?: boolean; bold?: boolean; style?: StyleProp<any> }) {
  return <Text style={[styles.body, muted && { color: colors.textSecondary }, bold && { fontWeight: '700' }, style]}>{children}</Text>;
}

export function KeyValue({ k, v }: { k: string; v: ReactNode }) {
  return (
    <View style={styles.kv}>
      <Text style={styles.kvKey}>{k}</Text>
      <Text style={styles.kvVal}>{v}</Text>
    </View>
  );
}

// ----- Controls ------------------------------------------------------------------------------------

export function Button({ title, onPress, variant = 'primary', loading, disabled, style, small }: {
  title: string;
  onPress: () => void;
  variant?: 'primary' | 'secondary' | 'outline' | 'danger' | 'ghost';
  loading?: boolean;
  disabled?: boolean;
  style?: StyleProp<ViewStyle>;
  small?: boolean;
}) {
  const isDisabled = disabled || loading;
  const textColor = variant === 'primary' || variant === 'danger' ? colors.white : variant === 'ghost' ? colors.emerald : colors.forest;
  const inner = loading ? <ActivityIndicator color={textColor} /> : <Text style={[styles.btnText, { color: textColor }, small && { fontSize: font.sm }]}>{title}</Text>;
  const base = [styles.btn, small && styles.btnSmall, isDisabled && { opacity: 0.5 }, style];
  if (variant === 'primary') {
    return (
      <Pressable accessibilityRole="button" accessibilityState={{ disabled: !!isDisabled }} disabled={isDisabled} onPress={onPress}
        style={({ pressed }) => [{ borderRadius: radius.md, overflow: 'hidden' }, pressed && { opacity: 0.9 }, style]}>
        <LinearGradient colors={gradient.primary} start={{ x: 0, y: 0 }} end={{ x: 1, y: 0 }} style={[styles.btn, small && styles.btnSmall, isDisabled && { opacity: 0.5 }]}>
          {inner}
        </LinearGradient>
      </Pressable>
    );
  }
  const bg = variant === 'secondary' ? colors.lightGreen : variant === 'danger' ? colors.danger : 'transparent';
  const border = variant === 'outline' ? { borderWidth: 1.5, borderColor: colors.emerald } : {};
  return (
    <Pressable accessibilityRole="button" accessibilityState={{ disabled: !!isDisabled }} disabled={isDisabled} onPress={onPress}
      style={({ pressed }) => [...base, { backgroundColor: bg }, border, pressed && { opacity: 0.85 }]}>
      {inner}
    </Pressable>
  );
}

export function TextField({ label, error, hint, ...props }: TextInputProps & { label?: string; error?: string; hint?: string }) {
  return (
    <View style={{ marginBottom: space.md }}>
      {label ? <Text style={styles.fieldLabel}>{label}</Text> : null}
      <TextInput
        placeholderTextColor={colors.textSecondary}
        accessibilityLabel={label}
        {...props}
        style={[styles.input, props.multiline && { minHeight: 96, textAlignVertical: 'top' }, error ? { borderColor: colors.danger } : null, props.style]}
      />
      {error ? <Text style={styles.fieldError}>{error}</Text> : hint ? <Text style={styles.fieldHint}>{hint}</Text> : null}
    </View>
  );
}

export function Chip({ label, selected, onPress }: { label: string; selected?: boolean; onPress?: () => void }) {
  return (
    <Pressable accessibilityRole="button" accessibilityState={{ selected: !!selected }} onPress={onPress}
      style={[styles.chip, selected && { backgroundColor: colors.emerald, borderColor: colors.emerald }]}>
      <Text style={[styles.chipText, selected && { color: colors.white }]}>{label}</Text>
    </Pressable>
  );
}

export function Toggle({ label, value, onChange }: { label: string; value: boolean; onChange: (v: boolean) => void }) {
  return (
    <View style={[styles.kv, { alignItems: 'center' }]}>
      <Text style={styles.body}>{label}</Text>
      <Switch value={value} onValueChange={onChange} trackColor={{ true: colors.secondary, false: colors.border }} thumbColor={colors.white} />
    </View>
  );
}

const TONES: Record<Tone, { bg: string; fg: string }> = {
  success: { bg: colors.lightGreen, fg: colors.emerald },
  warning: { bg: colors.warningBg, fg: colors.warning },
  danger: { bg: colors.dangerBg, fg: colors.danger },
  info: { bg: colors.paleMint, fg: colors.forest },
  neutral: { bg: '#EEF2EF', fg: colors.textSecondary },
};

export function Badge({ label, tone = 'neutral' }: { label: string; tone?: Tone }) {
  const t = TONES[tone];
  return (
    <View style={[styles.badge, { backgroundColor: t.bg }]}>
      <Text style={[styles.badgeText, { color: t.fg }]}>{label}</Text>
    </View>
  );
}

export function StatusBadge({ status }: { status: string }) {
  return <Badge label={statusLabel(status)} tone={statusTone(status)} />;
}

export function Avatar({ name, size = 44 }: { name?: string | null; size?: number }) {
  const initials = (name ?? '?').split(' ').filter(Boolean).slice(0, 2).map((p) => p[0]?.toUpperCase()).join('');
  return (
    <LinearGradient colors={gradient.primary} style={{ width: size, height: size, borderRadius: size / 2, alignItems: 'center', justifyContent: 'center' }}>
      <Text style={{ color: colors.white, fontWeight: '700', fontSize: size * 0.36 }}>{initials || '?'}</Text>
    </LinearGradient>
  );
}

export function IconTile({ glyph, label, onPress, sublabel }: { glyph: string; label: string; onPress?: () => void; sublabel?: string }) {
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={label} onPress={onPress} style={({ pressed }) => [styles.tile, pressed && { opacity: 0.8 }]}>
      <View style={styles.tileIcon}><Text style={{ fontSize: 22 }}>{glyph}</Text></View>
      <Text style={styles.tileLabel} numberOfLines={2}>{label}</Text>
      {sublabel ? <Text style={styles.tileSub} numberOfLines={1}>{sublabel}</Text> : null}
    </Pressable>
  );
}

// ----- States --------------------------------------------------------------------------------------

export function Loading({ label = 'Loading…' }: { label?: string }) {
  return (
    <View style={styles.center}>
      <ActivityIndicator size="large" color={colors.emerald} />
      <Text style={[styles.body, { marginTop: space.sm, color: colors.textSecondary }]}>{label}</Text>
    </View>
  );
}

export function EmptyState({ title, message, action, onAction }: { title: string; message?: string; action?: string; onAction?: () => void }) {
  return (
    <View style={styles.empty}>
      <View style={styles.emptyIcon}><Text style={{ fontSize: 28 }}>🌿</Text></View>
      <Text style={styles.emptyTitle}>{title}</Text>
      {message ? <Text style={[styles.body, { textAlign: 'center', color: colors.textSecondary }]}>{message}</Text> : null}
      {action && onAction ? <Button title={action} onPress={onAction} style={{ marginTop: space.lg, alignSelf: 'stretch' }} /> : null}
    </View>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <View style={[styles.banner, { backgroundColor: colors.dangerBg, borderColor: '#F5C2C2' }]} accessibilityRole="alert">
      <Text style={{ color: colors.danger, fontWeight: '600', marginBottom: onRetry ? space.sm : 0 }}>{message}</Text>
      {onRetry ? <Button title="Try again" variant="outline" small onPress={onRetry} /> : null}
    </View>
  );
}

export function Notice({ message, tone = 'info' }: { message: string; tone?: Tone }) {
  const t = TONES[tone];
  return (
    <View style={[styles.banner, { backgroundColor: t.bg, borderColor: t.bg }]}>
      <Text style={{ color: t.fg, fontWeight: '600' }}>{message}</Text>
    </View>
  );
}

export function ConfirmDialog({ visible, title, message, confirmLabel = 'Confirm', danger, onConfirm, onCancel, children, loading }: {
  visible: boolean;
  title: string;
  message?: string;
  confirmLabel?: string;
  danger?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
  children?: ReactNode;
  loading?: boolean;
}) {
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onCancel}>
      <View style={styles.overlay}>
        <View style={styles.dialog}>
          <Text style={styles.dialogTitle}>{title}</Text>
          {message ? <Text style={[styles.body, { marginBottom: space.md }]}>{message}</Text> : null}
          {children}
          <Row style={{ marginTop: space.md }}>
            <Button title="Cancel" variant="outline" onPress={onCancel} style={{ flex: 1 }} />
            <Button title={confirmLabel} variant={danger ? 'danger' : 'primary'} onPress={onConfirm} loading={loading} style={{ flex: 1 }} />
          </Row>
        </View>
      </View>
    </Modal>
  );
}

export type Tab = { key: string; label: string; glyph: string };

/** Floating pill navigation: round icon buttons; the active tab expands into a green pill with its label. */
export function BottomTabs({ tabs, active, onChange }: { tabs: Tab[]; active: string; onChange: (k: string) => void }) {
  return (
    <SafeAreaView edges={['bottom']} style={styles.tabsWrap}>
      <View style={styles.tabsPill} accessibilityRole="tablist">
        {tabs.map((t) => {
          const on = t.key === active;
          return (
            <Pressable key={t.key} accessibilityRole="tab" accessibilityLabel={t.label} accessibilityState={{ selected: on }} onPress={() => onChange(t.key)}
              style={({ pressed }) => [styles.tabBtn, on && styles.tabBtnOn, pressed && { opacity: 0.85 }]}>
              <Text style={{ fontSize: 19, opacity: on ? 1 : 0.7 }}>{t.glyph}</Text>
              {on ? <Text style={styles.tabLabelOn} numberOfLines={1}>{t.label}</Text> : null}
            </Pressable>
          );
        })}
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.paleMint },
  padded: { padding: space.lg, gap: space.md, width: '100%', maxWidth: 760, alignSelf: 'center' },
  header: { paddingHorizontal: space.lg, paddingBottom: space.lg, borderBottomLeftRadius: radius.xl, borderBottomRightRadius: radius.xl },
  headerRow: { flexDirection: 'row', alignItems: 'center', gap: space.md, paddingTop: space.md, width: '100%', maxWidth: 760, alignSelf: 'center' },
  headerTitle: { color: colors.white, fontSize: font.xl, fontWeight: '700' },
  headerSubtitle: { color: '#CFEBD9', fontSize: font.sm, marginTop: 2 },
  backBtn: { width: 36, height: 36, borderRadius: 18, backgroundColor: 'rgba(255,255,255,0.18)', alignItems: 'center', justifyContent: 'center' },
  backText: { color: colors.white, fontSize: 26, lineHeight: 28, marginTop: -2 },
  card: { backgroundColor: colors.white, borderRadius: radius.lg, padding: space.lg, borderWidth: 1, borderColor: colors.border, ...shadow.card },
  sectionRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: space.sm },
  sectionTitle: { fontSize: font.lg, fontWeight: '700', color: colors.text },
  sectionAction: { color: colors.emerald, fontWeight: '600' },
  label: { fontSize: font.xs, color: colors.textSecondary, textTransform: 'uppercase', letterSpacing: 0.6, fontWeight: '600' },
  body: { fontSize: font.md, color: colors.text, lineHeight: 21 },
  kv: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 6, gap: space.md },
  kvKey: { color: colors.textSecondary, fontSize: font.sm, flexShrink: 0 },
  kvVal: { color: colors.text, fontSize: font.sm, fontWeight: '600', flex: 1, textAlign: 'right' },
  btn: { minHeight: 48, paddingHorizontal: space.lg, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  btnSmall: { minHeight: 36, paddingHorizontal: space.md },
  btnText: { fontSize: font.md, fontWeight: '700' },
  fieldLabel: { fontSize: font.sm, color: colors.text, fontWeight: '600', marginBottom: 6 },
  input: { borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, paddingHorizontal: space.md, paddingVertical: 12, fontSize: font.md, color: colors.text, backgroundColor: colors.white },
  fieldError: { color: colors.danger, fontSize: font.xs, marginTop: 4 },
  fieldHint: { color: colors.textSecondary, fontSize: font.xs, marginTop: 4 },
  chip: { paddingHorizontal: 14, paddingVertical: 8, borderRadius: radius.pill, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.white },
  chipText: { color: colors.text, fontSize: font.sm, fontWeight: '600' },
  badge: { paddingHorizontal: 10, paddingVertical: 4, borderRadius: radius.pill, alignSelf: 'flex-start' },
  badgeText: { fontSize: font.xs, fontWeight: '700' },
  tile: { width: '31%', backgroundColor: colors.white, borderRadius: radius.lg, paddingVertical: space.md, paddingHorizontal: space.sm, alignItems: 'center', borderWidth: 1, borderColor: colors.border, ...shadow.card },
  tileIcon: { width: 48, height: 48, borderRadius: radius.md, backgroundColor: colors.lightGreen, alignItems: 'center', justifyContent: 'center', marginBottom: 6 },
  tileLabel: { fontSize: font.xs, fontWeight: '600', color: colors.text, textAlign: 'center' },
  tileSub: { fontSize: 11, color: colors.textSecondary },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: space.xxl },
  empty: { alignItems: 'center', padding: space.xl, gap: space.sm },
  emptyIcon: { width: 64, height: 64, borderRadius: 32, backgroundColor: colors.lightGreen, alignItems: 'center', justifyContent: 'center' },
  emptyTitle: { fontSize: font.lg, fontWeight: '700', color: colors.text },
  banner: { borderRadius: radius.md, padding: space.md, borderWidth: 1 },
  overlay: { flex: 1, backgroundColor: colors.overlay, justifyContent: 'center', padding: space.xl },
  dialog: { backgroundColor: colors.white, borderRadius: radius.xl, padding: space.xl, width: '100%', maxWidth: 480, alignSelf: 'center' },
  dialogTitle: { fontSize: font.xl, fontWeight: '700', color: colors.text, marginBottom: space.sm },
  tabsWrap: { backgroundColor: colors.paleMint, paddingHorizontal: space.lg, paddingTop: 6, paddingBottom: 6 },
  tabsPill: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', alignSelf: 'center', width: '100%', maxWidth: 440,
    backgroundColor: colors.white, borderRadius: radius.pill, padding: 6, borderWidth: 1, borderColor: colors.border, ...shadow.raised },
  tabBtn: { height: 50, minWidth: 50, borderRadius: 25, alignItems: 'center', justifyContent: 'center', flexDirection: 'row', gap: 6,
    backgroundColor: colors.paleMint },
  tabBtnOn: { backgroundColor: colors.emerald, paddingHorizontal: 16 },
  tabLabelOn: { color: colors.white, fontWeight: '700', fontSize: font.sm },
});
