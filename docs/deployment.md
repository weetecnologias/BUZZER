# Deployment & high availability

> Targets: 99.9 % monthly availability, RPO ≤ 5 min, RTO ≤ 30 min. These are **targets**, to be validated by real
> cloud configuration and failover/restore drills — not guarantees.

## Environments

`dev` (local, embedded DB, dev seed) → `staging` (production-like, test-mode providers) → `production`.
Every change goes through CI (`.github/workflows/ci.yml`: backend tests, admin build, mobile typecheck + web bundles)
on a feature branch and a pull request. Nobody pushes to `main` directly.

## Independently deployable frontends

| App | Build | Host |
|---|---|---|
| Admin panel | `npm run build` → Node server | Separate container/service behind its own route; health: `/api/health` |
| Agent app (web) | `expo export --platform web` | Static hosting + CDN; native via EAS Build |
| Customer / Partner apps | EAS Build → App Store / Play Store; OTA via EAS Update | — |

A frontend deploy never redeploys the backend or the other apps. The backend API is versioned (`/api/v1`) and changes
must stay backward compatible with the oldest supported mobile build.

## Backend

- Container: `backend/Dockerfile` (non-root, JRE 21). Reference manifests: `infra/k8s/backend.yaml` — 3+ replicas
  spread across zones, `maxUnavailable: 0` rolling updates gated by readiness, PDB, HPA, graceful shutdown.
- Load balancer health: `/actuator/health/readiness`; metrics: `/actuator/prometheus`.
- Blue-green/canary: deploy new ReplicaSet behind the same Service with a canary weight in the ingress/gateway;
  roll back with `kubectl rollout undo` if error rate or latency SLOs regress.
- **Migrations** run via Flyway at startup and must be backward compatible (expand → migrate → contract) so old and
  new versions can run side by side during a rollout.

## Data

- Managed PostgreSQL with a synchronous standby in a second AZ, automatic failover, encrypted storage, automated daily
  snapshots + continuous WAL archiving (PITR) to a separate, access-restricted backup account/bucket with retention lock.
- A replica is **not** a backup: restore drills are mandatory (see runbooks).
- Connection pooling (HikariCP, 20/instance); monitor connections, storage, replication lag.

## Edge

CDN + WAF (OWASP rules, rate limits on `/api/v1/auth/*`), TLS 1.2+, HSTS. API gateway / ingress terminates TLS and
forwards `X-Forwarded-*` (backend uses `forward-headers-strategy: framework`).

## Observability & alerting

Centralised JSON logs, Prometheus metrics, OpenTelemetry tracing (to add), alerts on: 5xx rate, p95 latency, DB
connections/replication lag, outbox backlog age and dead-letter count, payment failure rate, SLA breach count.
The Admin → System Health page summarises component state for operators.
