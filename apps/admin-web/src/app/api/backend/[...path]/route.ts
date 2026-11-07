import { cookies } from "next/headers";
import { ACCESS_COOKIE, BACKEND_URL, jsonError, refreshSession } from "@/lib/server";

/** Only these backend areas are reachable through the admin BFF. */
const ALLOWED = [/^admin\//, /^catalogue\//, /^locations\//, /^notifications/, /^auth\/me$/, /^media\//, /^attachments\//];

async function forward(request: Request, path: string[]) {
  const joined = path.join("/");
  if (!ALLOWED.some((r) => r.test(joined)) || joined.includes("..")) return jsonError(404, "NOT_FOUND", "Unknown endpoint");
  const url = new URL(request.url);
  const target = `${BACKEND_URL}/api/v1/${joined}${url.search}`;
  // Forward the raw body and its content type, so JSON and multipart uploads (images, Excel) both pass through intact.
  const body = request.method === "GET" || request.method === "HEAD" ? undefined : await request.arrayBuffer();
  const contentType = request.headers.get("content-type");
  const jar = await cookies();
  let token = jar.get(ACCESS_COOKIE)?.value ?? null;

  const send = (t: string | null) =>
    fetch(target, {
      method: request.method,
      headers: {
        ...(body && contentType ? { "Content-Type": contentType } : {}),
        ...(t ? { Authorization: `Bearer ${t}` } : {}),
        Accept: request.headers.get("accept") ?? "application/json",
      },
      body,
      cache: "no-store",
    });

  try {
    let res = await send(token);
    if (res.status === 401) {
      token = await refreshSession();
      if (token) res = await send(token);
    }
    const headers = new Headers();
    for (const h of ["content-type", "content-disposition", "cache-control"]) {
      const v = res.headers.get(h);
      if (v) headers.set(h, v);
    }
    return new Response(await res.arrayBuffer(), { status: res.status, headers });
  } catch {
    return jsonError(503, "BACKEND_UNAVAILABLE", "BUZZER backend is unreachable. Other apps are unaffected.");
  }
}

type Ctx = { params: Promise<{ path: string[] }> };

export async function GET(request: Request, ctx: Ctx) {
  return forward(request, (await ctx.params).path);
}
export async function POST(request: Request, ctx: Ctx) {
  return forward(request, (await ctx.params).path);
}
export async function PUT(request: Request, ctx: Ctx) {
  return forward(request, (await ctx.params).path);
}
export async function PATCH(request: Request, ctx: Ctx) {
  return forward(request, (await ctx.params).path);
}
export async function DELETE(request: Request, ctx: Ctx) {
  return forward(request, (await ctx.params).path);
}
