# Architecture

```
 Customer App   Partner App   Agent App        Admin Panel (Next.js BFF)
 (Expo)         (Expo)        (Expo + web)     httpOnly cookie session
      \             |             /                  |
       \            |            /                   |
        +---- HTTPS (JWT bearer) ----+----------------+
                         |
             CDN / WAF / Load balancer (multi-AZ)
                         |
        BUZZER backend — Spring Boot modular monolith (N stateless replicas)
        identity · location · catalogue · customer · agent · partner · request ·
        quotation · assignment · execution · payment · finance · notification · ops · health
                         |
     PostgreSQL (primary + standby, PITR)    Redis (planned)    Object storage (planned)
                         |
        Transactional outbox → dispatcher → SMS / Push / Email / WhatsApp adapters
        Payment provider adapter ← signed webhooks
```

## Principles

- **Modular monolith.** Each package (`com.buzzer.<module>`) owns its tables and exposes services; controllers never
  reach across modules' SQL except for read-only list views. Modules can be extracted later if scale demands.
- **Database is the source of truth for invariants.** Partial unique indexes enforce: one live assignment per
  request, one accepted quotation, one active primary/backup agent per customer, idempotency keys, webhook event
  uniqueness, and idempotent ledger postings.
- **Explicit SQL (JdbcClient) over ORM** for predictable locking (`SELECT … FOR UPDATE`, `SKIP LOCKED`) on
  concurrency-critical paths.
- **Never trust the client.** Totals, form validation, eligibility, state transitions, ownership and permissions are
  all re-checked server-side.
- **Fail safe, not silent.** Provider failures are recorded, retried with backoff, dead-lettered and surfaced on System
  Health; business records are written before any external call.
- **Honest integration status.** Test-mode adapters are labelled everywhere (API flags, UI banners, health page).

## Request lifecycle

`REQUEST_CREATED → AGENT_REVIEW → PARTNER_QUOTATION → CUSTOMER_APPROVAL → PAYMENT_PENDING → PAYMENT_CONFIRMED →
PARTNER_ASSIGNED → PARTNER_CONFIRMED → PARTNER_ARRIVED → SERVICE_STARTED → COMPLETION_PENDING → COMPLETED →
SETTLEMENT_PENDING → SETTLED`, plus `RESCHEDULING_REQUESTED, PARTNER_DECLINED, NO_SHOW, DISPUTED, CANCELLED,
REFUND_PENDING, REFUNDED, REJECTED, EXPIRED`. Allowed transitions live in `RequestStatus`; every change writes
`request_status_history` and `audit_events`. `GET /api/v1/admin/request-states` returns the live transition table.

## Security

OTP-only sign-in; JWT (HS256, 30 min) + rotating refresh tokens stored as SHA-256 hashes; per-app role gates; method
security; agents limited to their portfolio; partners only see their own leads/jobs/ledger (cross-tenant ids return
404); customer contact released to a partner only after booking confirmation; secrets from environment only; OTP and
numeric secrets redacted from logs; CSV formula-injection protection; admin tokens never reach browser JavaScript.
