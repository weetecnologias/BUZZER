-- Stage 5: quotation requests (RFQ) with structured items and item-wise vendor quotations.
--   request_items   : what the customer asked for (name, preferred brand, specifications, quantity, unit)
--   quotation_items : the vendor's answer per item (brand offered, availability, unit price, discount, tax, MOQ, lead time)
--   rfq_invite_rules: how many vendors to invite, configurable by category, city and locality
-- The JSON line_items on requests and quotations stay (older screens read them); the tables are the structured copy.

CREATE TABLE request_items (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id        UUID          NOT NULL REFERENCES service_requests(id),
    line_key          VARCHAR(80)   NOT NULL,
    line_no           INT           NOT NULL,
    product_id        UUID REFERENCES products(id),
    service_item_id   UUID REFERENCES service_items(id),
    name              VARCHAR(200)  NOT NULL,
    brand_preference  VARCHAR(120),
    specs             VARCHAR(500),
    quantity          INT           NOT NULL CHECK (quantity >= 1 AND quantity <= 1000000),
    unit              VARCHAR(40),
    notes             VARCHAR(300),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (request_id, line_key),
    UNIQUE (request_id, line_no)
);

ALTER TABLE quotations
    ADD COLUMN terms          TEXT,
    ADD COLUMN lead_time_days INT CHECK (lead_time_days IS NULL OR (lead_time_days >= 0 AND lead_time_days <= 180));

CREATE TABLE quotation_items (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quotation_id      UUID          NOT NULL REFERENCES quotations(id),
    request_item_id   UUID REFERENCES request_items(id),
    line_key          VARCHAR(80)   NOT NULL,
    name              VARCHAR(200)  NOT NULL,
    brand             VARCHAR(120),
    specs             VARCHAR(500),
    quantity          INT           NOT NULL CHECK (quantity >= 1),
    unit              VARCHAR(40),
    available         BOOLEAN       NOT NULL,
    unit_price        NUMERIC(12,2) CHECK (unit_price IS NULL OR unit_price >= 0),
    discount_percent  NUMERIC(5,2)  NOT NULL DEFAULT 0 CHECK (discount_percent >= 0 AND discount_percent <= 100),
    discount_amount   NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    tax_rate          NUMERIC(5,2)  NOT NULL DEFAULT 0 CHECK (tax_rate >= 0 AND tax_rate <= 40),
    tax_amount        NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
    line_total        NUMERIC(12,2) CHECK (line_total IS NULL OR line_total >= 0),
    moq               INT CHECK (moq IS NULL OR moq >= 1),
    lead_time_days    INT CHECK (lead_time_days IS NULL OR (lead_time_days >= 0 AND lead_time_days <= 180)),
    note              VARCHAR(200),
    UNIQUE (quotation_id, line_key),
    CHECK (NOT available OR unit_price IS NOT NULL)
);
CREATE INDEX ix_quotation_items_quotation ON quotation_items(quotation_id);

-- Structured copy of existing item quotes (older quotes had no discount / tax per line).
INSERT INTO quotation_items(quotation_id, line_key, name, quantity, unit, available, unit_price, line_total, note)
SELECT q.id, l->>'key', COALESCE(l->>'name', 'Item'), GREATEST(COALESCE((l->>'quantity')::int, 1), 1), l->>'unit',
       COALESCE((l->>'available')::boolean, FALSE),
       CASE WHEN COALESCE((l->>'available')::boolean, FALSE) THEN (l->>'unit_price')::numeric END,
       CASE WHEN COALESCE((l->>'available')::boolean, FALSE) THEN (l->>'line_total')::numeric END,
       left(l->>'note', 200)
FROM quotations q CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(q.line_items) = 'array' THEN q.line_items ELSE '[]'::jsonb END) l
WHERE l->>'key' IS NOT NULL AND (NOT COALESCE((l->>'available')::boolean, FALSE) OR l->>'unit_price' IS NOT NULL)
ON CONFLICT DO NOTHING;

-- ---- How many vendors an RFQ is sent to ----------------------------------------------------------------------
CREATE TABLE rfq_invite_rules (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id   UUID REFERENCES categories(id),
    city_id       UUID REFERENCES cities(id),
    locality_id   UUID REFERENCES localities(id),
    invite_count  INT         NOT NULL CHECK (invite_count >= 1 AND invite_count <= 20),
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_by    UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_rfq_invite_rule_scope ON rfq_invite_rules(
    COALESCE(category_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(city_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(locality_id, '00000000-0000-0000-0000-000000000000'::uuid)) WHERE active;

INSERT INTO platform_settings(key, value, description) VALUES
 ('rfq.invite_count', '7', 'Default number of vendors a quotation request is sent to (rules per category / city / locality override it)'),
 ('rfq.bulk_prefers_wholesalers', 'true', 'Bulk quotation requests go to wholesalers and distributors first')
ON CONFLICT (key) DO NOTHING;

-- Building materials are usually bought in bulk from many suppliers: ask more of them by default.
INSERT INTO rfq_invite_rules(category_id, invite_count)
SELECT id, 10 FROM categories WHERE code = 'INDUSTRIAL_SUPPLIES'
ON CONFLICT DO NOTHING;
