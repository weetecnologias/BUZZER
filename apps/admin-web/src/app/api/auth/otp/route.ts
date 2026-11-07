import { BACKEND_URL, jsonError } from "@/lib/server";

export async function POST(request: Request) {
  const { phone } = await request.json().catch(() => ({ phone: null }));
  if (!phone) return jsonError(400, "VALIDATION_FAILED", "Phone number is required");
  try {
    const res = await fetch(`${BACKEND_URL}/api/v1/auth/otp/request`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Forwarded-For": request.headers.get("x-forwarded-for") ?? "" },
      body: JSON.stringify({ phone, app: "ADMIN" }),
      cache: "no-store",
    });
    return new Response(await res.text(), { status: res.status, headers: { "Content-Type": "application/json" } });
  } catch {
    return jsonError(503, "BACKEND_UNAVAILABLE", "BUZZER backend is unreachable");
  }
}
