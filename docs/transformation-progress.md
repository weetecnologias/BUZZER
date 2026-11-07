# BUZZER Transformation — Progress Report

Plan: [transformation-plan.md](transformation-plan.md) · Audit: [transformation-audit.md](transformation-audit.md)

| Stage | Status |
|---|---|
| 1. Audit | ✅ Done |
| 2. Architecture & database impact | ✅ Done, approved (all 7 recommended decisions) |
| 3. Unified UI/UX & navigation | ✅ Done |
| **4. Request engine** | ✅ **Done** — acceptance tests pass |
| 5. Marketplace & RFQ | ⏭ Next |
| 6–10 | Planned |

---

## Stage 4 — Unified request engine

### Delivered

- **Shared request identity:** every request now has a `request_type` (PRODUCT_PURCHASE, PRODUCT_RFQ, SERVICE_BOOKING, TASK_REQUEST,
  PERSONAL_ASSISTANCE), a `vertical` and a `lifecycle` (which state machine owns its status: `REQUEST_V1` today; `ORDER` arrives in
  Stage 5, `TASK` in Stage 8). Existing requests were **backfilled**; nothing was moved or deleted.
- **Type rules at creation:** marketplace items → PRODUCT_RFQ; services → SERVICE_BOOKING; Tasks & Errands categories and
  "describe a task" → TASK_REQUEST; *Write to my agent* / Special Requests → PERSONAL_ASSISTANCE.
- **Status groups** shared by every type: OPEN · ACTION_NEEDED · IN_PROGRESS · DONE · CLOSED.
- **`requests` view** — readable name for the hub with the currently assigned partner (for reporting).
- **Private attachments** (photos and PDFs): type detected from file content (PNG, JPEG, WebP, PDF), max 5 MB, 10 per request,
  safe file names, soft delete, every upload in the timeline. Files are **never public** — viewers get **HMAC-signed links that
  expire after 15 minutes**; tampered/expired links return 404. Visibility **PRIVATE** (customer, agent, admin) or **PARTNERS**
  (also the partners invited to quote).
- **Parent / child requests:** an agent can create requests for an assistance case (`parent_request_id`, same customer only);
  both sides show the link.

**Apps**
- Customer: *My Requests* filters by type (Quotes · Services · Tasks · Assistance) and group (Active · Needs you · Done · Closed);
  cards show type, 📎 count and "Needs your decision". Request detail shows the type, **Photos & documents** (add photo/PDF with a
  "Share with partners who are quoting" switch, remove own files) and **Related requests**. Uses Expo's official
  `expo-document-picker` (Android, iOS, web).
- Agent: request type, attachments, related requests.
- Partner: photos/documents the customer shared appear on the lead (item quotes and standard quotes).
- Admin: Requests list filter and column for type (+ 📎 count); request detail shows type/vertical/lifecycle, attachments with
  thumbnails and related requests.

### Database migration
- `V11__request_engine.sql` — `service_requests.request_type`, `vertical`, `lifecycle`, `parent_request_id` (+ backfill and indexes),
  `requests` view, `request_attachments` table. Additive only.

### API contracts (new / changed)

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/v1/customer/requests?type=&group=` | customer | Filters; rows include `request_type`, `status_group`, `attachment_count` |
| POST | `/api/v1/customer/requests/{id}/attachments` | customer (owner) | multipart `file`, `visibility` PRIVATE/PARTNERS, `note` |
| GET | `/api/v1/customer/requests/{id}/attachments` | customer (owner) | With signed `url` |
| DELETE | `/api/v1/customer/requests/{id}/attachments/{attachmentId}` | customer (uploader) | Soft delete |
| POST | `/api/v1/agent/requests/{id}/attachments` | authorised agent | Files the customer sent by phone/WhatsApp |
| POST | `/api/v1/agent/requests` | agent | Optional `parent_request_id` |
| GET | `/api/v1/attachments/{id}?exp=&sig=` | signed link | Serves the file inline, `no-store`, `nosniff` |
| GET | `/api/v1/partner/leads/{invitationId}` | invited partner | Adds `attachments` (PARTNERS only) |
| GET | `/api/v1/admin/requests?type=` | admin | Type filter |
| POST | `/api/v1/customer/agent-request` | customer | Optional `kind: TASK` → task request |
| GET | `/api/v1/customer/requests/{id}` (and agent/admin detail) | owner/agent/admin | Adds `request_type_label`, `attachments`, `parent`, `children` |

### Files
- **New:** `request/RequestType.java`, `request/AttachmentService.java`, `request/AttachmentController.java`, `V11__request_engine.sql`,
  `RequestEngineTest.java`, `mobile/packages/core/src/Attachments.tsx`.
- **Changed:** `RequestService`, `RequestRepository`, `CustomerController`, `AgentController`, `DirectQuoteService`, `AdminOperationsController`,
  `CartController`, `CartService`, `SecurityConfig`; core `api.ts` (multipart upload), `index.ts`; customer `RequestDetail.tsx`, `Requests.tsx`,
  `package.json` (expo-document-picker); agent `AgentRequest.tsx`; partner `Leads.tsx`; admin requests list/detail, BFF allow-list.

### Tests
- `RequestEngineTest` (3): types/vertical/lifecycle for RFQ, service, task and assistance requests + filters + `requests` view;
  attachments — content-type detection, size limit, unsafe names cleaned, other customers blocked, signed link works, tampered and
  expired links 404, invited partner sees only shared files, uninvited partner blocked, delete, timeline; agent child requests with
  a parent, foreign parent refused.
- **Full suite: 50 passed, 0 failed.** Typecheck passes (customer, partner, agent, admin); admin production build passes.
- Live check on the dev backend: Ravi's existing hardware requests backfilled as quotation requests; uploaded a photo shared
  with partners; signed link 200, tampered link 404; Sri Sai Hardware sees it on the lead; timeline shows the upload.
- Browser (phone size): My Requests type/group filters, request type on detail, attachments card. The native file chooser can't
  be driven from the browser pane, so the upload itself was verified through the API and tests.

### Rollback
Revert the Stage 4 commit; V11 is additive (older code ignores the new columns, view and table).

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
