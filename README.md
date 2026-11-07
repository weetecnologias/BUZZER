# BUZZER — Your Personal Assistant for Everyday Life

*You Buzz. We Do.*

BUZZER is a multi-service coordination platform launching in Hyderabad. A customer raises a requirement, their
**dedicated BUZZER agent** collects quotations from **verified partners**, the customer approves and pays, and the
platform tracks the job through OTP-verified start and completion to settlement.

| Part | Path | Stack |
|---|---|---|
| Backend API (modular monolith) | [`backend/`](backend) | Java 21 · Spring Boot 3.5 · PostgreSQL · Flyway · OpenAPI |
| Admin Web Panel | [`apps/admin-web/`](apps/admin-web) | Next.js 16 · React 19 · TypeScript (BFF with httpOnly-cookie sessions) |
| Customer App | [`mobile/apps/customer/`](mobile/apps/customer) | Expo SDK 58 · React Native · TypeScript |
| Partner App | [`mobile/apps/partner/`](mobile/apps/partner) | Expo SDK 58 · React Native · TypeScript |
| Agent App | [`mobile/apps/agent/`](mobile/apps/agent) | Expo SDK 58 · React Native · TypeScript (mobile + desktop web) |
| Shared mobile kit | [`mobile/packages/core/`](mobile/packages/core) | Design system, API client, auth, navigation, dynamic forms |
| Design tokens | [`design/tokens.json`](design/tokens.json) | Forest/Emerald green brand palette |
| Infrastructure | [`infra/`](infra) | Docker Compose, Kubernetes reference manifests |
| Docs & runbooks | [`docs/`](docs) | Architecture, phase report, deployment, DR runbooks |

Each frontend is built and deployed independently; all four share one backend.

## Documentation

| Read this | If you are… |
|---|---|
| [Business overview](docs/business-overview.md) | Founder, operations, sales, investor — what BUZZER does, journeys, revenue, status |
| [Developer guide](docs/developer-guide.md) | Developer — setup, structure, everyday tasks, testing, troubleshooting |
| [Architecture](docs/architecture.md) | Anyone technical — diagrams of the system, modules, request lifecycle, data model, security |
| [Technology stack](docs/technology-stack.md) | Anyone — every technology in plain words and why it was chosen |
| [Developer logins (Excel)](docs/developer-logins.xlsx) | Developer / tester — all dev and test sign-ins |
| [Deployment](docs/deployment.md) · [Runbooks](docs/runbooks) | DevOps — production setup, backup/restore, disaster recovery |
| [Phase 1 report](docs/phase-1-report.md) | History of the first build phase |

## Quick start (local development)

Prerequisites: JDK 21+ (tested on 25), Maven 3.9+, Node 24+. **No Docker or PostgreSQL install needed** — local dev
and tests use a real embedded PostgreSQL.

```bash
cd backend && mvn spring-boot:test-run -Dspring-boot.run.main-class=com.buzzer.LocalDevApplication
```

Backend: http://localhost:8080 · API docs: http://localhost:8080/swagger-ui.html · data persists in `backend/.local-data/`.

```bash
cd apps/admin-web && npm install && npm run dev
```

```bash
cd mobile && npm install && npm --workspace @buzzer/customer-app run web -- --port 8081
```

Partner app: `npm --workspace @buzzer/partner-app run web -- --port 8082` · Agent app: `npm --workspace @buzzer/agent-app run web -- --port 8083`.
On a phone, run `npm --workspace @buzzer/customer-app run start` and set `EXPO_PUBLIC_API_URL` to your machine's LAN address.

### Dev sign-in

All apps use mobile OTP. In the **dev profile only**, the OTP is returned by the API and shown on screen.
The complete list is in [docs/developer-logins.xlsx](docs/developer-logins.xlsx).

| Who | Phone | App |
|---|---|---|
| Super admin | 90000 00001 | Admin panel |
| Agent manager (Suresh Reddy) | 90000 00010 | Agent app / Admin |
| Agents (Rahul Sharma, Priya Nair) | 90000 00011 / 12 | Agent app |
| Plumbers (approved + KYC verified) | 90000 00021 / 22 / 23 | Partner app |
| Electrician | 90000 00024 | Partner app |
| Pending partner | 90000 00025 | Partner app |
| Hardware shop (Sri Sai Hardware) | 90000 00026 | Partner app — quotes "Get quotes" requests item by item |
| Any other number | — | Customer app (sign up → Subscribe for a dedicated agent / Explore) / Partner app (registration) |

Payments run on a **TEST MODE** gateway: the customer app shows "Simulate success / failure" buttons, which drive the
real signed-webhook code path. No real money moves and nothing pretends to be a live integration.

## Tests

```bash
cd backend && mvn test
```

44 integration tests against real PostgreSQL cover authentication and RBAC, the dynamic catalogue and form validation,
the full plumbing flow from registration to settlement, concurrent exclusive assignment, payment failure/retry, webhook
signatures and duplicates, SLA escalation, cancellation alerts, dead-lettering, commission precedence, refunds, cart and
checkout, Excel imports, partner price lists and instant quotes, ticket transfers, dedicated-agent subscription, and automated "Get quotes" straight from partners.
See [`docs/phase-1-report.md`](docs/phase-1-report.md) for what is implemented, tested, in test mode, and still pending.

## Branching

`main` is protected by convention: never push directly to `main`. Work on feature branches and open pull requests.
