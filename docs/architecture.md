# Architecture

How BUZZER is put together, explained simply first and then in detail.
Related: [Technology stack](technology-stack.md) · [Developer guide](developer-guide.md) · [Business overview](business-overview.md) · [Deployment](deployment.md)

---

## 1. The big picture (in one sentence)

**Four front-end apps talk to one backend over HTTPS; the backend keeps everything in one PostgreSQL database and
talks to outside services (payments, SMS, WhatsApp…) through replaceable adapters.**

```mermaid
flowchart TB
    subgraph Clients["Front-end apps (built and deployed separately)"]
        CA["Customer App<br/>Expo / React Native<br/>Android · iOS · Web"]
        PA["Partner App<br/>Expo / React Native"]
        AA["Agent App<br/>Expo / React Native<br/>mobile + desktop web"]
        AW["Admin Panel<br/>Next.js web"]
    end

    AW -->|"browser → same-origin /api/backend/*<br/>(httpOnly cookie session)"| BFF["Admin BFF<br/>Next.js server routes"]
    CA & PA & AA -->|"HTTPS + JWT bearer token"| LB
    BFF -->|"HTTPS + JWT"| LB["Load balancer / WAF / CDN"]

    LB --> API["BUZZER Backend<br/>Spring Boot modular monolith<br/>(N identical stateless instances)"]

    API --> DB[("PostgreSQL<br/>primary + standby<br/>Flyway migrations")]
    API --> OUT["Transactional outbox<br/>→ dispatcher"]
    OUT --> SMS["SMS adapter"] & WA["WhatsApp adapter"] & PUSH["Push (FCM) adapter"] & MAIL["Email adapter"]
    API <--> PG["Payment provider adapter<br/>(signed webhooks)"]
    API --> MON["Metrics (Prometheus)<br/>System Health page"]
```

**Why this shape?**

| Choice | Reason |
|---|---|
| One backend for all apps | One source of truth for rules (prices, permissions, states). No duplicated logic. |
| **Modular monolith** (not microservices) | Simple to build, test and deploy for a startup; modules are cleanly separated so any one can be split out later if it needs to scale on its own. |
| Stateless backend instances | Run 2, 3 or 10 copies behind a load balancer; any instance can serve any request. |
| PostgreSQL as the source of truth | Strong transactions; critical rules (one partner per job, one accepted quote…) are enforced by the database itself. |
| Adapters for outside services | Test-mode adapters today; swap in Razorpay / an SMS provider later **without touching business logic**. |
| Admin BFF ("backend for frontend") | Admin tokens never reach browser JavaScript — safer against XSS. |

---

## 2. Backend modules

Each module is a Java package `com.buzzer.<module>` that owns its tables and exposes services. Controllers only read
across modules for list views.

```mermaid
flowchart LR
    identity --> customer & agent & partner
    catalogue --> request & cart
    location --> request & customer
    customer --> request
    cart --> request
    agent --> request
    request --> quotation --> assignment --> execution --> payment --> finance
    partner --> quotation
    notification -.used by all.- request
    ops -.monitors.- request
    health -.monitors.- notification
```

| Module | Responsibility |
|---|---|
| `identity` | OTP login, JWT access + rotating refresh tokens, roles, per-app sign-in rules, rate limits |
| `location` | Cities, localities, pincodes, nearest locality from GPS |
| `catalogue` | Categories → subcategories → menu groups → items, pricing types, dynamic forms, deals, search, **Excel import/export** |
| `customer` | Profile, addresses, **dedicated-agent subscription / explore onboarding** |
| `cart` | Cart, wishlist, checkout ("Book now" / "Send to my agent"), write-to-agent |
| `agent` | Agent allocation & language matching, portfolios, agent workspace APIs |
| `partner` | Registration, terms, KYC, capabilities, service areas, eligibility, **price lists** |
| `request` | Request creation, 24-state lifecycle state machine, history, messages, SLA |
| `quotation` | Invitations, quotations, sharing, **instant quotes from price lists**, **direct quotes** (`DirectQuoteService`: automatic dispatch to partners, item-by-item quotes, revisions, automatic hand-over to agents) |
| `assignment` | Exclusive assignment (exactly one partner), decline/cancel, reassignment |
| `execution` | Booking, arrival, START/COMPLETE OTPs, reschedules, overrides |
| `payment` | Payment orders, provider adapter, webhooks, retries, pay-on-delivery |
| `finance` | Ledger, commission rules (+ Excel), invoices, payouts, refunds |
| `notification` | In-app notifications, outbox, dispatcher, channel adapters |
| `ops` | Exceptions, escalations, **transfers**, wings/managers, dashboards, exports |
| `health` | System Health checks for every component |
| `common` | Errors, audit, JSON, settings, Excel helpers, media library, exports |

---

## 3. A request, step by step

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer
    participant API as Backend
    actor A as Agent
    actor P as Partner(s)
    C->>API: Checkout cart / submit request (Idempotency-Key)
    API->>API: Validate form, address, city availability
    alt "Get quotes" (DIRECT_QUOTE, handling AUTOMATED)
        API-->>P: Sent automatically to the best verified partners
        P->>API: Item-by-item quote (available? price) + delivery fee
        API-->>C: Quote shared instantly (customer may ask to revise)
    else "Send to my agent" (subscribers) / write to agent
    API-->>A: Notification: new request (SLA clock starts)
    A->>API: Acknowledge (first response)
    end
    alt Agent path and partner has approved price list
        A->>API: Create instant quote
    else
        A->>API: Invite eligible partners
        P->>API: Submit quotation (server computes totals)
    end
    A->>API: Share best quotes with customer
    C->>API: Accept quote + pay (online or pay-on-delivery)
    API->>API: Lock request row → assign exactly one partner
    P->>API: Confirm → Arrive → START OTP from customer
    P->>API: COMPLETE OTP from customer
    API->>API: Invoice, ledger postings, commission, payout scheduled
    C->>API: Rating & review
```

### Request lifecycle (state machine)

```mermaid
stateDiagram-v2
    [*] --> REQUEST_CREATED
    REQUEST_CREATED --> AGENT_REVIEW
    REQUEST_CREATED --> PARTNER_QUOTATION: Get quotes (automatic)
    AGENT_REVIEW --> PARTNER_QUOTATION
    PARTNER_QUOTATION --> CUSTOMER_APPROVAL
    CUSTOMER_APPROVAL --> PAYMENT_PENDING
    PAYMENT_PENDING --> PAYMENT_CONFIRMED
    CUSTOMER_APPROVAL --> PARTNER_ASSIGNED: pay on delivery
    PAYMENT_CONFIRMED --> PARTNER_ASSIGNED
    PARTNER_ASSIGNED --> PARTNER_CONFIRMED
    PARTNER_CONFIRMED --> PARTNER_ARRIVED
    PARTNER_ARRIVED --> SERVICE_STARTED: START OTP
    SERVICE_STARTED --> COMPLETION_PENDING
    COMPLETION_PENDING --> COMPLETED: COMPLETE OTP
    COMPLETED --> SETTLEMENT_PENDING
    SETTLEMENT_PENDING --> SETTLED
    SETTLED --> [*]
```

Side states: `RESCHEDULING_REQUESTED, PARTNER_DECLINED, NO_SHOW, DISPUTED, CANCELLED, REFUND_PENDING, REFUNDED,
REJECTED, EXPIRED`. Allowed moves live in `RequestStatus`; every move writes `request_status_history` and
`audit_events`. `GET /api/v1/admin/request-states` returns the live table.

---

## 4. Agent allocation (dedicated agent subscription)

```mermaid
flowchart TD
    N[New customer signs up] --> W{Welcome screen}
    W -->|Explore| E[agent_plan = EXPLORE<br/>no permanent agent]
    W -->|Subscribe| S[Save preferences:<br/>languages in order, contact method/time,<br/>area, interests, notes]
    S --> R[Rank agents]
    R --> R1[1. same city, not on leave, below capacity]
    R1 --> R2[2. regular agents before managers]
    R2 --> R3[3. covers customer's locality]
    R3 --> R4[4. speaks customer's FIRST-choice language]
    R4 --> R5[5. most languages in common]
    R5 --> R6[6. currently available]
    R6 --> R7[7. lowest workload ratio]
    R7 --> L[Link PRIMARY agent + BACKUP agent]
    E --> Q[Each request → best on-duty agent<br/>agent_source = ON_DUTY]
    L --> Q2[Each request → dedicated agent<br/>agent_source = DEDICATED]
```

If nobody is available, the customer stays subscribed, an `NO_AGENT_AVAILABLE` exception is raised for admins, and
allocation is retried on the next request.

## 4b. Direct quotes ("Get quotes") — automation instead of manual agent work

```mermaid
flowchart TD
    CO["Checkout: menu items + extra items<br/>mode DIRECT_QUOTE"] --> RQ["Request created<br/>handling_mode = AUTOMATED<br/>agent = subscriber's agent, watch only, or none"]
    RQ --> EL{Eligible verified partners?}
    EL -->|none| HO["Hand to agent automatically<br/>dedicated agent, else on-duty agent<br/>+ ops exception"]
    EL -->|yes| DS["Invite top N partners<br/>setting direct_quote.max_partners = 5<br/>ranked by price-list coverage, rating, workload"]
    DS --> PQ["Partner: per item available? + price<br/>pre-filled from approved price list, delivery fee/time"]
    PQ --> SH["Quote status SHARED immediately<br/>request → CUSTOMER_APPROVAL"]
    SH -->|Ask to revise| RV["Quote → REVISION_REQUESTED<br/>partner resubmits → new version, old → REVISED"]
    RV --> SH
    SH --> AC["Customer accepts + pays → exclusive assignment"]
    DS -->|all partners decline| HO
    DS -->|no quote within direct_quote.no_quote_minutes = 60| HO
```

Every automatic step is written to `request_status_history` with actor role `SYSTEM` (status changes via
`RequestRepository.transition`, non-status events via `RequestRepository.note`), which the apps show as a timeline.
The agent inbox orders tickets by *never opened* (`agent_seen_at IS NULL`) first, then agent work, then the rest.

---

## 5. Data model (main tables)

```mermaid
erDiagram
    users ||--o| customer_profiles : "is a"
    users ||--o| agents : "is a"
    users ||--o| partners : "is a"
    customer_profiles ||--o{ customer_agent_links : "PRIMARY / BACKUP"
    agents ||--o{ customer_agent_links : serves
    categories ||--o{ subcategories : has
    subcategories ||--o{ service_items : "menu items"
    service_requests }o--|| users : "customer"
    service_requests }o--o| agents : "handled by"
    service_requests ||--o{ partner_invitations : invites
    service_requests ||--o{ quotations : receives
    service_requests ||--o{ assignments : "exactly one live"
    service_requests ||--o{ request_status_history : history
    partners ||--o{ partner_capabilities : "approved services"
    partners ||--o{ partner_price_items : "price list"
    service_requests ||--o{ payments : paid_by
    payments ||--o{ ledger_entries : "posted to"
```

Migrations (`backend/src/main/resources/db/migration`):

| Version | Adds |
|---|---|
| V1 | Core schema: users, roles, OTP, tokens, customers, agents, partners, requests, quotations, assignments, payments, ledger, notifications, outbox, exceptions, audit |
| V2 | Hyderabad + localities, 25 categories, forms, illustrative commission rules, terms |
| V3 | Locality coordinates, image URLs |
| V4 | Cart, wishlist, deals, booking modes, line items |
| V5 | Rich priced menus (repairs, events, travel, dining…) |
| V6 | Menu groups, partner price lists, media library, agent wings |
| V7 | Dedicated-agent subscription (plan, preferences), request `agent_source` |
| V8 | Direct quotes: `handling_mode` (AGENT / AUTOMATED), agent `agent_seen_at` (NEW highlight), hand-over fields, item-level quotation lines + revisions, settings, **Hardware & Supplies** example category |
| V9 | Timeline entries use real clock time so steps in one transaction stay in order |
| `db/devseed` V1000–V1002 | **Dev/test only** staff, partners, deals and a demo hardware shop — never loaded in production |

---

## 6. Reliability principles

- **The database enforces the important rules.** Partial unique indexes guarantee: one live assignment per request,
  one accepted quotation, one active primary/backup agent per customer, unique idempotency keys, unique webhook events,
  idempotent ledger postings.
- **Explicit SQL, explicit locks.** `SELECT … FOR UPDATE` on assignment; `FOR UPDATE SKIP LOCKED` lets many instances
  share the outbox safely.
- **Idempotency everywhere money or requests are created** (`Idempotency-Key` header) — double taps never create
  duplicates.
- **Write first, call out later.** Business records are saved, then messages go through the outbox with retries,
  exponential backoff and a dead-letter queue.
- **Never trust the client.** Totals, prices, eligibility, permissions and state changes are recomputed on the server.
- **Honest status.** Test-mode integrations are labelled in the API, UI and System Health; unknown components never
  show green.

## 7. Security

| Layer | Protection |
|---|---|
| Login | OTP only (hashed, 5-minute expiry, single use, attempt and rate limits); staff apps refuse unknown numbers without revealing whether they exist |
| Sessions | JWT access tokens (30 min) + rotating refresh tokens stored as SHA-256 hashes; reuse is detected and rejected |
| Authorisation | Roles per app (customer, partner, agent, agent manager, support, finance, admin, super admin) + method-level checks; agents see only their portfolio; partners only their own leads/jobs (others return 404) |
| Privacy | Customer contact released to a partner only after booking; KYC numbers masked; OTPs and secrets redacted from logs |
| Admin web | Tokens in httpOnly cookies behind the BFF; proxy allow-list of backend paths |
| Files | Image uploads checked by content (magic bytes), PNG/JPEG/WebP, ≤ 2 MB |
| Spreadsheets | Excel/CSV exports neutralise formula injection |
| Secrets | From environment variables only; dev defaults are clearly marked "change me" |

## 8. Production deployment (target)

See [deployment.md](deployment.md) and the [runbooks](runbooks): multi-AZ load balancer + WAF + CDN, 2+ backend
replicas (Kubernetes reference manifest in `infra/k8s`), managed PostgreSQL with hot standby and point-in-time recovery,
Redis for cluster-wide rate limits (planned), object storage for photos/KYC (planned), Prometheus metrics, and the
in-app System Health page.
