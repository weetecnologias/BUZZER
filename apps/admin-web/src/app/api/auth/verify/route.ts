import { BACKEND_URL, jsonError, setSession } from "@/lib/server";

export async function POST(request: Request) {
  const { phone, code } = await request.json().catch(() => ({}));
  if (!phone || !code) return jsonError(400, "VALIDATION_FAILED", "Phone and OTP are required");
  try {
    const res = await fetch(`${BACKEND_URL}/api/v1/auth/otp/verify`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "User-Agent": "buzzer-admin-web" },
      body: JSON.stringify({ phone, code, app: "ADMIN" }),
      cache: "no-store",
    });
    const body = await res.json();
    if (!res.ok) return Response.json(body, { status: res.status });
    await setSession(body.data.tokens);
    // Tokens are never returned to the browser.
    return Response.json({ data: { user_id: body.data.user_id, full_name: body.data.full_name, roles: body.data.roles } });
  } catch {
    return jsonError(503, "BACKEND_UNAVAILABLE", "BUZZER backend is unreachable");
  }
}
