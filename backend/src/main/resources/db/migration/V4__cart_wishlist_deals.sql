-- Cart, wishlist, deals and booking modes.

ALTER TABLE service_items
  ADD COLUMN original_price NUMERIC(12,2),
  ADD COLUMN deal_label VARCHAR(80),
  ADD COLUMN duration_label VARCHAR(40);

-- How a request was raised:
--   SELF            customer booked menu items at listed prices (agent only arranges a partner)
--   AGENT_ASSISTED  customer sent the cart to their agent to arrange / negotiate
--   AGENT_DIRECT    customer wrote a free-text request straight to their agent
ALTER TABLE service_requests
  ADD COLUMN booking_mode VARCHAR(20) NOT NULL DEFAULT 'AGENT_ASSISTED'
      CHECK (booking_mode IN ('SELF','AGENT_ASSISTED','AGENT_DIRECT')),
  ADD COLUMN line_items JSONB,
  ADD COLUMN estimated_total NUMERIC(12,2),
  ADD COLUMN has_quote_items BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE cart_items (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_user_id UUID        NOT NULL REFERENCES users(id),
    service_item_id  UUID        NOT NULL REFERENCES service_items(id),
    quantity         INT         NOT NULL CHECK (quantity BETWEEN 1 AND 50),
    added_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (customer_user_id, service_item_id)
);
CREATE INDEX ix_cart_customer ON cart_items(customer_user_id);

CREATE TABLE wishlist_items (
    customer_user_id UUID        NOT NULL REFERENCES users(id),
    service_item_id  UUID        NOT NULL REFERENCES service_items(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_user_id, service_item_id)
);
