# BUZZER Transformation — Progress Report

Plan: [transformation-plan.md](transformation-plan.md) · Audit: [transformation-audit.md](transformation-audit.md)

| Stage | Status |
|---|---|
| 1. Audit | ✅ Done |
| 2. Architecture & database impact | ✅ Done, approved (all 7 recommended decisions) |
| 3. Unified UI/UX & navigation | ✅ Done |
| 4. Request engine | ✅ Done |
| 5. Marketplace & RFQ | ✅ Done |
| 6. Services & provider workflows | ✅ Done |
| **7. Agent & personal assistance** | ✅ **Done** — acceptance tests F, G, H, M pass |
| 8. Tasks & errands | ⏭ Next |
| 9–10 | Planned |

---

## Stage 7 — Agent & personal assistance

### Delivered

**Drafts the customer approves (Vertical C rule)**
- An agent prepares, for a customer in their portfolio: a **quotation request** (items, brand, quantity, bulk), **products from one shop**
  (listing offers and quantities) or a **service booking** (service form, address, free slot, chosen professional). Each is checked when
  sent (portfolio, address ownership, category, one shop, stock / MOQ, provider free at the slot, service form valid) and shown to the
  customer with a preview and total.
- **Only the customer can approve** — in their own app. Approval runs the normal customer flow under the customer's identity: the RFQ is
  sent to shops, the booking is made (customer picks pay after service / online), or the products go into the customer's cart for them to
  check out and pay. The customer can also say no (with a reason). Drafts expire after 72 hours (setting). The resulting request's timeline
  says "Prepared by agent X and approved by the customer".
- **Agents can never approve, accept a quote, check out or pay**: all of those endpoints are customer-only (403 for agents), and the
  approval checks the draft belongs to the caller.

**Agent activity log**
- Every call and message on a request, drafts sent / approved / rejected / withdrawn, requests created for a customer, escalations
  received and cover for an away colleague are logged automatically; agents add notes, calls and **vendor follow-ups** (linked to a
  partner) by hand. Managers see their team's log; admins see each agent's log.

**Workspace across all verticals**
- New agent dashboard section: pending RFQs, vendor follow-ups (no quote after 2 hours, setting), active orders, service bookings, tasks,
  waiting on customer, drafts awaiting approval, escalated to me, completed (30 days); open / done per business area; **first-response
  countdown**; SLA met %, drafts approved; recent activity.

**Eligibility-based dedicated agents (F)**
- Agents have **skills per business area** and an **accepts dedicated customers** switch (off for trainees — they still take one-off and
  escalated requests). Dedicated allocation only uses eligible agents and ranks: locality → first language → trained for the areas the
  customer is interested in → languages in common → availability → workload; managers last; backup allocated alongside.

**Backup coverage (G)**
- When the dedicated agent is offline / on leave, a new request goes straight to the **backup** (else the best available agent): the
  timeline says who is covering, the backup is notified and sees it in "escalated to me". The existing hand-over of open requests when an
  agent goes offline stays.

**SLA per business area (H)**
- First-response time per vertical (pilot: Services 60, Tasks 60, Shop & Buy 120, Personal Assistance 120 minutes), editable by admins;
  HIGH / URGENT use the shorter of that and the priority rule. Breaches escalate to the backup → best available agent → manager (existing
  engine), now also logged on the receiving agent's activity.

**Apps**
- Agent: workspace on the dashboard; on a customer — **Prepare for approval** (quotation request / products from a shop / service booking
  with slots, professionals and the service form), drafts with status (withdraw while pending) and the activity log with a quick logger.
- Customer: Home banner "N items waiting for your approval", **Approvals** list and detail (preview, total, payment choice for bookings,
  approve / say no).
- Admin: Settings → **First response time per business area**; Agents → skills column and **Skills & log** (areas, dedicated switch,
  recent activity).

### Database migration (additive)
- `V17__agent_drafts_activity_sla.sql` — `agents.accepts_dedicated`, `agent_skills` (backfill: all areas), `vertical_sla`, `agent_drafts`,
  `agent_activities`, draft / follow-up settings.
- Dev seed `V1005__dev_agent_skills.sql` — skills for the seeded agents.

### API contracts (new)

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/v1/agent/workspace` | agent | Queues, by-area counts, SLA countdown, metrics, recent activity |
| POST | `/api/v1/agent/customers/{customerId}/drafts` | agent (portfolio) | `kind` RFQ / PURCHASE / BOOKING with `rfq` / `purchase` / `booking` |
| GET | `/api/v1/agent/drafts?status&customerId` | agent (+ team for managers) | |
| POST | `/api/v1/agent/drafts/{id}/cancel` | agent | Withdraw while pending |
| GET | `/api/v1/agent/customers/{customerId}/services/{serviceId}/slots`, `/providers` | agent (portfolio) | For booking drafts |
| GET / POST | `/api/v1/agent/activity` | agent | List (own / team); log CALL, NOTE, VENDOR_FOLLOW_UP |
| GET | `/api/v1/customer/drafts?status`, `/{id}` | customer | Own drafts with preview |
| POST | `/api/v1/customer/drafts/{id}/approve` | customer only | `payment_method` for bookings; returns `next` (REQUEST / PRODUCT_CART) |
| POST | `/api/v1/customer/drafts/{id}/reject` | customer only | Optional `note` |
| GET / PUT | `/api/v1/admin/sla/verticals[/{code}]` | admin (PUT: ADMIN) | `first_response_minutes` 15-480 |
| GET / PUT | `/api/v1/admin/agents/{id}/eligibility` | admin (PUT: ADMIN / AGENT_MANAGER) | `verticals`, `accepts_dedicated` |
| GET | `/api/v1/admin/agents/{id}/activity`, `/api/v1/admin/requests/{id}/agent-activity` | admin | |

### Tests
- `AgentAssistanceTest` (4):
  - **F** an agent not taking dedicated customers is never allocated; once allowed, the Hindi speaker gets them; with equal languages the
    agent trained for Shop & Buy gets the Shop & Buy customer; validation and permissions.
  - **G** primary on leave → new request covered by the backup (timeline, activity, workspace, access); primary back → no cover.
  - **H** Services request gets 60 minutes; admin sets 90 → new requests 90; URGENT → 30; invalid / non-admin changes refused; workspace
    countdown; breach escalates to the backup with an activity entry.
  - **M + drafts** RFQ, booking and purchase drafts; not for customers outside the portfolio; agent gets 403 on approve, quote acceptance,
    payment simulation and checkout; another customer gets 404; customer approves each (RFQ sent to shops with the "prepared by agent"
    timeline entry, booking assigned to the professional, products in the cart); double approval and expired drafts refused; activity log
    and workspace metrics.
- **Full suite: 63 passed, 0 failed.** Typecheck passes (customer, partner, agent, admin); admin production build passes.
- Live check on the dev backend: Priya Nair's workspace → RFQ draft for Anjali Menon → agent approval blocked (403) → Anjali approves → request
  created → activity shows sent / approved; SLA per area listed.
- Browser (phone size): agent dashboard workspace; customer page with drafts and activity log; Prepare for approval (booking search now
  shows only bookable services and asks for an address first).

### Rollback
Revert the Stage 7 commit; V17 is additive (older code ignores the new tables / column; SLA falls back to the per-priority rules).

### Not in Stage 7 (by plan)
Task drafts (with the task domain in Stage 8); agent membership plans (Stage 9).

---

## Stage 6 — Services & provider workflows

### Delivered

**Instant booking (customer picks the time and the professional)**
- For every fixed / starting-from / per-unit priced service: **slot picker** for the next 7 days (local time, 1-hour grid, earliest 90 minutes
  ahead) showing how many professionals are free and the lowest price per slot.
- **Provider matches** at the chosen slot: only approved, KYC-verified, available service providers with an approved capability, covering
  the customer's locality / within their service radius and **free in their calendar**. Each shows rating, jobs done, distance and price
  (their BUZZER-approved own price, else the listed price).
- **Book**: the service's detail form, pay **after the service** (where allowed) or **online**; idempotent. Under the hood it reuses the
  existing lifecycle — a price-list quotation from the chosen professional is accepted and paid, and the professional gets the exclusive
  assignment (booking mode `PROVIDER_SELECTED`, request type `SERVICE_BOOKING`).
- **No double booking**: bookings of one professional are serialised (row lock) and re-checked; the second customer for the same slot gets
  409 `SLOT_UNAVAILABLE`. Slots off the grid or outside the booking window are refused.

**Accept / decline**
- The professional **accepts or declines** (existing confirm / decline) within the confirmation window (60 minutes, setting
  `assignment.confirm_timeout_minutes`). Unanswered bookings are **released automatically** (sweep every minute).
- After a decline or no answer the customer is notified and **chooses another professional for the same time at the price already booked**
  (any payment carries over); professionals already tried are not offered again.

**Provider calendar**
- Weekly **working hours** (up to 3 windows a day), **days off**, upcoming jobs. Bookings respect hours, days off, existing jobs and a
  30-minute **travel buffer** (setting). Existing providers start with Monday–Saturday 9:00–19:00. Adding days off that clash with booked
  jobs returns those jobs so the provider can reschedule.

**Completion evidence**
- Professionals add **before / after photos** to a job (private to the customer, their agent and admins; signed links as in Stage 4) next
  to the existing START / COMPLETE OTPs. Setting `booking.completion_photo_required` makes an after photo mandatory before asking the
  customer to confirm completion. Every photo is on the timeline.

**Apps**
- Customer: **Book instantly** from the service details sheet and the request form → address, day, time, professional, details,
  payment; request page shows "Booked for", a waiting-for-acceptance note, and **Choose another professional** after a decline; photos are
  labelled "Before the work" / "Finished work".
- Partner: **My calendar** (from My jobs) — working hours per weekday, days off, upcoming jobs; job list shows the slot and "accept by";
  job detail adds **work photos** (before / after).
- Admin: partner page shows working hours, days off and upcoming jobs; request attachments show the professional's photos.

### Database migration (additive)
- `V16__provider_availability_booking.sql` — `partner_availability`, `partner_time_off` (+ default hours backfill),
  `service_items.duration_minutes`, booking mode `PROVIDER_SELECTED`, `request_attachments.purpose` and uploader role `PARTNER`, booking
  settings.
- Dev seed `V1004__dev_provider_calendars.sql` — hours for the seeded providers (Quick Fix Plumbers also Sunday mornings).

### API contracts (new / changed)

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/v1/customer/services/{id}/slots?addressId&from&days` | customer | Days → slots with free-provider count and lowest price |
| GET | `/api/v1/customer/services/{id}/providers?addressId&slot&quantity` | customer | Free providers at the slot: rating, jobs done, distance, price |
| POST | `/api/v1/customer/bookings` | customer | `service_item_id, partner_id, slot_start, address_id, answers, payment_method, quantity`; `Idempotency-Key`; 409 `SLOT_UNAVAILABLE` |
| GET | `/api/v1/customer/requests/{id}/alternatives` | customer (owner) | Other free providers for the same slot |
| POST | `/api/v1/customer/requests/{id}/rebook` | customer (owner) | `partner_id`; same slot, same price |
| GET / PUT | `/api/v1/partner/schedule` | partner | Weekly hours (`weekly: [{weekday, start_time, end_time}]`), days off, upcoming jobs |
| POST / DELETE | `/api/v1/partner/schedule/time-off[/{id}]` | partner | `starts_on, ends_on, reason`; response lists clashing jobs |
| POST / GET | `/api/v1/partner/assignments/{id}/evidence` | partner (own job) | multipart `file`, `purpose` BEFORE / AFTER |
| POST | `/api/v1/partner/assignments/{id}/confirm`, `/decline` | partner | Existing endpoints = accept / reject a booking |
| GET | `/api/v1/admin/partners/{id}/schedule` | admin | Provider calendar |
| GET | `/api/v1/partner/assignments` | partner | Adds `slot_start`, `slot_end`, `booking_mode`, `expires_at`, `locality_name` |

### Tests
- `ServiceBookingFlowTest` (4):
  - **D** slots → providers → book (pay after service) → idempotent retry → second customer blocked from the same professional and slot →
    professional declines → customer notified → alternatives exclude him → rebook another at the same price → accept → booking at the
    slot → on the calendar → arrive / START OTP / before photo → completion refused without an after photo (setting on) → after photo →
    COMPLETE OTP → completed / settled; customer sees both photos; timeline tells the story; other partners can't add photos.
  - Calendar: changed hours and a day off remove the provider from that slot; invalid hours refused; admin sees the calendar.
  - Unanswered booking released by the sweep → `PARTNER_DECLINED`, assignment `EXPIRED`, slot free again.
  - Quote-only services can't be booked instantly; off-grid times and other customers' addresses refused.
- **Full suite: 59 passed, 0 failed.** Typecheck passes (customer, partner, agent, admin); admin production build passes.
- Live check on the dev backend: Tap Repair slots for 7 days (Sunday only Quick Fix) → 3 plumbers compared → booked → accepted → calendar
  shows the job → arrive / START OTP / after photo / COMPLETE OTP → settled with the photo on the request.
- Browser (phone size): service details → Book a time & professional → address, days, slots with counts, professionals with jobs done and
  distance, details form, payment, booking summary.

### Rollback
Revert the Stage 6 commit; V16 is additive (older code ignores the new tables, column and settings; `PROVIDER_SELECTED` requests keep
working through the existing request lifecycle).

### Not in Stage 6 (by plan)
Partner-proposed alternative times for instant bookings (they can use the existing reschedule after accepting); provider ratings by
service; agent drafts for bookings (Stage 7).

---

## Stage 5 — Marketplace & RFQ

### Delivered

**Partner types and shops**
- A partner can hold several types: **retail shop, wholesaler, distributor, service provider, task partner**. Shop types need admin
  approval; approving a registration approves the types it asked for, and an admin can suspend one type on its own (the shop disappears,
  data is kept). Existing partners were backfilled from their categories.
- **Shop locations**: address, locality / coordinates, service radius, opening hours, pickup and/or delivery, delivery fee, free-delivery
  amount, minimum order. Every existing partner got a primary location.

**Products, listings and search**
- Shared **product catalogue** (brands, products with specifications and GST rate, variants such as colour temperature). Admins manage it;
  shops can **propose** a missing product (admin approves it together with the shop's listing). Starter catalogue: every Hardware &
  Supplies item plus branded products; 9 new Shop & Buy categories (medical stays **off** until licensing — decision 6).
- **Vendor listings**: the shop's own price, MRP, stock status (in stock / few left / out of stock / on request, with "updated x ago" —
  BUZZER does not claim real-time stock), minimum order, optional bulk price tier, lead time. New listings are reviewed by admin;
  price and stock edits are instant. **Excel** download / upload for many listings at once.
- **Visibility rule** (one SQL fragment for every screen): shop approved + KYC verified + open + approved shop role + approved category,
  listing and product active, category and vertical switched on.
- **Search**: full-text with English stemming ("bulbs" → bulb) + fuzzy matching via `pg_trgm` ("hamer" → hammer) + brand match.
  Universal search now also returns products.
- **Shop discovery by distance** (haversine from the customer's address or locality); **price comparison** on every product page with
  the lowest price and nearest shop flagged.

**Direct purchase (A)**
- **One shop per cart** (adding another shop's item asks to empty the cart). Every cart view re-prices against live listings;
  checkout refuses changed prices until the customer accepts them, and refuses unavailable / below-MOQ items.
- Checkout: **pickup or shop delivery** (delivery area, fee, free-above and minimum order checked), **pay online or at pickup / on
  delivery**, `Idempotency-Key` (a retried checkout returns the same order).
- **Orders** with **immutable snapshots** of prices, GST, product details, shop and fulfilment — enforced by database triggers (order lines
  and money can't be updated or deleted, orders can't be deleted). Order lifecycle `PAYMENT_PENDING → PAID / PLACED → VENDOR_CONFIRMED →
  READY_FOR_PICKUP | OUT_FOR_DELIVERY → COMPLETED`, plus `CANCELLED` (customer before the shop confirms; admin anytime) and
  `VENDOR_REJECTED`. Each order is a request-hub row (`PRODUCT_PURCHASE`, lifecycle `ORDER`), so it appears in My Requests with the same
  timeline.
- Payments use the existing gateway path: server-created order, **only the verified webhook marks it paid**; failure keeps the order and
  the customer can retry. Cancel / decline after payment opens a refund for Finance to approve.
- Hand-over with a **4-digit pickup / delivery code** shown only to the customer; wrong codes are counted (locked after 5, ops alert).
  Completion posts commission, tax invoice, ledger entries and a payout (or records cash collected) with the existing commission rules.

**Quotation requests (B, C)**
- Customers send an **RFQ**: items with preferred brand, specifications, quantity, unit and notes; delivery or pickup; needed-by; bulk
  flag; photos / BOQ via Stage 4 attachments. Cart "Get quotes" for Shop & Buy items creates the same structured RFQ.
- Sent automatically to **5–10 shops** — count from `rfq_invite_rules` (category / city / locality) or the `rfq.invite_count` setting (7).
  Only approved shop types, within their service radius; **wholesalers / distributors first for bulk**; then nearest, then shops listing
  that subcategory, then rating.
- Shops quote **item by item**: brand / specs offered, availability, unit price, **discount %, GST %, MOQ, lead time**, delivery charge,
  validity, **terms and exclusions** (stored in `quotation_items`).
- Customer **compares side by side** (lowest complete quote, lowest total, most items, fastest) and **accepts one** — under a row lock
  with a unique index: the request turns into an order with that shop, other quotes are closed and those shops are told.

**Apps**
- Customer: Shop & Buy (search, category chips, shops near you, products with price range and shop count), product page with offers,
  shop profile, one-shop cart and checkout, order tracking (steps, code, payment / test-mode buttons, cancel, timeline), RFQ form,
  side-by-side comparison with accept; Orders tab and Home show product orders; Explore / Search / Create a Request route here.
- Partner: role-based tabs — shops get a **Shop** tab (orders with confirm / decline / ready / out for delivery / complete-with-code,
  products & stock with one-tap stock status, Excel upload / download, add from catalogue, propose a product, locations and partner
  types); the quote form has brand, discount, GST, MOQ, lead time, terms and exclusions; dashboard shows new orders.
- Agent: request detail shows the linked order (agents can't pay, accept or cancel for the customer).
- Admin: new **Marketplace** section — Orders (list, detail with frozen snapshot, payments, timeline, cancel), Shops & vendors (approve /
  suspend partner types), Products & listings (review listings, approve proposed products, create / edit catalogue products);
  Settings → **RFQ invite rules**; request detail links its order.

### Database migrations (additive)
- `V12__partner_roles_locations.sql` — `partner_roles`, `partner_locations` + backfill.
- `V13__products_listings.sql` — `pg_trgm`, 9 marketplace categories, `brands`, `products`, `product_variants`, `vendor_listings`, search
  indexes, starter catalogue, marketplace settings.
- `V14__orders.sql` — `product_carts`, `product_cart_items`, `orders`, `order_items`, immutability triggers, `payments.order_id` and
  purpose `ORDER`, order settings.
- `V15__rfq_items.sql` — `request_items`, `quotation_items` (+ backfill from existing quotes), `quotations.terms / lead_time_days`,
  `rfq_invite_rules`, RFQ settings.
- Dev seed `V1003__dev_marketplace.sql` — Deccan Building Supplies (wholesaler), FreshMart Supermarket, Vijaya Electronics (pending), shop
  locations, grocery products, 40 listings.

> Production note: `CREATE EXTENSION pg_trgm` needs a database role allowed to create extensions (managed PostgreSQL services allow
> `pg_trgm`).

### API contracts (new)

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/v1/marketplace/shops?lat&lng&localityId&categoryId&q` | public | Nearest first, within `marketplace.search_radius_km` |
| GET | `/api/v1/marketplace/shops/{id}` | public | Profile, locations, categories |
| GET | `/api/v1/marketplace/products?q&categoryId&subcategoryId&shopId&lat&lng&page&size` | public | Shop count, price range, nearest km |
| GET | `/api/v1/marketplace/products/{id}?lat&lng` | public | Specs, variants, offers (cheapest / nearest) |
| GET / DELETE | `/api/v1/customer/product-cart?addressId` | customer | Re-priced cart, pickup / delivery options |
| POST | `/api/v1/customer/product-cart/items` | customer | `listing_id`, `quantity`, `replace_cart`; 409 `CART_VENDOR_MISMATCH` |
| PUT | `/api/v1/customer/product-cart/items/{listingId}` | customer | Quantity (0 removes) |
| POST | `/api/v1/customer/product-cart/accept-prices` | customer | After a price change |
| POST | `/api/v1/customer/orders` | customer | Checkout, `Idempotency-Key`; 409 `PRICE_CHANGED` / `ITEM_UNAVAILABLE` |
| GET | `/api/v1/customer/orders`, `/{id}` | customer | Own orders only |
| POST | `/api/v1/customer/orders/{id}/pay`, `/cancel` | customer | Retry payment; cancel before the shop confirms |
| POST | `/api/v1/customer/rfqs` | customer | Structured quotation request, `Idempotency-Key` |
| GET | `/api/v1/customer/requests/{id}/compare` | customer (owner) | Side-by-side comparison |
| POST | `/api/v1/customer/requests/{id}/quotations/{qid}/accept` | customer | For RFQs now returns `{order, payment}`; body adds `fulfilment` |
| GET | `/api/v1/partner/shop`, `/partner/locations`, `/partner/listings`, `/partner/catalogue/products` | partner | Own data only |
| POST / PUT | `/api/v1/partner/locations[/{id}]`, POST `/partner/listings`, `/partner/products`, `/partner/roles` | partner | |
| GET / POST | `/api/v1/partner/listings/excel` | partner | Template download / upload |
| GET | `/api/v1/partner/orders?group=NEW|ACTIVE|DONE`, `/{id}` | partner | Own orders; unpaid online orders hidden |
| POST | `/api/v1/partner/orders/{id}/confirm|reject|ready|dispatch|complete` | partner | `complete` needs the customer's code |
| POST | `/api/v1/partner/leads/{id}/item-quote` | partner | Adds per line `brand, specs, discount_percent, tax_percent, moq, lead_time_days`; quote `terms, exclusions, lead_time_days` |
| GET / POST / PUT | `/api/v1/admin/marketplace/shops`, `/shops/{id}/roles/{role}`, `/products`, `/products/{id}`, `/products/{id}/review`, `/products/{id}/variants`, `/listings`, `/listings/{id}/review`, `/brands`, `/rfq-rules` | admin (changes: ADMIN) | |
| GET / POST | `/api/v1/admin/orders`, `/{id}`, `/{id}/cancel` | admin | Cancel: ADMIN / SUPPORT |

### Tests
- `MarketplaceFlowTest` (5):
  - **A** search with stemming and typos, price comparison and distance, one shop per cart, price change refused until accepted,
    idempotent checkout, failed then retried online payment, shop confirm / dispatch / wrong code / right code, settlement (ledger + payout),
    My Requests status, immutable snapshots; pay-at-pickup order with MOQ, bulk tier and customer cancellation.
  - **B + C** RFQ to several shops, shops see brand / specs, item-wise quote with discount + GST + MOQ + lead time + terms
    (total 8,034.90 checked to the paisa), comparison picks the complete lowest quote, accept one → order, second acceptance refused,
    other quote rejected; bulk RFQ with an invite rule of 1 goes to the wholesaler only.
  - **I** a new shop registers → can't list before approval → admin approves registration, KYC, category → listing waits for review →
    visible after approval → suspended role hides it → proposed product live after admin approval.
  - **M** other shops / customers get 404 on orders, leads and comparisons; partners can't call admin endpoints.
- `DirectQuoteFlowTest` updated on purpose: an accepted shop quote for Shop & Buy items now creates an **order** (one shop) instead of a
  service job.
- **Full suite: 55 passed, 0 failed.** Typecheck passes (customer, partner, agent, admin); admin production build passes.
- Live check on the dev backend: search "mcb" → 2 shops compared → one-shop cart → pay-at-pickup order → shop confirmed, ready, completed
  with the code; bulk RFQ sent to 3 shops, wholesaler first.
- Browser (phone size): Shop & Buy, product comparison, one-shop dialog, cart → order with pickup code; partner Shop tab (new order,
  products & stock); admin Products & listings and Orders.

### Rollback
Switch the Shop & Buy vertical off in Admin → Settings (shops, products, carts and checkout disappear; data kept). Code: revert the
Stage 5 commit — V12–V15 are additive; older code ignores the new tables and columns. RFQs created by the new flow keep working as
ordinary requests.

### Not in Stage 5 (by plan)
BUZZER-arranged delivery (linked task, Stage 8); vendor subscriptions, featured listings and order commission statements (Stage 9);
returns and disputes (Stage 9); real payment gateway (credentials pending).

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
