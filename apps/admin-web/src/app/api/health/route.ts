/** Liveness endpoint for load balancers and the backend's System Health probe. */
export function GET() {
  return Response.json({ status: "UP", app: "admin-web" });
}
