import { cookies } from "next/headers";
import { BACKEND_URL, clearSession, REFRESH_COOKIE } from "@/lib/server";

export async function POST() {
  const jar = await cookies();
  const rt = jar.get(REFRESH_COOKIE)?.value;
  if (rt) {
    await fetch(`${BACKEND_URL}/api/v1/auth/logout`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refresh_token: rt }),
      cache: "no-store",
    }).catch(() => undefined);
  }
  await clearSession();
  return Response.json({ data: { logged_out: true } });
}
