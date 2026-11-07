-- Stage 4: unified request engine. service_requests is the shared request identity ("hub") for every request type;
-- each type keeps its own lifecycle (state machine). Existing rows are backfilled; nothing is removed.

ALTER TABLE service_requests
    ADD COLUMN request_type      VARCHAR(30) NOT NULL DEFAULT 'SERVICE_BOOKING'
        CHECK (request_type IN ('PRODUCT_PURCHASE', 'PRODUCT_RFQ', 'SERVICE_BOOKING', 'TASK_REQUEST', 'PERSONAL_ASSISTANCE')),
    ADD COLUMN vertical          VARCHAR(30) NOT NULL DEFAULT 'SERVICES' REFERENCES platform_verticals(code),
    -- Which state machine owns the status: REQUEST_V1 (existing RequestStatus), ORDER (Stage 5), TASK (Stage 8).
    ADD COLUMN lifecycle         VARCHAR(20) NOT NULL DEFAULT 'REQUEST_V1' CHECK (lifecycle IN ('REQUEST_V1', 'ORDER', 'TASK')),
    -- An assistance case can spawn child requests (an RFQ, a booking, a task) for the same customer.
    ADD COLUMN parent_request_id UUID REFERENCES service_requests(id);

UPDATE service_requests r SET vertical = c.vertical FROM categories c WHERE c.id = r.category_id;
UPDATE service_requests SET request_type = CASE
    WHEN booking_mode = 'AGENT_DIRECT' AND vertical = 'TASKS' THEN 'TASK_REQUEST'
    WHEN booking_mode = 'AGENT_DIRECT' THEN 'PERSONAL_ASSISTANCE'
    WHEN vertical = 'MARKETPLACE' THEN 'PRODUCT_RFQ'
    WHEN vertical = 'TASKS' THEN 'TASK_REQUEST'
    WHEN vertical = 'ASSISTANCE' THEN 'PERSONAL_ASSISTANCE'
    ELSE 'SERVICE_BOOKING' END;

CREATE INDEX ix_requests_customer_type ON service_requests(customer_user_id, request_type, created_at DESC);
CREATE INDEX ix_requests_parent ON service_requests(parent_request_id) WHERE parent_request_id IS NOT NULL;

-- Readable name for the hub, with the currently assigned partner (from the live assignment).
CREATE VIEW requests AS
SELECT r.id, r.request_number, r.customer_user_id, r.request_type, r.vertical, r.lifecycle, r.category_id, r.subcategory_id,
       r.description, r.address_snapshot AS location, r.priority, r.preferred_start, r.preferred_end, r.agent_id,
       (SELECT a.partner_id FROM assignments a WHERE a.request_id = r.id AND a.status IN ('ACTIVE', 'CONFIRMED', 'IN_PROGRESS', 'COMPLETED')
        ORDER BY a.created_at DESC LIMIT 1) AS assigned_partner_id,
       r.status, r.parent_request_id, r.created_at, r.updated_at
FROM service_requests r;

-- Private attachments (photos, BOQs, prescriptions, documents). Never public: served only through short-lived signed links
-- to the customer, their agent, admins and - when shared - the partners invited to quote.
CREATE TABLE request_attachments (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id     UUID         NOT NULL REFERENCES service_requests(id),
    uploaded_by    UUID         NOT NULL REFERENCES users(id),
    uploader_role  VARCHAR(20)  NOT NULL CHECK (uploader_role IN ('CUSTOMER', 'AGENT', 'ADMIN')),
    file_name      VARCHAR(160) NOT NULL,
    content_type   VARCHAR(60)  NOT NULL CHECK (content_type IN ('image/png', 'image/jpeg', 'image/webp', 'application/pdf')),
    size_bytes     INT          NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    data           BYTEA        NOT NULL,
    -- PRIVATE: customer, agent, admin. PARTNERS: also the partners invited to quote on this request.
    visibility     VARCHAR(20)  NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE', 'PARTNERS')),
    note           VARCHAR(200),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ
);
CREATE INDEX ix_request_attachments_request ON request_attachments(request_id) WHERE deleted_at IS NULL;
