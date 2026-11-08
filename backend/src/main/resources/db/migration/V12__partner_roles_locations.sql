-- Stage 5: partner types and shop locations. One partner account can hold several roles (a hardware shop that is also a
-- wholesaler, an electrician who also sells electrical supplies). Shop-type roles need admin approval before the shop is
-- shown in the marketplace or invited to quotation requests. Existing partners are backfilled; nothing is removed.

CREATE TABLE partner_roles (
    partner_id   UUID        NOT NULL REFERENCES partners(id),
    role         VARCHAR(30) NOT NULL CHECK (role IN ('RETAIL_SHOP', 'WHOLESALER', 'DISTRIBUTOR', 'SERVICE_PROVIDER', 'TASK_PARTNER')),
    status       VARCHAR(20) NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    reviewed_by  UUID,
    reviewed_at  TIMESTAMPTZ,
    review_note  VARCHAR(300),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (partner_id, role)
);
CREATE INDEX ix_partner_roles_role ON partner_roles(role, status);

-- Shop branches / service bases: where the partner operates from, how far they serve, opening hours, pickup and delivery.
CREATE TABLE partner_locations (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_id         UUID          NOT NULL REFERENCES partners(id),
    name               VARCHAR(120)  NOT NULL,
    address            VARCHAR(400),
    city_id            UUID          NOT NULL REFERENCES cities(id),
    locality_id        UUID REFERENCES localities(id),
    latitude           NUMERIC(10,7),
    longitude          NUMERIC(10,7),
    -- How far the shop delivers / accepts quotation requests from.
    service_radius_km  NUMERIC(6,2)  NOT NULL DEFAULT 10 CHECK (service_radius_km > 0 AND service_radius_km <= 200),
    opening_hours      VARCHAR(200),
    phone              VARCHAR(20),
    pickup_enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
    delivery_enabled   BOOLEAN       NOT NULL DEFAULT FALSE,
    delivery_fee       NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (delivery_fee >= 0),
    free_delivery_above NUMERIC(12,2) CHECK (free_delivery_above IS NULL OR free_delivery_above >= 0),
    min_order_value    NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (min_order_value >= 0),
    is_primary         BOOLEAN       NOT NULL DEFAULT FALSE,
    active             BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK ((latitude IS NULL) = (longitude IS NULL))
);
CREATE INDEX ix_partner_locations_partner ON partner_locations(partner_id) WHERE active;
CREATE UNIQUE INDEX ux_partner_primary_location ON partner_locations(partner_id) WHERE is_primary AND active;

-- ---- Backfill roles from approved capabilities ------------------------------------------------------------
INSERT INTO partner_roles(partner_id, role, status, reviewed_at, review_note)
SELECT DISTINCT pc.partner_id,
       CASE c.vertical WHEN 'MARKETPLACE' THEN 'RETAIL_SHOP' WHEN 'TASKS' THEN 'TASK_PARTNER' ELSE 'SERVICE_PROVIDER' END,
       CASE WHEN p.registration_status = 'APPROVED' THEN 'APPROVED' ELSE 'REQUESTED' END,
       CASE WHEN p.registration_status = 'APPROVED' THEN now() END,
       'Backfilled from existing capabilities'
FROM partner_capabilities pc
JOIN partners p ON p.id = pc.partner_id
JOIN subcategories s ON s.id = pc.subcategory_id
JOIN categories c ON c.id = s.category_id
WHERE pc.status IN ('APPROVED', 'REQUESTED')
ON CONFLICT DO NOTHING;

-- Partners without any capability yet are service providers by default (the only type that existed before).
INSERT INTO partner_roles(partner_id, role, status, review_note)
SELECT p.id, 'SERVICE_PROVIDER', CASE WHEN p.registration_status = 'APPROVED' THEN 'APPROVED' ELSE 'REQUESTED' END,
       'Backfilled default role'
FROM partners p WHERE NOT EXISTS (SELECT 1 FROM partner_roles r WHERE r.partner_id = p.id);

-- ---- Backfill one primary location per partner: first service-area locality, else the city centre ---------
INSERT INTO partner_locations(partner_id, name, address, city_id, locality_id, latitude, longitude, service_radius_km, phone,
                              pickup_enabled, delivery_enabled, is_primary)
SELECT p.id, COALESCE(p.business_name, p.display_name), p.address, p.city_id, l.id,
       COALESCE(l.latitude, ci.latitude), COALESCE(l.longitude, ci.longitude),
       LEAST(GREATEST(COALESCE(ci.service_radius_km, 40), 1), 200), p.contact_phone,
       TRUE, FALSE, TRUE
FROM partners p
JOIN cities ci ON ci.id = p.city_id
LEFT JOIN LATERAL (SELECT lo.id, lo.latitude, lo.longitude FROM partner_service_areas sa JOIN localities lo ON lo.id = sa.locality_id
                   WHERE sa.partner_id = p.id ORDER BY lo.name LIMIT 1) l ON TRUE;
