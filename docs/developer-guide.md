# Developer Guide

Everything a developer needs to **run, understand, change and test** BUZZER.
Related: [Architecture](architecture.md) · [Technology stack](technology-stack.md) · [Business overview](business-overview.md) · **[Developer logins (Excel)](developer-logins.xlsx)**

---

## 1. Get running in 10 minutes

### Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 or newer (tested on 25) | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Node.js | 24+ | `node -v` |
| Git | any recent | |

**You do not need Docker or a PostgreSQL install** — the backend starts a real embedded PostgreSQL for you.

### Clone

```bash
git clone https://github.com/weetecnologias/BUZZER.git
```

```bash
cd BUZZER
```

### Start the backend (port 8080)

```bash
cd backend && mvn spring-boot:test-run -Dspring-boot.run.main-class=com.buzzer.LocalDevApplication
```

- First start downloads dependencies and PostgreSQL binaries (a few minutes).
- Data persists in `backend/.local-data/` (delete the folder to start fresh).
- Health: http://localhost:8080/actuator/health · API docs: http://localhost:8080/swagger-ui.html

### Start the admin panel (port 3000)

```bash
cd apps/admin-web && npm install && npm run dev
```

### Start the mobile apps in the browser

```bash
cd mobile && npm install
```

```bash
npm --workspace @buzzer/customer-app run web -- --port 8081
```

```bash
npm --workspace @buzzer/partner-app run web -- --port 8082
```

```bash
npm --workspace @buzzer/agent-app run web -- --port 8083
```

**On a real phone:** run `npm --workspace @buzzer/customer-app run start`, scan the QR code with Expo Go, and set
`EXPO_PUBLIC_API_URL` to your computer's LAN address (e.g. `http://192.168.1.20:8080`). The Android emulator reaches
your machine at `http://10.0.2.2:8080` automatically.

### Claude Code / VS Code launch config

[`.claude/launch.json`](../.claude/launch.json) defines `backend`, `customer-app`, `partner-app`, `agent-app` and
`admin-web` so all five can be started from the tooling.

### Sign in

All apps use **mobile number + OTP**. In the **dev profile only**, the OTP is returned by the API and shown on screen
("DEV MODE – your OTP is …"). The full list of test accounts is in **[developer-logins.xlsx](developer-logins.xlsx)**:

| App | URL | Try |
|---|---|---|
| Admin | http://localhost:3000 | 9000000001 (Super Admin) |
| Agent | http://localhost:8083 | 9000000010 (manager), 9000000011, 9000000012 |
| Partner | http://localhost:8082 | 9000000021 – 9000000025 |
| Customer | http://localhost:8081 | any new number (sign-up flow) |

---

## 2. Repository layout

```
BUZZER/
├── backend/                         Spring Boot API (Java 21, Maven)
│   ├── src/main/java/com/buzzer/
│   │   ├── identity/                OTP login, JWT, roles
│   │   ├── catalogue/               categories, items, search, Excel import/export
│   │   ├── customer/                profile, addresses, dedicated-agent subscription
│   │   ├── cart/                    cart, wishlist, checkout
│   │   ├── agent/                   allocation, agent workspace APIs
│   │   ├── partner/                 registration, KYC, price lists
│   │   ├── request/                 request lifecycle (RequestStatus state machine)
│   │   ├── quotation/               invitations, quotes, instant quotes
│   │   ├── assignment/ execution/   exclusive assignment, OTP start/complete
│   │   ├── payment/ finance/        payments, ledger, commission, payouts
│   │   ├── notification/            in-app, outbox, channel adapters
│   │   ├── ops/ health/             exceptions, transfers, dashboards, system health
│   │   └── common/                  errors, audit, Excel, media, exports, settings
│   ├── src/main/resources/
│   │   ├── application*.yml         configuration (dev profile in application-dev.yml)
│   │   ├── db/migration/            Flyway V1…V7 (production schema + reference data)
│   │   └── db/devseed/              dev-only seed data (V1000+), never in production
│   └── src/test/java/com/buzzer/    integration tests + LocalDevApplication
├── apps/admin-web/                  Next.js admin panel
│   └── src/app/(panel)/<page>/      one folder per admin page
│       src/app/api/backend/         BFF proxy to the backend (allow-listed paths)
│       src/components/              ui.tsx, export.tsx, workflow.tsx (Excel, transfers, images)
├── mobile/                          npm workspace
│   ├── packages/core/               shared: api client, auth/OTP, ui kit, nav, forms, format
│   └── apps/customer|partner|agent/ the three Expo apps (App.tsx + src/screens)
├── design/tokens.json               brand colours, spacing, type
├── infra/                           docker-compose.yml, k8s/backend.yaml
├── docs/                            this documentation + runbooks
└── .github/workflows/ci.yml         CI pipeline
```

---

## 3. How the pieces talk

- Every API lives under **`/api/v1/...`** and returns `{ "data": … }` on success or
  `{ "error": { "code", "message", "details" } }` on failure.
- JSON uses **snake_case** everywhere (`full_name`, `service_item_id`).
- Mobile apps call the backend directly with `Authorization: Bearer <access token>` (see `mobile/packages/core/src/api.ts`).
- The admin panel calls **`/api/backend/<path>`** on its own server; the BFF adds the token from an httpOnly cookie and
  forwards only allow-listed paths (`admin/`, `catalogue/`, `locations/`, `notifications`, `auth/me`, `media/`).
- Endpoints that create things accept an **`Idempotency-Key`** header — send the same key on retry.

### API groups

| Prefix | Who | Examples |
|---|---|---|
| `/auth` | everyone | `otp/request`, `otp/verify`, `refresh`, `me` |
| `/catalogue`, `/locations` | public/any app | categories, services, search, deals, nearest locality |
| `/customer` | Customer app | onboarding, `agent/subscribe`, cart, checkout, requests, addresses |
| `/agent` | Agent app | inbox, request workspace, invitations, instant quotes, transfer, customers |
| `/partner` | Partner app | registration, KYC, leads, quotes, jobs, earnings, price list |
| `/admin/**` | Admin panel | dashboard, catalogue (+Excel), partners, price lists, agents, customers, requests, exceptions, finance (+Excel), settings, health, exports, media |
| `/payments/webhooks/{provider}` | payment provider | signed webhooks |

Browse everything live at **http://localhost:8080/swagger-ui.html**.

---

## 4. Everyday tasks

### Add or change a database table

1. Create the next migration: `backend/src/main/resources/db/migration/V8__short_description.sql`.
2. **Never edit a migration that has already been merged** — always add a new one.
3. Restart the backend; Flyway applies it. Tests run all migrations on a fresh DB.
4. Dev-only data goes in `db/devseed/V10xx__…sql` (loaded only by the dev profile; `out-of-order` is enabled there).

### Add an API endpoint

1. Put it in the right module's controller (e.g. `customer/CustomerController.java`).
2. Use a `record` for the input with validation annotations; return `ApiResponse.of(...)`.
3. Throw `BusinessException` (`invalid`, `notFound`, `forbidden`…) for expected errors — the global handler formats them.
4. Protect it: role checks (`@PreAuthorize`) and ownership checks (`CurrentUser.id()`, `AgentAccess`).
5. Write important actions to the audit log (`AuditService.record`).
6. Add an integration test (see §5).
7. If the admin panel needs it and the path is new, check the allow-list in `apps/admin-web/src/app/api/backend/[...path]/route.ts`.

### Add an admin page

1. Create `apps/admin-web/src/app/(panel)/<name>/page.tsx` (client component).
2. Load data with `useApi("admin/…")`, mutate with `useMutation()` + `api(...)` from `@/lib/api`.
3. Reuse `PageHeader`, `Card`, `Modal`, `Status`, `ExportMenu`, `ExcelImport`, `TransferModal`, `ImageField`.
4. Add it to the sidebar in `src/app/(panel)/layout.tsx`.

### Add a mobile screen

1. Create the screen in `mobile/apps/<app>/src/screens/`.
2. Register the route in that app's `App.tsx` (`StackNavigator` screens map) and open it with `nav.push('Name', params)`.
3. Use `useQuery` / `useAction` and UI components from `@buzzer/core` so it matches the brand.

### Change the catalogue (no code needed)

Admin → **Catalogue** → *Excel import / export*: download the full catalogue, edit rows (one row = one menu item:
category → subcategory → menu_group → item with price), upload, review the preview, import. Images: upload in any
edit form (⬆ Upload) and paste the `/api/v1/media/…` link into `image_url`, or use any `https://` link.

### Change commission rates (no code needed)

Admin → **Finance** → *Excel upload*: download the rate sheet, fill `new_rate_percent` (and optionally
`effective_from`) only on rows to change, upload, preview, import. Each change creates a new dated rule.

---

## 5. Testing

```bash
cd backend && mvn test
```

**41 integration tests** run against a real embedded PostgreSQL 17 (no mocks of the database):

| Suite | Covers |
|---|---|
| `AuthAndAccessTest` | OTP, lockout, single use, staff-app gating, roles, token rotation, agent isolation |
| `CatalogueAndFormsTest` | Catalogue, contextual menus, dynamic form validation, admin catalogue changes |
| `EndToEndServiceFlowTest` | Full plumbing flow: request → quotes → payment → assignment → OTPs → commission → payout → review |
| `ExclusiveAssignmentConcurrencyTest` | 12 concurrent assigners → exactly one winner; decline/reassign |
| `ResilienceAndOperationsTest` | Payment failure/retry, webhooks, SLA escalation, cancellations alert, dead-letter, refunds |
| `CartCheckoutTest` | Cart, wishlist, checkout modes, idempotent checkout |
| `AdminToolsTest` | Media upload, catalogue & commission Excel, export injection guard, price lists → instant quotes, transfers |
| `AgentSubscriptionTest` | Welcome state, explore → on-duty agent, language-matched subscription |

Test helpers: `support/ApiClient` (`login` auto-subscribes new customers; `loginOnly` doesn't), `support/FlowHelper`
(drives a request to customer approval).

Front-end checks:

```bash
cd mobile && npm run typecheck
```

```bash
cd apps/admin-web && npx tsc --noEmit && npm run build
```

There are **no automated UI tests yet** (planned: Playwright for admin, Maestro/Detox for mobile).

---

## 6. Configuration

Copy [`.env.example`](../.env.example) to `.env` for real environments. Key variables:

| Variable | Purpose |
|---|---|
| `BUZZER_DB_URL`, `BUZZER_DB_USER`, `BUZZER_DB_PASSWORD` | PostgreSQL connection (not needed for local dev) |
| `BUZZER_JWT_SECRET`, `BUZZER_OTP_PEPPER` | **Required in production** — long random secrets |
| `BUZZER_PAYMENT_PROVIDER`, `BUZZER_PAYMENT_WEBHOOK_SECRET` | `mock` = test mode |
| `BUZZER_SMS_PROVIDER` | `log` = test mode |
| `BUZZER_CORS_ORIGINS` | Allowed web origins |
| `BUZZER_BACKEND_URL` | Admin BFF → backend URL |
| `EXPO_PUBLIC_API_URL` | Mobile apps → backend URL |

Profiles: `dev` (local; shows OTP, loads dev seed, permissive CORS for `localhost:*`), `test` (high rate limits),
production (no dev seed, secrets required).

---

## 7. Conventions

- **Branches:** never commit or push to `main`. Use `feature/<topic>` branches and pull requests.
- **Commits:** imperative subject line ("Add partner price lists"), short body explaining why.
- **Money:** `BigDecimal` / `NUMERIC(12,2)`; never compute totals on the client.
- **Rules belong in the database** when they are invariants (unique indexes), in the backend otherwise — never only in the UI.
- **Every state change** goes through `RequestStatus` transitions and writes history + audit.
- **Test-mode honesty:** if something is simulated, the API and UI must say so.
- **Brand:** green palette only (see `design/tokens.json`); reuse `@buzzer/core` UI components.

---

## 8. Troubleshooting

| Problem | Fix |
|---|---|
| `Port 8080 already in use` | Another backend is still stopping. Wait a few seconds or stop the old process, then start again. |
| Flyway: "Detected resolved migration not applied" | Only in dev: `spring.flyway.out-of-order: true` is already set in `application-dev.yml`; otherwise delete `backend/.local-data/` to rebuild the dev DB. |
| Mobile web stuck on "Bundling…" | Stop and restart the Expo server (`--clear` to reset the cache). |
| "Use current location" fails in the browser | Allow location permission for `localhost`; the address form still works manually. |
| Phone can't reach the backend | Set `EXPO_PUBLIC_API_URL` to your PC's LAN IP and allow port 8080 in the firewall. |
| OTP not shown | You are not on the `dev` profile — OTPs are only displayed in dev. |
| Admin shows "BUZZER backend is unreachable" | Start the backend, or set `BUZZER_BACKEND_URL`. |
