import { cookies } from "next/headers";

/**
 * Backend-for-frontend helpers. The admin panel never exposes tokens to browser JavaScript:
 * access/refresh tokens live in httpOnly, SameSite=Strict cookies and every API call is proxied
 * server-side with the Authorization header attached.
 */
export const BACKEND_URL = process.env.BUZZER_BACKEND_URL ?? "http://localhost:8080";
export const ACCESS_COOKIE = "bz_admin_at";
export const REFRESH_COOKIE = "bz_admin_rt";

const secure = process.env.NODE_ENV === "production";

export async function setSession(tokens: { access_token: string; refresh_token: string; refresh_token_expires_at?: string }) {
  const jar = await cookies();
  jar.set(ACCESS_COOKIE, tokens.access_token, { httpOnly: true, sameSite: "strict", secure, path: "/", maxAge: 60 * 60 });
  jar.set(REFRESH_COOKIE, tokens.refresh_token, { httpOnly: true, sameSite: "strict", secure, path: "/api", maxAge: 60 * 60 * 24 * 7 });
}

export async function clearSession() {
  const jar = await cookies();
  jar.delete(ACCESS_COOKIE);
  jar.delete({ name: REFRESH_COOKIE, path: "/api" });
}

export async function refreshSession(): Promise<string | null> {
  const jar = await cookies();
  const rt = jar.get(REFRESH_COOKIE)?.value;
  if (!rt) return null;
  const res = await fetch(`${BACKEND_URL}/api/v1/auth/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refresh_token: rt }),
    cache: "no-store",
  });
  if (!res.ok) return null;
  const body = await res.json();
  await setSession(body.data);
  return body.data.access_token as string;
}

export function jsonError(status: number, code: string, message: string) {
  return Response.json({ error: { code, message, details: {} } }, { status });
}
