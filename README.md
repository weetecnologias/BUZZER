# BUZZER — "You buzz. We do."

A multi-sided personal-assistance platform for Hyderabad, built from the BUZZER developer blueprint and the
UI/UX decks: **Customer app · Partner portal · Agent workspace · Admin console**, one backend, one source of truth.

## Run it

```bash
pip install -r requirements.txt      # Flask only
python run.py                        # http://127.0.0.1:8000
```

The first start creates `data/buzzer.db` and seeds demo data for all four roles
(delete `data/buzzer.db*` to reset). Nothing else to install — no Node, Docker or database server.

### Demo accounts (development seed data)
| Portal | Sign in | Notes |
|---|---|---|
| Customer | mobile `98765 43210` (Rahul) — OTP is shown on screen in development | Has a request awaiting quote approval |
| Partner | `arun@abchome.in` / `Buzzer@123` | ABC Home Services (verified partner + employer) |
| Partner | `nisha@lensandlight.in`, `mahesh@royalfeast.in`, `rizwan@metrointeriors.in` | photography · catering · verification pending |
| Agent | `priya@buzzer.in`, `ravi@buzzer.in`, `neha@buzzer.in` (team lead) | Rahul's dedicated agent is Priya |
| Admin | `super@buzzer.in`, `ops@`, `finance@`, `verify@` `…@buzzer.in` | Different admin roles see different menus |

All staff/partner demo passwords: `Buzzer@123`.

## The journey (works end to end)
1. **Customer** raises a request (dynamic form per category) → auto-allocated to their dedicated agent.
2. **Agent** triages, invites verified providers, validates and shares quotes.
3. **Partner** receives a limited lead (no customer identity), sends an itemised quote.
4. **Customer** compares quotes and **explicitly approves** one — the hard gate. Only then is a booking created
   (the database also enforces one live booking per request). A price change after approval needs re-consent.
5. Sandbox payment → provider executes the job (on the way → in progress → done + evidence) →
   customer confirms → commission ledger, rating, payout request → admin finance approves.
Quick Jobs (employer ↔ candidate), wedding/event and photography briefs, complaints/disputes, refunds,
verification, category/workflow configuration, audit logs and reports run on the same data.

## Architecture (follows the blueprint)
- **Modular monolith, API-first**: Flask, `/api/v1`, one module per domain in `server/api/`
  (identity, catalogue, requests & workflow, quotes & bookings, providers, jobs, comms, trust, agent, admin).
- **SQLite** with the blueprint's tables (`server/schema.sql`); request status history, assignment history, quote
  approvals, immutable ledger, audit log. Swap for PostgreSQL by porting `db.py` and the schema.
- **Frontend**: no-build vanilla ES modules (`web/`), four role shells, shared components, responsive.
- **Security**: RBAC + record-level checks (customers see their own, agents their assigned work, partners only leads
  they were invited to), hashed passwords/tokens, rate-limited OTP/login, CSP, validated uploads served by
  short-lived signed URLs, CSV-injection-safe exports, audit log of sensitive access.
- **Simulated by design** (blueprint: activate after legal/gateway review): OTP/SMS/WhatsApp, payment gateway
  (sandbox adapter — no card data is ever collected), maps.

### Deviation from the blueprint's recommended stack
The blueprint recommends Java/Spring Boot + PostgreSQL + Next.js. The build machine only had Python, so the same
architecture is implemented on Flask + SQLite + vanilla JS. Endpoint shapes, entities and workflow rules follow the
blueprint so the backend can be re-platformed behind the same API contract.

## Tests
```bash
python -m unittest discover -s tests -v
```
Covers the full journey, the approval gate, duplicate guards, record-level access, refunds, jobs and uploads.

`GET /api/v1/openapi.json` lists every route; `GET /api/v1/health` is the health check.

## Config (environment variables)
`BUZZER_ENV` (`development` shows OTPs/demo helpers), `BUZZER_PORT`, `BUZZER_HOST`, `BUZZER_DB`, `BUZZER_UPLOADS`,
`BUZZER_SECRET` (generated into `data/secret.key` if unset — keep secrets out of source).

## Known limits / next steps
Real SMS/WhatsApp/payment integrations, PostgreSQL + migrations, background retry queue for notifications,
object storage, automated browser tests, full OpenAPI schemas, legal review of Terms/Privacy.
