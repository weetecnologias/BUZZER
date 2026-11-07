# BUZZER Transformation — Progress Report

Plan: [transformation-plan.md](transformation-plan.md) · Audit: [transformation-audit.md](transformation-audit.md)

| Stage | Status |
|---|---|
| 1. Audit | ✅ Done |
| 2. Architecture & database impact | ✅ Done, approved (all 7 recommended decisions) |
| **3. Unified UI/UX & navigation** | ✅ **Done** — acceptance tests pass |
| 4. Request engine | ⏭ Next |
| 5–10 | Planned |

---

## Stage 3 — Unified UI/UX & navigation

### Delivered

**Customer app**
- New **Home**: BUZZER logo, location, cart, notifications, profile; "What do you need today?"; universal search entry;
  four cards **Shop & Buy · Request Quotations · Book Services · Personal Assistance** (shown only when the vertical is on);
  prominent **＋ Create a Request**; Active orders; My assistant; Popular categories; **Shops on BUZZER**; Offers &
  featured; Recommended services; Recent requests.
- New bottom navigation: **Home · Explore · My Requests · Orders · Account** (notifications moved to the header).
- **Explore**: one chip per enabled vertical, each with its own categories and a short "how it works" hint.
- **Universal search** ("Search products, shops, services or tell us what you need..."): items, shops and categories
  plus **one suggested workflow** (shop, request quotes, book a service, task, or personal assistance). The suggestion is
  only a link — nothing is created until the customer confirms in that flow.
- **Create a Request**: get quotes from shops, book a service, task/errand, or ask a person to handle it.
- **Personal Assistance** screen: dedicated agent (call / WhatsApp / write) or subscribe; what agents can do; "you
  always approve before anything is ordered or paid".
- **Orders** tab: accepted purchases/bookings (active and past). Product orders join it in Stage 5.
- **Account**: renamed from Profile; adds Notifications and Help & support.

**Backend**
- `platform_verticals` with admin on/off switches; every category belongs to one vertical
  (Hardware & Supplies, Shopping & Product Sourcing → Marketplace; Daily Errands, Delivery & Logistics → Tasks;
  Special Requests → Assistance; the rest → Services).
- A disabled vertical disappears from categories, category detail, subcategories, items, deals, search, featured shops,
  cart and checkout — **data is kept**, admin still sees it.
- Universal search with word-level matching (e.g. "leaking tap repair" now finds "Pipe Leakage") and rule-based intent.

**Admin**
- Settings → **Business verticals** card with ON/OFF switches.
- Catalogue → category editor has a **Business vertical** field; the tree shows each category's vertical.

### Database migration
- `V10__platform_verticals.sql` — new `platform_verticals` table (4 rows), `categories.vertical` (FK, default `SERVICES`) with backfill. Additive only.

### API contracts (new / changed)

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/v1/platform/verticals` | public | Enabled verticals in display order |
| GET | `/api/v1/platform/featured-shops?limit=` | public | Verified partners with approved marketplace capabilities |
| GET | `/api/v1/search?q=` | public | `{ suggestion, categories, items, shops }` — suggestion is advisory |
| GET | `/api/v1/admin/platform/verticals` | admin roles | Includes disabled verticals |
| PUT | `/api/v1/admin/platform/verticals/{code}` | ADMIN, SUPER_ADMIN | `{ "enabled": true/false }`, audited |
| GET | `/api/v1/catalogue/categories?vertical=` | public | New optional filter; responses now include `vertical` |
| GET | `/api/v1/customer/home` | customer | Adds `recent_requests`, `active_orders` |
| POST/PUT | `/api/v1/admin/catalogue/categories` | admin | Optional `vertical` field |

### Files
- **New:** `backend/.../platform/PlatformService.java`, `PlatformController.java`, `db/migration/V10__platform_verticals.sql`,
  `backend/src/test/.../PlatformVerticalsTest.java`, `mobile/apps/customer/src/screens/Unified.tsx`, this report.
- **Changed:** `CatalogueService` (vertical filter, word search), `CatalogueController`, `AdminCatalogueController`, `CartService`,
  `CustomerController`, `SecurityConfig`; customer `App.tsx`, `Home.tsx`, `Cart.tsx` (write-to-agent prefill / task mode),
  `Requests.tsx`, `Profile.tsx`, `Notifications.tsx`; admin `settings/page.tsx`, `catalogue/page.tsx`.

### Tests
- `PlatformVerticalsTest` (3): verticals listed and categories tagged; admin switches Marketplace off → hidden from
  categories / detail (404) / search / featured shops / cart (400), only admins may switch, data intact, switched back on
  → works again; universal search suggests BOOK_SERVICE / REQUEST_QUOTES / TASK / ASSISTANCE and creates nothing.
- **Full suite: 47 passed, 0 failed** (44 existing + 3 new). TypeScript strict typecheck passes for customer, partner and
  agent apps; admin production build passes.
- Checked in the browser (phone size): new Home, universal search ("pickup documents from my office" → *Request a task
  or errand*), Explore with vertical chips, admin vertical switches.

### Rollback
Switch any vertical back on (or off) in Admin → Settings. To roll back the code, revert the Stage 3 commit; V10 is
additive (the `vertical` column and table can stay unused by older code).

### Not in Stage 3 (by plan)
Location-based "nearby" shops, product pages and vendor profiles (Stage 5); task creation screen (Stage 8 — the Task
option currently goes to a person via *Write to my agent* in task mode); payment history and complaints (Stage 9).
