-- Menu groups (a "sub-subcategory" level inside a subcategory, e.g. Electrician > Fans > Fan Repair),
-- partner price lists, uploaded media, agent wings and quotation source.

ALTER TABLE service_items ADD COLUMN menu_group VARCHAR(80);

-- Partner's own price for a catalogue item. Changes go to PENDING and need admin approval before agents use them.
CREATE TABLE partner_price_items (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_id       UUID          NOT NULL REFERENCES partners(id),
    service_item_id  UUID          NOT NULL REFERENCES service_items(id),
    price            NUMERIC(12,2) NOT NULL CHECK (price >= 0),
    unit             VARCHAR(40),
    notes            VARCHAR(300),
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    approved_price   NUMERIC(12,2),
    review_note      VARCHAR(300),
    reviewed_by      UUID,
    reviewed_at      TIMESTAMPTZ,
    active           BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (partner_id, service_item_id)
);
CREATE INDEX ix_partner_prices_status ON partner_price_items(status);

-- Small images uploaded from the admin panel (category / service pictures).
-- Stored in the database for the MVP; swap for S3-compatible storage + CDN at scale (URLs stay the same shape).
CREATE TABLE media_assets (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_name     VARCHAR(200) NOT NULL,
    content_type  VARCHAR(40)  NOT NULL CHECK (content_type IN ('image/png','image/jpeg','image/webp')),
    size_bytes    INT          NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 2097152),
    data          BYTEA        NOT NULL,
    uploaded_by   UUID,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Agents belong to a wing (e.g. Home Services, Events & Weddings); managers can own a wing.
ALTER TABLE agents ADD COLUMN wing VARCHAR(60) NOT NULL DEFAULT 'General';

ALTER TABLE quotations ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'PARTNER' CHECK (source IN ('PARTNER','PRICE_LIST'));

-- Menu groups for the launch menus.
UPDATE service_items si SET menu_group = g.grp
FROM subcategories s, (VALUES
  ('ELECTRICAL','FAN_REPAIR','Fans'), ('ELECTRICAL','FAN_INSTALLATION','Fans'),
  ('ELECTRICAL','LIGHT_REPAIR','Lights'), ('ELECTRICAL','LIGHT_INSTALLATION','Lights'),
  ('ELECTRICAL','SWITCH_SOCKET','Switches & Sockets'), ('ELECTRICAL','COMBO_SWITCHBOARD','Switches & Sockets'),
  ('ELECTRICAL','WIRING','Wiring & Power'), ('ELECTRICAL','MCB_REPAIR','Wiring & Power'), ('ELECTRICAL','INVERTER_SETUP','Wiring & Power'),
  ('ELECTRICAL','DOORBELL','Others'),
  ('PLUMBING','TAP_REPAIR','Taps & Fittings'), ('PLUMBING','BATHROOM_FITTINGS','Taps & Fittings'), ('PLUMBING','WASH_BASIN','Taps & Fittings'),
  ('PLUMBING','PIPE_LEAKAGE','Leaks & Blockages'), ('PLUMBING','BLOCKAGE','Leaks & Blockages'), ('PLUMBING','TOILET_REPAIR','Leaks & Blockages'),
  ('PLUMBING','TANK_CLEANING','Tanks & Motors'), ('PLUMBING','MOTOR_INSTALL','Tanks & Motors'), ('PLUMBING','PIPELINE_INSTALLATION','Tanks & Motors'),
  ('CLEANING','BATHROOM_CLEAN','Home Cleaning'), ('CLEANING','KITCHEN_CLEAN','Home Cleaning'), ('CLEANING','HOME_DEEP_CLEAN','Home Cleaning'),
  ('CLEANING','SOFA_CLEAN','Furnishing Cleaning'), ('CLEANING','CARPET_CLEAN','Furnishing Cleaning'), ('CLEANING','MATTRESS_CLEAN','Furnishing Cleaning'),
  ('AC','AC_JET_SERVICE','Service'), ('AC','AC_REPAIR','Repair'), ('AC','GAS_REFILL','Repair'),
  ('AC','AC_INSTALLATION','Install / Uninstall'), ('AC','AC_UNINSTALLATION','Install / Uninstall'), ('AC','AC_AMC','Service')
) AS g(sub, code, grp)
WHERE si.subcategory_id = s.id AND s.code = g.sub AND si.code = g.code;
