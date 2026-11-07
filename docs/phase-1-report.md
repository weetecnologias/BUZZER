# Phase 1 report — foundation + core service flow

Branch: `feature/phase1-foundation` (not pushed to `main`).

## What was implemented

**Backend (Spring Boot modular monolith, PostgreSQL, Flyway)**

| Module | Implemented |
|---|---|
| Identity | Mobile OTP (6-digit, peppered hash, 5-min TTL, single use, 5 attempts, per-phone hourly limit in DB, per-IP limit), JWT access tokens (30 min), rotating revocable refresh tokens, per-app sign-in rules (staff apps refuse unknown numbers without account enumeration), RBAC with 8 roles + method-level permissions |
| Location | Multi-city + localities + pincodes; launching a city is configuration |
| Catalogue | 25 categories, 60 subcategories, menu items with 9 pricing types, auto "Other … Requirement" per subcategory, global Special Requests, dynamic JSON forms validated server-side, category/city availability, advisory notices (health/legal/financial/security), admin CRUD + reorder + deactivate |
| Customers | Profile, notification preferences, Home/Work/Other addresses with locality/pincode validation (soft delete) |
| Agents | Dedicated primary + backup allocation (city, locality, language, availability, workload, capacity; managers last), manager override / temporary agents, portfolio-scoped access, agent-created requests with consent method + timestamp + customer confirmation |
| Requests | Permanent IDs (`BZ-REQ-2026-00000001`), idempotent submission, configurable SLA per priority, first-response vs resolution tracking, 24-state lifecycle state machine with full history + audit |
| Partners | Individual/business registration, versioned terms acceptance (version + timestamp + IP + UA), KYC (masked numbers), registration / KYC / capability approval as separate states, service areas, availability, eligibility engine |
| Quotations | Invitations only to eligible partners, server-computed totals, validity, warranty/exclusions/cancellation terms, share with customer, clarify/reject |
| Assignment | Exclusive assignment (row lock + check + partial unique index), losing invitations closed, decline/cancel with mandatory reason, reassignment with new ID, configurable repeated-cancellation alert (no auto-suspension) |
| Execution | Booking IDs, arrival, 4-digit START/COMPLETE OTPs (hashed, 15-min, single use, attempt-limited), audited agent override when customer phone unavailable, reschedule propose/accept with original + revised times kept |
| Payments | Provider adapter interface, TEST MODE mock gateway, server-created orders, HMAC webhook verification, event de-duplication, idempotency keys, amount reconciliation check, failure keeps request, retry, withdraw acceptance, pay-on-delivery policy (global + service + quotation), receipts |
| Finance | Append-only idempotent ledger, commission rules with explicit precedence + effective dates + city scope, tax invoice on completion, partner payouts (T+N target), mark-paid with bank reference, refunds as PENDING → admin approval (never blanket auto-refund) |
| Notifications | In-app notifications, transactional outbox, multi-instance safe dispatcher (SKIP LOCKED), exponential backoff, dead-letter, TEST MODE SMS adapter with redaction |
| Operations | Exceptions with priority/owner/ageing/next action/dedupe; SLA breach + agent-unavailable escalation to backup → best available → manager; admin dashboard; partner coverage analytics; CSV export |
| System health | Per-component HEALTHY / DEGRADED / UNAVAILABLE / UNKNOWN (frontends probed, DB primary/replica, outbox, payment, SMS/email/WhatsApp/push, integrations, backups) — unprovisioned components report UNKNOWN, never green |

**Frontends**

- **Customer App** — OTP login; home with the personal agent card directly under the header (photo/initials, name,
  availability, call, chat, backup support), search, categories, active requests, popular services; contextual
  Category → Subcategory → Service navigation; dynamic request forms; addresses; request tracking with quotation
  comparison, accept & pay (online / pay on delivery), TEST MODE payment simulation, retry, start/completion OTP display,
  reschedule response, cancel with reason, review, invoices, messages with agent, timeline; notifications; profile.
- **Partner App** — registration (type, services, service areas, terms), dashboard with verification status and
  ledger-based earnings, availability toggle, leads with quotation builder (server-computed total), decline, jobs
  (confirm/decline/arrive/start OTP/request completion/complete OTP/cancel with reason/propose reschedule), earnings
  ledger + payouts, KYC submission, request additional service capability.
- **Agent App** — dashboard (counts, SLA breaches, 30-day performance), availability (going offline hands requests to
  backup), inbox ordered by SLA, request workspace (first response, eligible partners, invite, compare and share
  quotes, reassign, OTP override, messages + call log, audit timeline), customer portfolio, register customer with
  consent, create request on behalf with dynamic form.
- **Admin Panel** — OTP login via BFF (tokens in httpOnly cookies), dashboard, requests (filter, export, full detail
  with ledger + audit), catalogue editor (tree, reorder, activate/deactivate, JSON form editor), partners & KYC &
  capability approvals, coverage, customers + agent reassignment, agents, finance (commission rules, payouts, refunds,
  payments, category report CSV), exceptions, settings + SLA + city launch, system health.

## Database migrations

- `V1__core_schema.sql` — all Phase 1 tables, sequences, partial unique indexes (one live assignment per request,
  one accepted quote, one active primary/backup agent, idempotency keys, webhook event uniqueness), default settings,
  SLA rules, default commission.
- `V2__reference_catalogue.sql` — Hyderabad + 16 localities, 25 categories, subcategories, services, forms,
  illustrative commission rules, versioned draft terms.
- `db/devseed/V1000__dev_seed.sql` — dev/test-only staff and partners (never loaded in production).

## APIs

OpenAPI at `/v3/api-docs` and `/swagger-ui.html`. Groups: `/api/v1/auth`, `/catalogue`, `/locations`, `/customer`,
`/agent`, `/partner`, `/notifications`, `/payments/webhooks/{provider}`, `/admin/**` (dashboard, catalogue, partners,
agents, customers, requests, exceptions, settings, sla-rules, finance, system-health).

## Tests

`mvn test` — **25 passed, 0 failed** (embedded PostgreSQL 17):

| Suite | Covers |
|---|---|
| AuthAndAccessTest (7) | Registration + agent allocation, wrong-OTP lockout, single-use OTP, staff-app number gating, RBAC 401/403, refresh rotation + reuse rejection, agent portfolio isolation |
| CatalogueAndFormsTest (4) | 25 categories, contextual plumbing menu, server-side form validation + hierarchy mismatch, admin adds category/subcategory without deploy, bad schema rejected |
| EndToEndServiceFlowTest (1) | Acceptance scenarios 1–6 end to end, incl. idempotent submit, eligibility, cross-partner lead isolation, server totals, payment, exactly one assignment, OTPs, 5% plumbing commission, payout, settlement, review |
| ExclusiveAssignmentConcurrencyTest (3) | 12 concurrent assigners → exactly 1 winner; stale acceptance → controlled error; decline + reassignment with new ID and preserved history |
| ResilienceAndOperationsTest (10) | Payment failure keeps request + retry; webhook signature + duplicates; amount mismatch; POD policy; SLA escalation to backup (scenario 7); agent offline escalation; repeated cancellation alert w/ dedupe, no auto-suspend (scenario 8); dead-letter on failing channel (scenario 11); commission precedence; cancellation → pending refund → approval |

Frontends: TypeScript strict typecheck passes for all three mobile apps; all three export as web bundles; the admin
panel production build passes. Customer App and Admin Panel were exercised in a browser against the live backend
(OTP login, agent card, category navigation, dynamic form, dashboard, system health). There are **no automated UI /
end-to-end browser tests yet**.

## Integrations in TEST MODE

| Integration | State |
|---|---|
| Payment gateway | `MockPaymentProvider` (signed webhooks, flagged `test_mode`) — Razorpay/Cashfree/PayU adapter not written until credentials exist |
| SMS | `LoggingSmsSender` — logs redacted messages |
| Push (FCM) | Not configured — in-app notifications only |
| Email, WhatsApp | Not configured — events dead-letter and show on System Health |
| Maps / geolocation | Not configured — manual address + locality + pincode |
| Object storage (photos, KYC files) | Not configured — forms show an honest notice; KYC stores masked numbers only |
| Recharge / travel | Not started (separate Digital Transaction Engine — Phase 4) |

## Credentials / approvals still required

Payment gateway merchant account + keys + webhook secret; SMS provider (DLT-registered sender + templates);
Firebase project for FCM; WhatsApp Business API provider; Google Maps key; S3-compatible bucket + KMS key;
cloud account (managed PostgreSQL HA, Redis, load balancer, WAF, CDN); legal review of partner/customer terms,
pricing guardrails and privacy notices; tax (GST/TDS) review.

## Known limitations / remaining work

1. **Phase 2:** partner menu & price lists (fixed/range/per-unit…), Excel template download/upload with row-level
   validation, versioned price lists; partner subscriptions (Free/Basic/Premium/Custom), lead charges.
2. **Phase 2:** customer memberships, coupons, referrals, campaigns and eligibility checks (scenario 9).
3. **Phase 3:** Quick Jobs — employer verification, vacancies, candidate profiles, applications (scenario 10).
4. **Phase 3:** file uploads to private object storage with signed URLs; KYC document images; request photos.
5. **Phase 3:** real provider adapters (payments, SMS, FCM, email, WhatsApp, maps) once credentials exist.
6. **Phase 4:** Digital Transaction Engine (recharge, bills, FASTag, tickets) via the Integration Hub.
7. Redis-backed rate limiting/caching for multi-instance deployments (current per-IP limiter is per instance; per-phone
   OTP limits are already DB-backed and cluster-safe).
8. Automated end-to-end UI tests (Playwright for admin web; Maestro/Detox for mobile), load and resilience tests,
   failover and restore drills (scenarios 12–15 need real cloud infrastructure — see `docs/deployment.md`).
9. No-show handling and dispute resolution UI for admins (states exist in the state machine).
10. Request state machine is defined in code (documented, tested); per-category workflow configuration in DB is future work.
