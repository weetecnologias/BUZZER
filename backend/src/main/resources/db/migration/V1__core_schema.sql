-- BUZZER core schema (Phase 1)
-- Modules: identity, location, catalogue, customer, agent, partner, request, quotation,
-- assignment, booking/execution, payment, ledger, commission, notification/outbox, ops, audit.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------
-- Human-readable business ID sequences (BZ-REQ-2026-00001234 etc.)
-- ---------------------------------------------------------------------------
CREATE SEQUENCE seq_request_number START 1;
CREATE SEQUENCE seq_invitation_number START 1;
CREATE SEQUENCE seq_quotation_number START 1;
CREATE SEQUENCE seq_assignment_number START 1;
CREATE SEQUENCE seq_booking_number START 1;
CREATE SEQUENCE seq_invoice_number START 1;
CREATE SEQUENCE seq_payment_number START 1;
CREATE SEQUENCE seq_refund_number START 1;
CREATE SEQUENCE seq_payout_number START 1;

-- ---------------------------------------------------------------------------
-- Platform settings (configurable thresholds, SLAs, feature switches)
-- ---------------------------------------------------------------------------
CREATE TABLE platform_settings (
    key          VARCHAR(100) PRIMARY KEY,
    value        TEXT         NOT NULL,
    description  TEXT,
    updated_by   UUID,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Location
-- ---------------------------------------------------------------------------
CREATE TABLE cities (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(40)  NOT NULL UNIQUE,
    name        VARCHAR(120) NOT NULL,
    state       VARCHAR(120) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE localities (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id     UUID         NOT NULL REFERENCES cities(id),
    name        VARCHAR(160) NOT NULL,
    pincode     VARCHAR(10),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (city_id, name)
);
CREATE INDEX ix_localities_city ON localities(city_id);

-- ---------------------------------------------------------------------------
-- Identity
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone               VARCHAR(20)  NOT NULL UNIQUE,
    full_name           VARCHAR(160),
    email               VARCHAR(200),
    preferred_language  VARCHAR(20),
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','DELETED')),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE user_roles (
    user_id     UUID        NOT NULL REFERENCES users(id),
    role        VARCHAR(30) NOT NULL CHECK (role IN ('CUSTOMER','PARTNER','AGENT','AGENT_MANAGER','SUPPORT','FINANCE','ADMIN','SUPER_ADMIN')),
    city_id     UUID REFERENCES cities(id),
    granted_by  UUID,
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role)
);

CREATE TABLE otp_challenges (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone        VARCHAR(20) NOT NULL,
    purpose      VARCHAR(30) NOT NULL,
    code_hash    VARCHAR(200) NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    attempts     INT         NOT NULL DEFAULT 0,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_otp_phone_created ON otp_challenges(phone, created_at DESC);

CREATE TABLE auth_sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID         NOT NULL REFERENCES users(id),
    app                 VARCHAR(20)  NOT NULL,
    refresh_token_hash  VARCHAR(200) NOT NULL UNIQUE,
    expires_at          TIMESTAMPTZ  NOT NULL,
    revoked_at          TIMESTAMPTZ,
    user_agent          VARCHAR(300),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_sessions_user ON auth_sessions(user_id);

CREATE TABLE terms_versions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audience        VARCHAR(20)  NOT NULL CHECK (audience IN ('CUSTOMER','PARTNER','AGENT')),
    version         VARCHAR(20)  NOT NULL,
    title           VARCHAR(200) NOT NULL,
    summary         TEXT         NOT NULL,
    document_url    VARCHAR(500),
    effective_from  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    UNIQUE (audience, version)
);

CREATE TABLE terms_acceptances (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID        NOT NULL REFERENCES users(id),
    terms_version_id  UUID        NOT NULL REFERENCES terms_versions(id),
    accepted_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip_address        VARCHAR(64),
    user_agent        VARCHAR(300),
    UNIQUE (user_id, terms_version_id)
);

-- ---------------------------------------------------------------------------
-- Catalogue: Category -> Subcategory -> Service item -> Variants -> Dynamic form
-- ---------------------------------------------------------------------------
CREATE TABLE categories (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code               VARCHAR(80)  NOT NULL UNIQUE,
    name               VARCHAR(160) NOT NULL,
    description        TEXT,
    icon               VARCHAR(80),
    image_url          VARCHAR(500),
    sort_order         INT          NOT NULL DEFAULT 0,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    workflow_template  VARCHAR(40)  NOT NULL DEFAULT 'MENU_DRIVEN'
        CHECK (workflow_template IN ('MENU_DRIVEN','PROPERTY_REQUIREMENT','CATEGORY_SPECIAL_REQUEST','GLOBAL_SPECIAL_REQUEST','JOB_RECRUITMENT','EVENT_BRIEF','API_PRICED')),
    is_global_special  BOOLEAN      NOT NULL DEFAULT FALSE,
    advisory_notice    TEXT,
    default_form_schema JSONB,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE category_cities (
    category_id  UUID NOT NULL REFERENCES categories(id),
    city_id      UUID NOT NULL REFERENCES cities(id),
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (category_id, city_id)
);

CREATE TABLE subcategories (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id  UUID         NOT NULL REFERENCES categories(id),
    code         VARCHAR(80)  NOT NULL,
    name         VARCHAR(160) NOT NULL,
    description  TEXT,
    icon         VARCHAR(80),
    sort_order   INT          NOT NULL DEFAULT 0,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (category_id, code)
);
CREATE INDEX ix_subcategories_category ON subcategories(category_id, sort_order);

CREATE TABLE service_items (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subcategory_id   UUID          NOT NULL REFERENCES subcategories(id),
    code             VARCHAR(80)   NOT NULL,
    name             VARCHAR(160)  NOT NULL,
    description      TEXT,
    icon             VARCHAR(80),
    pricing_type     VARCHAR(30)   NOT NULL DEFAULT 'QUOTE_REQUIRED'
        CHECK (pricing_type IN ('FIXED','STARTING_FROM','RANGE','PER_UNIT','HOURLY','VISIT_PLUS_LABOUR','PACKAGE','QUOTE_REQUIRED','API_PRICED')),
    base_price       NUMERIC(12,2),
    min_price        NUMERIC(12,2),
    max_price        NUMERIC(12,2),
    unit             VARCHAR(40),
    is_other         BOOLEAN       NOT NULL DEFAULT FALSE,
    pay_on_delivery_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    form_schema      JSONB,
    sort_order       INT           NOT NULL DEFAULT 0,
    active           BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (subcategory_id, code)
);
CREATE INDEX ix_service_items_sub ON service_items(subcategory_id, sort_order);

CREATE TABLE service_variants (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_item_id  UUID          NOT NULL REFERENCES service_items(id),
    name             VARCHAR(160)  NOT NULL,
    price_delta      NUMERIC(12,2) NOT NULL DEFAULT 0,
    sort_order       INT           NOT NULL DEFAULT 0,
    active           BOOLEAN       NOT NULL DEFAULT TRUE
);

-- ---------------------------------------------------------------------------
-- Customers
-- ---------------------------------------------------------------------------
CREATE TABLE customer_profiles (
    user_id                UUID PRIMARY KEY REFERENCES users(id),
    city_id                UUID REFERENCES cities(id),
    locality_id            UUID REFERENCES localities(id),
    preferred_language     VARCHAR(20),
    notify_push            BOOLEAN     NOT NULL DEFAULT TRUE,
    notify_sms             BOOLEAN     NOT NULL DEFAULT TRUE,
    notify_whatsapp        BOOLEAN     NOT NULL DEFAULT FALSE,
    notify_email           BOOLEAN     NOT NULL DEFAULT FALSE,
    registered_by_agent_id UUID,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE addresses (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL REFERENCES users(id),
    label        VARCHAR(20)  NOT NULL CHECK (label IN ('HOME','WORK','OTHER')),
    line1        VARCHAR(250) NOT NULL,
    line2        VARCHAR(250),
    landmark     VARCHAR(200),
    city_id      UUID         NOT NULL REFERENCES cities(id),
    locality_id  UUID REFERENCES localities(id),
    pincode      VARCHAR(10)  NOT NULL,
    latitude     NUMERIC(10,7),
    longitude    NUMERIC(10,7),
    is_default   BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_addresses_user ON addresses(user_id) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- Agents and allocation
-- ---------------------------------------------------------------------------
CREATE TABLE agents (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID         NOT NULL UNIQUE REFERENCES users(id),
    display_name   VARCHAR(160) NOT NULL,
    photo_url      VARCHAR(500),
    city_id        UUID         NOT NULL REFERENCES cities(id),
    languages      VARCHAR(200) NOT NULL DEFAULT 'en',
    availability   VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE' CHECK (availability IN ('AVAILABLE','BUSY','OFFLINE','ON_LEAVE')),
    max_customers  INT          NOT NULL DEFAULT 300,
    shift_start    TIME         NOT NULL DEFAULT '09:00',
    shift_end      TIME         NOT NULL DEFAULT '21:00',
    is_manager     BOOLEAN      NOT NULL DEFAULT FALSE,
    manager_id     UUID REFERENCES agents(id),
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE agent_localities (
    agent_id     UUID NOT NULL REFERENCES agents(id),
    locality_id  UUID NOT NULL REFERENCES localities(id),
    PRIMARY KEY (agent_id, locality_id)
);

CREATE TABLE customer_agent_links (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_user_id  UUID        NOT NULL REFERENCES users(id),
    agent_id          UUID        NOT NULL REFERENCES agents(id),
    link_role         VARCHAR(20) NOT NULL CHECK (link_role IN ('PRIMARY','BACKUP','TEMPORARY')),
    active            BOOLEAN     NOT NULL DEFAULT TRUE,
    assigned_by       UUID,
    reason            VARCHAR(300),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at          TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_customer_agent_active_role ON customer_agent_links(customer_user_id, link_role) WHERE active;
CREATE INDEX ix_customer_agent_agent ON customer_agent_links(agent_id) WHERE active;

-- ---------------------------------------------------------------------------
-- Partners
-- ---------------------------------------------------------------------------
CREATE TABLE partners (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID         NOT NULL UNIQUE REFERENCES users(id),
    partner_type         VARCHAR(20)  NOT NULL CHECK (partner_type IN ('INDIVIDUAL','BUSINESS')),
    display_name         VARCHAR(160) NOT NULL,
    business_name        VARCHAR(200),
    contact_phone        VARCHAR(20)  NOT NULL,
    email                VARCHAR(200),
    city_id              UUID         NOT NULL REFERENCES cities(id),
    address              TEXT,
    registration_status  VARCHAR(20)  NOT NULL DEFAULT 'SUBMITTED' CHECK (registration_status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','SUSPENDED')),
    kyc_status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (kyc_status IN ('PENDING','SUBMITTED','VERIFIED','REJECTED','EXPIRED')),
    kyc_expires_on       DATE,
    availability         VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE' CHECK (availability IN ('AVAILABLE','UNAVAILABLE')),
    rating_avg           NUMERIC(3,2) NOT NULL DEFAULT 0,
    rating_count         INT          NOT NULL DEFAULT 0,
    review_note          TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_partners_city ON partners(city_id);

CREATE TABLE partner_capabilities (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_id       UUID        NOT NULL REFERENCES partners(id),
    subcategory_id   UUID        NOT NULL REFERENCES subcategories(id),
    service_item_id  UUID REFERENCES service_items(id),
    status           VARCHAR(20) NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','APPROVED','REJECTED','REVOKED')),
    reviewed_by      UUID,
    reviewed_at      TIMESTAMPTZ,
    review_note      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_partner_capability ON partner_capabilities(partner_id, subcategory_id, COALESCE(service_item_id, '00000000-0000-0000-0000-000000000000'::uuid));

CREATE TABLE partner_service_areas (
    partner_id   UUID NOT NULL REFERENCES partners(id),
    locality_id  UUID NOT NULL REFERENCES localities(id),
    PRIMARY KEY (partner_id, locality_id)
);

CREATE TABLE partner_kyc_documents (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_id    UUID         NOT NULL REFERENCES partners(id),
    doc_type      VARCHAR(40)  NOT NULL,
    doc_number_masked VARCHAR(40),
    storage_key   VARCHAR(500),
    status        VARCHAR(20)  NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED','VERIFIED','REJECTED')),
    reviewed_by   UUID,
    reviewed_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Service requests
-- ---------------------------------------------------------------------------
CREATE TABLE service_requests (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_number            VARCHAR(40)  NOT NULL UNIQUE,
    customer_user_id          UUID         NOT NULL REFERENCES users(id),
    created_by_user_id        UUID         NOT NULL REFERENCES users(id),
    created_via               VARCHAR(20)  NOT NULL CHECK (created_via IN ('CUSTOMER_APP','AGENT_APP','ADMIN')),
    consent_method            VARCHAR(30),
    consent_at                TIMESTAMPTZ,
    customer_confirmed_at     TIMESTAMPTZ,
    category_id               UUID         NOT NULL REFERENCES categories(id),
    subcategory_id            UUID REFERENCES subcategories(id),
    service_item_id           UUID REFERENCES service_items(id),
    city_id                   UUID         NOT NULL REFERENCES cities(id),
    locality_id               UUID REFERENCES localities(id),
    address_id                UUID REFERENCES addresses(id),
    address_snapshot          JSONB,
    answers                   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    description               TEXT,
    preferred_start           TIMESTAMPTZ,
    preferred_end             TIMESTAMPTZ,
    priority                  VARCHAR(10)  NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT')),
    status                    VARCHAR(40)  NOT NULL,
    agent_id                  UUID REFERENCES agents(id),
    escalated_to_agent_id     UUID REFERENCES agents(id),
    escalated_at              TIMESTAMPTZ,
    first_agent_response_at   TIMESTAMPTZ,
    sla_due_at                TIMESTAMPTZ,
    resolved_at               TIMESTAMPTZ,
    idempotency_key           VARCHAR(100),
    cancel_reason             TEXT,
    version                   INT          NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_request_idempotency ON service_requests(customer_user_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_requests_customer ON service_requests(customer_user_id, created_at DESC);
CREATE INDEX ix_requests_agent_status ON service_requests(agent_id, status);
CREATE INDEX ix_requests_status_sla ON service_requests(status, sla_due_at);

CREATE TABLE request_status_history (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id     UUID        NOT NULL REFERENCES service_requests(id),
    from_status    VARCHAR(40),
    to_status      VARCHAR(40) NOT NULL,
    actor_user_id  UUID,
    actor_role     VARCHAR(30),
    reason         TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_request_history ON request_status_history(request_id, created_at);

CREATE TABLE request_messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id      UUID        NOT NULL REFERENCES service_requests(id),
    sender_user_id  UUID        NOT NULL REFERENCES users(id),
    sender_role     VARCHAR(30) NOT NULL,
    body            TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_request_messages ON request_messages(request_id, created_at);

-- ---------------------------------------------------------------------------
-- Partner invitations and quotations
-- ---------------------------------------------------------------------------
CREATE TABLE partner_invitations (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invitation_number  VARCHAR(40) NOT NULL UNIQUE,
    request_id         UUID        NOT NULL REFERENCES service_requests(id),
    partner_id         UUID        NOT NULL REFERENCES partners(id),
    invited_by         UUID        NOT NULL REFERENCES users(id),
    status             VARCHAR(20) NOT NULL DEFAULT 'INVITED' CHECK (status IN ('INVITED','QUOTED','DECLINED','CLOSED','EXPIRED')),
    decline_reason     TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at          TIMESTAMPTZ,
    UNIQUE (request_id, partner_id)
);
CREATE INDEX ix_invitations_partner ON partner_invitations(partner_id, status);

CREATE TABLE quotations (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quotation_number    VARCHAR(40)   NOT NULL UNIQUE,
    request_id          UUID          NOT NULL REFERENCES service_requests(id),
    invitation_id       UUID          NOT NULL REFERENCES partner_invitations(id),
    partner_id          UUID          NOT NULL REFERENCES partners(id),
    scope_of_work       TEXT          NOT NULL,
    labour_amount       NUMERIC(12,2) NOT NULL DEFAULT 0,
    materials_amount    NUMERIC(12,2) NOT NULL DEFAULT 0,
    visit_fee           NUMERIC(12,2) NOT NULL DEFAULT 0,
    tax_amount          NUMERIC(12,2) NOT NULL DEFAULT 0,
    discount_amount     NUMERIC(12,2) NOT NULL DEFAULT 0,
    total_amount        NUMERIC(12,2) NOT NULL CHECK (total_amount >= 0),
    currency            VARCHAR(3)    NOT NULL DEFAULT 'INR',
    estimated_arrival   TIMESTAMPTZ,
    estimated_completion TIMESTAMPTZ,
    valid_until         TIMESTAMPTZ   NOT NULL,
    exclusions          TEXT,
    cancellation_terms  TEXT,
    warranty            TEXT,
    pay_on_delivery_allowed BOOLEAN   NOT NULL DEFAULT FALSE,
    status              VARCHAR(20)   NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED','SHARED','ACCEPTED','REJECTED','WITHDRAWN','EXPIRED','CLOSED')),
    agent_note          TEXT,
    shared_at           TIMESTAMPTZ,
    decided_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_quotations_request ON quotations(request_id);
CREATE UNIQUE INDEX ux_one_accepted_quote ON quotations(request_id) WHERE status = 'ACCEPTED';

-- ---------------------------------------------------------------------------
-- Exclusive assignment: only ONE live assignment per request (DB-enforced)
-- ---------------------------------------------------------------------------
CREATE TABLE assignments (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_number  VARCHAR(40) NOT NULL UNIQUE,
    request_id         UUID        NOT NULL REFERENCES service_requests(id),
    partner_id         UUID        NOT NULL REFERENCES partners(id),
    quotation_id       UUID        NOT NULL REFERENCES quotations(id),
    status             VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','CONFIRMED','IN_PROGRESS','COMPLETED','DECLINED','EXPIRED','CANCELLED')),
    price_snapshot     JSONB       NOT NULL,
    cancel_reason      TEXT,
    cancelled_by       VARCHAR(20),
    expires_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_one_live_assignment ON assignments(request_id) WHERE status IN ('ACTIVE','CONFIRMED','IN_PROGRESS');
CREATE INDEX ix_assignments_partner ON assignments(partner_id, status);

CREATE TABLE bookings (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_number        VARCHAR(40) NOT NULL UNIQUE,
    request_id            UUID        NOT NULL REFERENCES service_requests(id),
    assignment_id         UUID        NOT NULL UNIQUE REFERENCES assignments(id),
    scheduled_at          TIMESTAMPTZ,
    status                VARCHAR(20) NOT NULL CHECK (status IN ('CONFIRMED','ARRIVED','STARTED','COMPLETION_PENDING','COMPLETED','CANCELLED','NO_SHOW')),
    arrived_at            TIMESTAMPTZ,
    started_at            TIMESTAMPTZ,
    start_verified_by     UUID,
    start_verification    VARCHAR(30),
    completed_at          TIMESTAMPTZ,
    completion_verified_by UUID,
    completion_verification VARCHAR(30),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE service_otps (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id   UUID         NOT NULL REFERENCES bookings(id),
    purpose      VARCHAR(20)  NOT NULL CHECK (purpose IN ('START','COMPLETE')),
    code_hash    VARCHAR(200) NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    consumed_at  TIMESTAMPTZ,
    consumed_by  UUID,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_service_otps ON service_otps(booking_id, purpose, created_at DESC);

CREATE TABLE reschedule_proposals (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id      UUID        NOT NULL REFERENCES bookings(id),
    proposed_by     UUID        NOT NULL REFERENCES users(id),
    proposed_role   VARCHAR(20) NOT NULL,
    original_time   TIMESTAMPTZ,
    proposed_time   TIMESTAMPTZ NOT NULL,
    reason          TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED','ACCEPTED','REJECTED','WITHDRAWN')),
    responded_by    UUID,
    responded_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reviews (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id       UUID        NOT NULL UNIQUE REFERENCES service_requests(id),
    customer_user_id UUID        NOT NULL REFERENCES users(id),
    partner_id       UUID        NOT NULL REFERENCES partners(id),
    rating           INT         NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment          TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Payments, invoices, refunds
-- ---------------------------------------------------------------------------
CREATE TABLE payments (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_number     VARCHAR(40)   NOT NULL UNIQUE,
    purpose            VARCHAR(30)   NOT NULL CHECK (purpose IN ('SERVICE','MEMBERSHIP','PARTNER_SUBSCRIPTION')),
    request_id         UUID REFERENCES service_requests(id),
    quotation_id       UUID REFERENCES quotations(id),
    payer_user_id      UUID          NOT NULL REFERENCES users(id),
    amount             NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    currency           VARCHAR(3)    NOT NULL DEFAULT 'INR',
    method             VARCHAR(20)   NOT NULL CHECK (method IN ('ONLINE','PAY_ON_DELIVERY')),
    provider           VARCHAR(30)   NOT NULL,
    provider_order_id  VARCHAR(100),
    provider_payment_id VARCHAR(100),
    status             VARCHAR(20)   NOT NULL CHECK (status IN ('CREATED','PENDING','SUCCEEDED','FAILED','REFUND_PENDING','PARTIALLY_REFUNDED','REFUNDED','COLLECT_ON_DELIVERY')),
    failure_reason     TEXT,
    idempotency_key    VARCHAR(100)  NOT NULL UNIQUE,
    test_mode          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_payments_request ON payments(request_id);
CREATE UNIQUE INDEX ux_payments_provider_order ON payments(provider, provider_order_id) WHERE provider_order_id IS NOT NULL;

CREATE TABLE payment_webhook_events (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider         VARCHAR(30)  NOT NULL,
    event_id         VARCHAR(120) NOT NULL,
    event_type       VARCHAR(60)  NOT NULL,
    payload          JSONB        NOT NULL,
    signature_valid  BOOLEAN      NOT NULL,
    processed_at     TIMESTAMPTZ,
    processing_error TEXT,
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (provider, event_id)
);

CREATE TABLE invoices (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_number   VARCHAR(40)   NOT NULL UNIQUE,
    invoice_type     VARCHAR(20)   NOT NULL CHECK (invoice_type IN ('PROFORMA','TAX_INVOICE','RECEIPT')),
    request_id       UUID REFERENCES service_requests(id),
    payment_id       UUID REFERENCES payments(id),
    customer_user_id UUID          NOT NULL REFERENCES users(id),
    line_items       JSONB         NOT NULL,
    subtotal         NUMERIC(12,2) NOT NULL,
    tax_amount       NUMERIC(12,2) NOT NULL DEFAULT 0,
    discount_amount  NUMERIC(12,2) NOT NULL DEFAULT 0,
    total_amount     NUMERIC(12,2) NOT NULL,
    issued_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE refunds (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    refund_number   VARCHAR(40)   NOT NULL UNIQUE,
    payment_id      UUID          NOT NULL REFERENCES payments(id),
    amount          NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    reason          TEXT          NOT NULL,
    status          VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING','APPROVED','PROCESSING','COMPLETED','FAILED','REJECTED')),
    requested_by    UUID,
    approved_by     UUID,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Commission rules and financial ledger (append-only source of truth)
-- ---------------------------------------------------------------------------
CREATE TABLE commission_rules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope_type      VARCHAR(20)  NOT NULL CHECK (scope_type IN ('PARTNER','SERVICE','SUBCATEGORY','CATEGORY','DEFAULT')),
    scope_id        UUID,
    city_id         UUID REFERENCES cities(id),
    rate_percent    NUMERIC(5,2) NOT NULL CHECK (rate_percent >= 0 AND rate_percent <= 100),
    effective_from  DATE         NOT NULL DEFAULT CURRENT_DATE,
    effective_to    DATE,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by      UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_commission_scope ON commission_rules(scope_type, scope_id);

CREATE TABLE ledger_entries (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_group   UUID          NOT NULL,
    account       VARCHAR(40)   NOT NULL CHECK (account IN ('CUSTOMER_PAYMENT','PLATFORM_COMMISSION','PARTNER_EARNING','PARTNER_PAYOUT','REFUND','LEAD_CHARGE','MEMBERSHIP','SUBSCRIPTION','CASH_COLLECTED_BY_PARTNER')),
    direction     VARCHAR(6)    NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount        NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    currency      VARCHAR(3)    NOT NULL DEFAULT 'INR',
    partner_id    UUID REFERENCES partners(id),
    request_id    UUID REFERENCES service_requests(id),
    reference_type VARCHAR(30)  NOT NULL,
    reference_id  UUID          NOT NULL,
    memo          TEXT,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (reference_type, reference_id, account, direction)
);
CREATE INDEX ix_ledger_partner ON ledger_entries(partner_id, created_at);

CREATE TABLE partner_payouts (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payout_number  VARCHAR(40)   NOT NULL UNIQUE,
    partner_id     UUID          NOT NULL REFERENCES partners(id),
    request_id     UUID REFERENCES service_requests(id),
    amount         NUMERIC(12,2) NOT NULL,
    status         VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING','SCHEDULED','PROCESSING','PAID','FAILED','ON_HOLD')),
    due_on         DATE,
    paid_at        TIMESTAMPTZ,
    reference      VARCHAR(120),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Notifications and transactional outbox
-- ---------------------------------------------------------------------------
CREATE TABLE notifications (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id),
    title       VARCHAR(200) NOT NULL,
    body        TEXT         NOT NULL,
    category    VARCHAR(40)  NOT NULL DEFAULT 'GENERAL',
    ref_type    VARCHAR(30),
    ref_id      UUID,
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_user ON notifications(user_id, created_at DESC);

CREATE TABLE outbox_events (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type       VARCHAR(60)  NOT NULL,
    channel          VARCHAR(20)  NOT NULL CHECK (channel IN ('SMS','PUSH','EMAIL','WHATSAPP','WEBHOOK','INTERNAL')),
    recipient        VARCHAR(200),
    payload          JSONB        NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED','DEAD')),
    attempts         INT          NOT NULL DEFAULT 0,
    max_attempts     INT          NOT NULL DEFAULT 5,
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error       TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at          TIMESTAMPTZ
);
CREATE INDEX ix_outbox_pending ON outbox_events(status, next_attempt_at);

-- ---------------------------------------------------------------------------
-- SLA rules and operational exceptions
-- ---------------------------------------------------------------------------
CREATE TABLE sla_rules (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    priority                 VARCHAR(10) NOT NULL UNIQUE CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT')),
    first_response_minutes   INT NOT NULL,
    resolution_minutes       INT NOT NULL,
    active                   BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE ops_exceptions (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exception_type VARCHAR(50)  NOT NULL,
    priority       VARCHAR(10)  NOT NULL DEFAULT 'NORMAL',
    status         VARCHAR(20)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACKNOWLEDGED','RESOLVED','DISMISSED')),
    title          VARCHAR(250) NOT NULL,
    details        JSONB,
    ref_type       VARCHAR(30),
    ref_id         UUID,
    owner_user_id  UUID,
    next_action    VARCHAR(250),
    dedupe_key     VARCHAR(200),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at    TIMESTAMPTZ,
    resolution_note TEXT
);
CREATE UNIQUE INDEX ux_ops_open_dedupe ON ops_exceptions(dedupe_key) WHERE status IN ('OPEN','ACKNOWLEDGED') AND dedupe_key IS NOT NULL;
CREATE INDEX ix_ops_status ON ops_exceptions(status, created_at DESC);

-- ---------------------------------------------------------------------------
-- Audit trail
-- ---------------------------------------------------------------------------
CREATE TABLE audit_events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id  UUID,
    actor_role     VARCHAR(30),
    action         VARCHAR(80)  NOT NULL,
    entity_type    VARCHAR(40)  NOT NULL,
    entity_id      UUID,
    details        JSONB,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_entity ON audit_events(entity_type, entity_id, created_at);

-- ---------------------------------------------------------------------------
-- Default configuration (values are configurable through the Admin panel)
-- ---------------------------------------------------------------------------
INSERT INTO platform_settings(key, value, description) VALUES
 ('partner.cancellation.alert_threshold', '3', 'Partner cancellations above this count within the window raise an admin alert'),
 ('partner.cancellation.window_days', '30', 'Rolling window (days) for partner cancellation monitoring'),
 ('quotation.default_validity_hours', '48', 'Default quotation validity when partner does not specify'),
 ('assignment.confirm_timeout_minutes', '60', 'Time a partner has to confirm an assignment'),
 ('service_otp.ttl_minutes', '15', 'Validity of start/completion service OTPs'),
 ('settlement.target_days', '1', 'Target settlement objective T+N days (subject to provider/bank processing)'),
 ('payments.pay_on_delivery_enabled', 'true', 'Global switch for pay-on-delivery');

INSERT INTO sla_rules(priority, first_response_minutes, resolution_minutes) VALUES
 ('URGENT', 30, 240),
 ('HIGH', 60, 480),
 ('NORMAL', 120, 1440),
 ('LOW', 240, 2880);

INSERT INTO commission_rules(scope_type, scope_id, rate_percent) VALUES ('DEFAULT', NULL, 5.00);
