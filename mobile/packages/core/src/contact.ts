import { Alert, Linking, Platform } from 'react-native';

/** Keeps only a leading + and digits, e.g. "+91 90000 00011" -> "+919000000011". */
export function dialable(phone: string): string {
  const trimmed = String(phone ?? '').trim();
  return (trimmed.startsWith('+') ? '+' : '') + trimmed.replace(/\D/g, '');
}

function showNumber(title: string, number: string) {
  if (Platform.OS === 'web') {
    globalThis.alert?.(`${title}\n${number}`);
  } else {
    Alert.alert(title, number);
  }
}

/**
 * Opens the phone dialler pre-filled with the number (the user still taps the green call button).
 * - iOS/Android: tel: URL hands off to the system dialler.
 * - Web (mobile browsers, desktop with a calling app): navigate the current window to tel:, which is what
 *   browsers expect; window.open('tel:') used by Linking on web is silently ignored by most browsers.
 * If nothing can handle it, the number is shown so it can be dialled manually.
 */
export async function callPhone(phone: string | null | undefined) {
  const number = dialable(phone ?? '');
  if (number.replace('+', '').length < 6) {
    showNumber('Phone number unavailable', 'Please try again later.');
    return;
  }
  const url = `tel:${number}`;
  if (Platform.OS === 'web') {
    try {
      globalThis.location.href = url;
    } catch {
      showNumber('Call this number', number);
    }
    return;
  }
  try {
    await Linking.openURL(url);
  } catch {
    showNumber('Call this number', number);
  }
}

/** Opens a WhatsApp chat with the number (app if installed, otherwise WhatsApp Web). */
export async function openWhatsApp(phone: string | null | undefined) {
  const digits = dialable(phone ?? '').replace('+', '');
  if (digits.length < 6) return;
  const url = `https://wa.me/${digits}`;
  if (Platform.OS === 'web') {
    globalThis.open?.(url, '_blank', 'noopener');
    return;
  }
  await Linking.openURL(url).catch(() => showNumber('WhatsApp', `+${digits}`));
}
