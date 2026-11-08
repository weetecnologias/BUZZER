-- Stage 5: direct product purchase. One shop per cart; checkout creates an order whose prices, taxes, product details and
-- fulfilment details are frozen (immutable snapshots, enforced by triggers). Every order also has a row in the request hub
-- (service_requests, lifecycle ORDER) so it shares the request number, timeline and "My Requests" tracking.

CREATE SEQUENCE seq_order_number START 1;

-- ---- Product cart (one shop at a time) -----------------------------------------------------------------------
CREATE TABLE product_carts (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_user_id  UUID        NOT NULL UNIQUE REFERENCES users(id),
    partner_id        UUID        NOT NULL REFERENCES partners(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE product_cart_items (
    cart_id          UUID          NOT NULL REFERENCES product_carts(id) ON DELETE CASCADE,
    listing_id       UUID          NOT NULL REFERENCES vendor_listings(id),
    quantity         INT           NOT NULL CHECK (quantity >= 1 AND quantity <= 10000),
    -- Price the customer saw when adding; checkout refuses silently changed prices.
    unit_price_seen  NUMERIC(12,2) NOT NULL,
    added_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (cart_id, listing_id)
);

-- ---- Orders -------------------------------------------------------------------------------------------------
CREATE TABLE orders (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_number         VARCHAR(40)   NOT NULL UNIQUE,
    request_id           UUID          NOT NULL UNIQUE REFERENCES service_requests(id),
    customer_user_id     UUID          NOT NULL REFERENCES users(id),
    partner_id           UUID          NOT NULL REFERENCES partners(id),
    location_id          UUID REFERENCES partner_locations(id),
    -- DIRECT: bought from a shop's listing. RFQ: created when the customer accepted one shop's quotation.
    source               VARCHAR(10)   NOT NULL CHECK (source IN ('DIRECT', 'RFQ')),
    quotation_id         UUID REFERENCES quotations(id),
    status               VARCHAR(30)   NOT NULL CHECK (status IN ('PAYMENT_PENDING', 'PLACED', 'PAID', 'VENDOR_CONFIRMED', 'READY_FOR_PICKUP',
                                                              'OUT_FOR_DELIVERY', 'COMPLETED', 'CANCELLED', 'VENDOR_REJECTED')),
    fulfilment           VARCHAR(20)   NOT NULL CHECK (fulfilment IN ('PICKUP', 'VENDOR_DELIVERY')),
    payment_method       VARCHAR(20)   NOT NULL CHECK (payment_method IN ('ONLINE', 'PAY_ON_DELIVERY')),
    items_total          NUMERIC(12,2) NOT NULL CHECK (items_total >= 0),
    discount_total       NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (discount_total >= 0),
    tax_total            NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (tax_total >= 0),
    -- TRUE: listed prices already include tax (retail); tax_total is the tax contained in them.
    prices_include_tax   BOOLEAN       NOT NULL,
    delivery_fee         NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (delivery_fee >= 0),
    total_amount         NUMERIC(12,2) NOT NULL CHECK (total_amount >= 0),
    currency             VARCHAR(3)    NOT NULL DEFAULT 'INR',
    vendor_snapshot      JSONB         NOT NULL,
    fulfilment_snapshot  JSONB         NOT NULL,
    customer_note        VARCHAR(500),
    -- Shown only to the customer; the shop enters it at pickup / delivery to complete the order.
    completion_code      VARCHAR(6)    NOT NULL,
    code_attempts        INT           NOT NULL DEFAULT 0,
    vendor_confirmed_at  TIMESTAMPTZ,
    ready_at             TIMESTAMPTZ,
    dispatched_at        TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    cancelled_at         TIMESTAMPTZ,
    cancel_reason        VARCHAR(300),
    idempotency_key      VARCHAR(120)  NOT NULL,
    version              INT           NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (customer_user_id, idempotency_key)
);
CREATE INDEX ix_orders_customer ON orders(customer_user_id, created_at DESC);
CREATE INDEX ix_orders_partner ON orders(partner_id, status, created_at DESC);
CREATE INDEX ix_orders_status ON orders(status, created_at DESC);
-- One accepted quotation becomes at most one order.
CREATE UNIQUE INDEX ux_orders_quotation ON orders(quotation_id) WHERE quotation_id IS NOT NULL;

CREATE TABLE order_items (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id         UUID          NOT NULL REFERENCES orders(id),
    line_no          INT           NOT NULL,
    listing_id       UUID REFERENCES vendor_listings(id),
    product_id       UUID REFERENCES products(id),
    variant_id       UUID REFERENCES product_variants(id),
    name             VARCHAR(250)  NOT NULL,
    variant_name     VARCHAR(120),
    brand            VARCHAR(120),
    specs            VARCHAR(500),
    unit             VARCHAR(40),
    quantity         INT           NOT NULL CHECK (quantity >= 1),
    unit_price       NUMERIC(12,2) NOT NULL CHECK (unit_price >= 0),
    discount_amount  NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    tax_rate         NUMERIC(5,2)  NOT NULL DEFAULT 0,
    tax_amount       NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
    line_total       NUMERIC(12,2) NOT NULL CHECK (line_total >= 0),
    -- Everything else known about the item at the time of the order.
    snapshot         JSONB         NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (order_id, line_no)
);

-- Immutable snapshots: order lines never change; an order's money, shop and fulfilment details never change.
CREATE FUNCTION buzzer_order_items_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'order_items are immutable snapshots (order %)', OLD.order_id;
END $$;
CREATE TRIGGER trg_order_items_immutable BEFORE UPDATE OR DELETE ON order_items
    FOR EACH ROW EXECUTE FUNCTION buzzer_order_items_immutable();

CREATE FUNCTION buzzer_orders_snapshot_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.items_total IS DISTINCT FROM OLD.items_total OR NEW.discount_total IS DISTINCT FROM OLD.discount_total
       OR NEW.tax_total IS DISTINCT FROM OLD.tax_total OR NEW.delivery_fee IS DISTINCT FROM OLD.delivery_fee
       OR NEW.total_amount IS DISTINCT FROM OLD.total_amount OR NEW.prices_include_tax IS DISTINCT FROM OLD.prices_include_tax
       OR NEW.vendor_snapshot IS DISTINCT FROM OLD.vendor_snapshot OR NEW.fulfilment_snapshot IS DISTINCT FROM OLD.fulfilment_snapshot
       OR NEW.partner_id IS DISTINCT FROM OLD.partner_id OR NEW.customer_user_id IS DISTINCT FROM OLD.customer_user_id
       OR NEW.fulfilment IS DISTINCT FROM OLD.fulfilment OR NEW.payment_method IS DISTINCT FROM OLD.payment_method
       OR NEW.source IS DISTINCT FROM OLD.source OR NEW.quotation_id IS DISTINCT FROM OLD.quotation_id
       OR NEW.completion_code IS DISTINCT FROM OLD.completion_code THEN
        RAISE EXCEPTION 'order % snapshot fields are immutable', OLD.order_number;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_orders_snapshot_guard BEFORE UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION buzzer_orders_snapshot_guard();
CREATE FUNCTION buzzer_orders_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'orders are never deleted (order %) - cancel them instead', OLD.order_number;
END $$;
CREATE TRIGGER trg_orders_no_delete BEFORE DELETE ON orders
    FOR EACH ROW EXECUTE FUNCTION buzzer_orders_no_delete();

-- ---- Payments for orders ---------------------------------------------------------------------------------------
ALTER TABLE payments ADD COLUMN order_id UUID REFERENCES orders(id);
ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_purpose_check;
ALTER TABLE payments ADD CONSTRAINT payments_purpose_check CHECK (purpose IN ('SERVICE', 'MEMBERSHIP', 'PARTNER_SUBSCRIPTION', 'ORDER'));
CREATE INDEX ix_payments_order ON payments(order_id) WHERE order_id IS NOT NULL;

INSERT INTO platform_settings(key, value, description) VALUES
 ('orders.pay_on_delivery_enabled', 'true', 'Allow "pay at pickup / on delivery" for marketplace orders'),
 ('orders.max_code_attempts', '5', 'Wrong completion-code attempts before the shop must contact support')
ON CONFLICT (key) DO NOTHING;
