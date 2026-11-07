import { Platform } from 'react-native';
import { storage } from './storage';

export type AppType = 'CUSTOMER' | 'PARTNER' | 'AGENT';

/** Backend base URL. Set EXPO_PUBLIC_API_URL for devices; Android emulator reaches the host via 10.0.2.2. */
export const API_URL: string =
  process.env.EXPO_PUBLIC_API_URL ?? (Platform.OS === 'android' ? 'http://10.0.2.2:8080' : 'http://localhost:8080');

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public details: Record<string, unknown> = {},
  ) {
    super(message);
  }

  /** Field-level validation messages from the server, if any. */
  get fieldErrors(): Record<string, string> {
    const f = (this.details as { fields?: Record<string, string> }).fields;
    return f ?? {};
  }
}

type Tokens = { access_token: string; refresh_token: string };

const ACCESS = 'bz_access';
const REFRESH = 'bz_refresh';

export function newIdempotencyKey(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
}

class Client {
  private access: string | null = null;
  private refreshing: Promise<boolean> | null = null;
  onSessionExpired: (() => void) | null = null;

  async load(): Promise<boolean> {
    this.access = await storage.get(ACCESS);
    return !!this.access || !!(await storage.get(REFRESH));
  }

  async setTokens(t: Tokens) {
    this.access = t.access_token;
    await storage.set(ACCESS, t.access_token);
    await storage.set(REFRESH, t.refresh_token);
  }

  async clear() {
    this.access = null;
    await storage.remove(ACCESS);
    await storage.remove(REFRESH);
  }

  async refresh(): Promise<boolean> {
    if (this.refreshing) return this.refreshing;
    this.refreshing = (async () => {
      const rt = await storage.get(REFRESH);
      if (!rt) return false;
      try {
        const res = await fetch(`${API_URL}/api/v1/auth/refresh`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refresh_token: rt }),
        });
        if (!res.ok) return false;
        const body = await res.json();
        await this.setTokens(body.data);
        return true;
      } catch {
        return false;
      }
    })();
    try {
      return await this.refreshing;
    } finally {
      this.refreshing = null;
    }
  }

  async request<T>(method: string, path: string, body?: unknown, opts: { idempotencyKey?: string; auth?: boolean } = {}): Promise<T> {
    const doFetch = async () => {
      const headers: Record<string, string> = { Accept: 'application/json' };
      if (body !== undefined) headers['Content-Type'] = 'application/json';
      if (opts.auth !== false && this.access) headers.Authorization = `Bearer ${this.access}`;
      if (opts.idempotencyKey) headers['Idempotency-Key'] = opts.idempotencyKey;
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 20000);
      try {
        return await fetch(`${API_URL}${path}`, {
          method,
          headers,
          body: body === undefined ? undefined : JSON.stringify(body),
          signal: controller.signal,
        });
      } finally {
        clearTimeout(timer);
      }
    };

    let res: Response;
    try {
      res = await doFetch();
      if (res.status === 401 && opts.auth !== false && (await this.refresh())) {
        res = await doFetch();
      }
    } catch (e) {
      throw new ApiError(0, 'NETWORK', 'Could not reach BUZZER. Check your connection and try again.');
    }
    if (res.status === 401 && opts.auth !== false) {
      await this.clear();
      this.onSessionExpired?.();
    }
    const text = await res.text();
    const json = text ? JSON.parse(text) : {};
    if (!res.ok) {
      const err = json.error ?? {};
      throw new ApiError(res.status, err.code ?? 'ERROR', err.message ?? 'Something went wrong', err.details ?? {});
    }
    return json.data as T;
  }

  /** Multipart upload (e.g. request attachments). Retries once after refreshing an expired session. */
  async upload<T>(path: string, form: FormData): Promise<T> {
    const send = () => fetch(`${API_URL}${path}`, {
      method: 'POST',
      headers: { Accept: 'application/json', ...(this.access ? { Authorization: `Bearer ${this.access}` } : {}) },
      body: form,
    });
    let res: Response;
    try {
      res = await send();
      if (res.status === 401 && (await this.refresh())) res = await send();
    } catch {
      throw new ApiError(0, 'NETWORK', 'Could not reach BUZZER. Check your connection and try again.');
    }
    const text = await res.text();
    const json = text ? JSON.parse(text) : {};
    if (!res.ok) {
      const err = json.error ?? {};
      throw new ApiError(res.status, err.code ?? 'ERROR', err.message ?? 'Upload failed', err.details ?? {});
    }
    return json.data as T;
  }

  get<T>(path: string) {
    return this.request<T>('GET', path);
  }

  post<T>(path: string, body: unknown = {}, idempotencyKey?: string) {
    return this.request<T>('POST', path, body, { idempotencyKey });
  }

  put<T>(path: string, body: unknown) {
    return this.request<T>('PUT', path, body);
  }

  del<T>(path: string) {
    return this.request<T>('DELETE', path);
  }
}

export const api = new Client();
