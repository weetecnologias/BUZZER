-- Stage 5: product catalogue and vendor listings.
--   products / product_variants : one shared catalogue (admin-managed; vendors may propose products, admin approves)
--   vendor_listings             : a shop's own price, stock status and minimum order for a variant (price comparison)
-- Stock status is what the shop declares (with the time it was last updated) - BUZZER does not claim real-time stock.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---- Initial marketplace categories (admin can rename, reorder, disable or add more) ---------------------
INSERT INTO categories(code, name, description, icon, sort_order, workflow_template, is_global_special, vertical, active) VALUES
 ('ELECTRONICS_APPLIANCES', 'Electronics & Appliances', 'TVs, fans, kitchen and home appliances from local stores', 'tv', 30, 'MENU_DRIVEN', FALSE, 'MARKETPLACE', TRUE),
 ('GROCERY_SUPERMARKET', 'Grocery & Supermarkets', 'Daily groceries and household essentials', 'basket', 31, 'MENU_DRIVEN', FALSE, 'MARKETPLACE', TRUE),
 ('FURNITURE_INTERIORS', 'Furniture & Interiors', 'Furniture, furnishings and interior materials', 'sofa', 32, 'MENU_DRIVEN', FALSE, 'MARKETPLACE', TRUE),
 ('AUTO_SPARES', 'Automobile & Spare Parts', 'Spare parts, tyres, batteries and accessories', 'car', 33, 'MENU_DRIVEN', FALSE, 'MARKETPLACE', TRUE),
 ('FASHION_LIFESTYLE', 'Fashion & Lifestyle', 'Clothing, footwear and accessories from local shops', 'shirt', 34, 'MENU_DRIVEN', FALSE, 'MARKETPLACE', TRUE),
 -- Off until the licensing checks for medical products are in place (approved decision 6).
 ('MEDICAL_SUPPLIES', 'Medical & Healthcare Products', 'Healthcare products from licensed stores (subject to applicable licences)', 'pills', 35, 'MENU_DRIVEN', FALSE, 'MARKETPLACE', FALSE),
 ('STATIONERY_OFFICE', 'Stationery & Office Supplies', 'Stationery, printing and office supplies', 'pencil', 36, 'MENU_DRIVEN', FALSE, 'MARKETPLACE', TRUE),
 ('AGRI_GARDEN', 'Agriculture & Garden', 'Seeds, fertilisers, tools and garden supplies', 'leaf', 37, 'MENU_DRIVEN', FALSE, 'MARKETPLACE', TRUE),
 ('INDUSTRIAL_SUPPLIES', 'Industrial & Commercial Supplies', 'Bulk and industrial materials for businesses', 'factory', 38, 'MENU_DRIVEN', FALSE, 'MARKETPLACE', TRUE)
ON CONFLICT (code) DO NOTHING;

UPDATE categories SET advisory_notice = 'Medical products are sold only by licensed stores. Prescription items need a valid prescription.'
WHERE code = 'MEDICAL_SUPPLIES' AND advisory_notice IS NULL;

INSERT INTO category_cities(category_id, city_id)
SELECT c.id, ci.id FROM categories c CROSS JOIN cities ci
WHERE c.code IN ('ELECTRONICS_APPLIANCES', 'GROCERY_SUPERMARKET', 'FURNITURE_INTERIORS', 'AUTO_SPARES', 'FASHION_LIFESTYLE', 'MEDICAL_SUPPLIES',
                 'STATIONERY_OFFICE', 'AGRI_GARDEN', 'INDUSTRIAL_SUPPLIES') AND ci.active
ON CONFLICT DO NOTHING;

INSERT INTO subcategories(category_id, code, name, icon, sort_order)
SELECT c.id, s.code, s.name, s.icon, s.ord FROM categories c JOIN (VALUES
  ('ELECTRONICS_APPLIANCES', 'HOME_APPLIANCES', 'Home Appliances', 'plug', 1),
  ('ELECTRONICS_APPLIANCES', 'KITCHEN_APPLIANCES', 'Kitchen Appliances', 'dish', 2),
  ('ELECTRONICS_APPLIANCES', 'FANS_COOLERS', 'Fans & Coolers', 'snow', 3),
  ('GROCERY_SUPERMARKET', 'STAPLES', 'Staples & Grains', 'basket', 1),
  ('GROCERY_SUPERMARKET', 'HOUSEHOLD_ESSENTIALS', 'Household Essentials', 'spray', 2),
  ('GROCERY_SUPERMARKET', 'BEVERAGES_SNACKS', 'Beverages & Snacks', 'dish', 3),
  ('FURNITURE_INTERIORS', 'FURNITURE', 'Furniture', 'sofa', 1),
  ('FURNITURE_INTERIORS', 'FURNISHINGS', 'Curtains & Furnishings', 'home', 2),
  ('FURNITURE_INTERIORS', 'PLY_LAMINATES', 'Plywood & Laminates', 'hammer', 3),
  ('AUTO_SPARES', 'CAR_PARTS', 'Car Parts & Accessories', 'car', 1),
  ('AUTO_SPARES', 'BIKE_PARTS', 'Bike Parts & Accessories', 'bike', 2),
  ('AUTO_SPARES', 'BATTERIES_TYRES', 'Batteries & Tyres', 'bolt', 3),
  ('FASHION_LIFESTYLE', 'CLOTHING', 'Clothing', 'shirt', 1),
  ('FASHION_LIFESTYLE', 'FOOTWEAR', 'Footwear', 'tag', 2),
  ('MEDICAL_SUPPLIES', 'HEALTHCARE_DEVICES', 'Healthcare Devices', 'stethoscope', 1),
  ('MEDICAL_SUPPLIES', 'OTC_WELLNESS', 'Wellness & OTC Products', 'pills', 2),
  ('STATIONERY_OFFICE', 'STATIONERY', 'Stationery', 'pencil', 1),
  ('STATIONERY_OFFICE', 'OFFICE_SUPPLIES', 'Office Supplies & Printing', 'file', 2),
  ('AGRI_GARDEN', 'SEEDS_FERTILISERS', 'Seeds & Fertilisers', 'leaf', 1),
  ('AGRI_GARDEN', 'GARDEN_TOOLS', 'Garden Tools & Pots', 'flower', 2),
  ('INDUSTRIAL_SUPPLIES', 'BUILDING_MATERIALS', 'Building Materials (cement, steel, sand)', 'building', 1),
  ('INDUSTRIAL_SUPPLIES', 'SAFETY_EQUIPMENT', 'Safety Equipment', 'shield', 2),
  ('INDUSTRIAL_SUPPLIES', 'PACKAGING', 'Packaging Materials', 'package', 3)
) AS s(cat, code, name, icon, ord) ON c.code = s.cat
ON CONFLICT (category_id, code) DO NOTHING;

-- ---- Brands -------------------------------------------------------------------------------------------------
CREATE TABLE brands (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(120) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_brands_name ON brands(lower(name));

-- ---- Products -----------------------------------------------------------------------------------------------
CREATE TABLE products (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                    VARCHAR(80)   NOT NULL UNIQUE,
    subcategory_id          UUID          NOT NULL REFERENCES subcategories(id),
    brand_id                UUID REFERENCES brands(id),
    name                    VARCHAR(200)  NOT NULL,
    description             TEXT,
    unit                    VARCHAR(40)   NOT NULL DEFAULT 'each',
    -- Key/value specifications shown on the product page, e.g. {"Wattage": "9 W", "Base": "B22"}.
    specs                   JSONB         NOT NULL DEFAULT '{}'::jsonb,
    -- GST rate used to show the tax included in a listed price.
    tax_rate_percent        NUMERIC(5,2)  NOT NULL DEFAULT 18 CHECK (tax_rate_percent >= 0 AND tax_rate_percent <= 40),
    hsn_code                VARCHAR(12),
    image_url               VARCHAR(500),
    -- ACTIVE: in the catalogue. PENDING: proposed by a shop, waiting for admin review.
    status                  VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PENDING', 'REJECTED', 'INACTIVE')),
    proposed_by_partner_id  UUID REFERENCES partners(id),
    review_note             VARCHAR(300),
    reviewed_by             UUID,
    reviewed_at             TIMESTAMPTZ,
    -- Link to the matching quote-menu item, if any (so "Get quotes" items and products line up).
    service_item_id         UUID REFERENCES service_items(id),
    sort_order              INT           NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_products_subcategory ON products(subcategory_id, sort_order) WHERE status = 'ACTIVE';
CREATE INDEX ix_products_status ON products(status);
-- Full-text (with English stemming: "bulbs" finds "bulb") and fuzzy (typos: "hamer" finds "hammer") search.
CREATE INDEX ix_products_fts ON products USING gin (to_tsvector('english', name || ' ' || coalesce(description, '')));
CREATE INDEX ix_products_trgm ON products USING gin (lower(name) gin_trgm_ops);
CREATE INDEX ix_brands_trgm ON brands USING gin (lower(name) gin_trgm_ops);

CREATE TABLE product_variants (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id  UUID         NOT NULL REFERENCES products(id),
    sku         VARCHAR(80)  UNIQUE,
    name        VARCHAR(120) NOT NULL DEFAULT 'Standard',
    attributes  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    sort_order  INT          NOT NULL DEFAULT 0,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_variants_product ON product_variants(product_id, sort_order);

-- ---- Vendor listings ----------------------------------------------------------------------------------------
CREATE TABLE vendor_listings (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_id        UUID          NOT NULL REFERENCES partners(id),
    variant_id        UUID          NOT NULL REFERENCES product_variants(id),
    price             NUMERIC(12,2) NOT NULL CHECK (price > 0),
    mrp               NUMERIC(12,2) CHECK (mrp IS NULL OR mrp > 0),
    -- Declared by the shop. LIMITED = few left; ON_REQUEST = shop arranges it (lead time applies).
    stock_status      VARCHAR(20)   NOT NULL DEFAULT 'IN_STOCK' CHECK (stock_status IN ('IN_STOCK', 'LIMITED', 'OUT_OF_STOCK', 'ON_REQUEST')),
    stock_updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    moq               INT           NOT NULL DEFAULT 1 CHECK (moq >= 1),
    max_order_qty     INT CHECK (max_order_qty IS NULL OR max_order_qty >= 1),
    -- Optional bulk tier: this unit price applies from bulk_min_qty upwards (wholesale).
    bulk_price        NUMERIC(12,2) CHECK (bulk_price IS NULL OR bulk_price > 0),
    bulk_min_qty      INT CHECK (bulk_min_qty IS NULL OR bulk_min_qty >= 2),
    lead_time_days    INT           NOT NULL DEFAULT 0 CHECK (lead_time_days >= 0 AND lead_time_days <= 90),
    notes             VARCHAR(300),
    -- PENDING: new listing waiting for admin review. ACTIVE: visible. PAUSED: hidden by the shop or admin.
    status            VARCHAR(20)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACTIVE', 'PAUSED', 'REJECTED')),
    review_note       VARCHAR(300),
    reviewed_by       UUID,
    reviewed_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (partner_id, variant_id),
    CHECK ((bulk_price IS NULL) = (bulk_min_qty IS NULL)),
    CHECK (max_order_qty IS NULL OR max_order_qty >= moq)
);
CREATE INDEX ix_listings_variant ON vendor_listings(variant_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_listings_partner ON vendor_listings(partner_id, status);

INSERT INTO platform_settings(key, value, description) VALUES
 ('marketplace.search_radius_km', '25', 'Shops farther than this from the customer are not shown in Shop & Buy'),
 ('marketplace.listing_auto_approve', 'false', 'true: new shop listings go live without admin review (price/stock edits are always instant)')
ON CONFLICT (key) DO NOTHING;

-- ---- Starter catalogue: one product per Hardware & Supplies menu item (generic, unbranded) ----------------
INSERT INTO products(code, subcategory_id, name, unit, tax_rate_percent, service_item_id, sort_order, specs)
SELECT i.code, i.subcategory_id, i.name, COALESCE(i.unit, 'each'), 18, i.id, i.sort_order,
       CASE WHEN i.menu_group IS NULL THEN '{}'::jsonb ELSE jsonb_build_object('Type', i.menu_group) END
FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id JOIN categories c ON c.id = s.category_id
WHERE c.code = 'HARDWARE_SUPPLIES' AND i.active
ON CONFLICT (code) DO NOTHING;

INSERT INTO brands(name) VALUES ('Havells'), ('Anchor'), ('Philips'), ('Asian Paints'), ('Finolex'), ('Ultratech'), ('Supreme'), ('Generic')
ON CONFLICT DO NOTHING;

-- A few branded products next to the generic ones (brand-specific comparison).
INSERT INTO products(code, subcategory_id, brand_id, name, description, unit, tax_rate_percent, specs, sort_order)
SELECT p.code, s.id, b.id, p.name, p.descr, p.unit, p.tax, p.specs::jsonb, p.ord
FROM (VALUES
  ('HAVELLS_MCB_16A_SP', 'ELECTRICAL_SUPPLIES', 'Havells', 'Havells MCB 16 A Single Pole (C curve)', 'Miniature circuit breaker for lighting and power circuits', 'each', 18, '{"Rating": "16 A", "Poles": "Single", "Curve": "C"}', 20),
  ('ANCHOR_ROMA_SWITCH_6A', 'ELECTRICAL_SUPPLIES', 'Anchor', 'Anchor Roma 6 A One-Way Switch', 'Modular one-way switch, white', 'each', 18, '{"Rating": "6 A", "Type": "One-way", "Colour": "White"}', 21),
  ('PHILIPS_LED_9W', 'ELECTRICAL_SUPPLIES', 'Philips', 'Philips Stellar Bright LED Bulb 9 W', 'B22 base LED bulb', 'each', 12, '{"Wattage": "9 W", "Base": "B22", "Life": "15,000 hours"}', 22),
  ('FINOLEX_WIRE_1_5', 'ELECTRICAL_SUPPLIES', 'Finolex', 'Finolex FR PVC Wire 1.5 sq mm (90 m)', 'Flame-retardant copper house wire', 'per coil', 18, '{"Size": "1.5 sq mm", "Length": "90 m", "Conductor": "Copper"}', 23),
  ('ASIAN_TRACTOR_EMULSION_1L', 'PAINT_SUPPLIES', 'Asian Paints', 'Asian Paints Tractor Emulsion (1 L)', 'Interior emulsion, white base', 'per litre', 18, '{"Finish": "Matt", "Coverage": "100-120 sq ft/L"}', 20),
  ('SUPREME_PVC_PIPE_HALF', 'PLUMBING_SUPPLIES', 'Supreme', 'Supreme uPVC Pipe 1/2 inch (3 m)', 'Plumbing pipe, SCH 40', 'per length', 18, '{"Size": "1/2 inch", "Length": "3 m"}', 20),
  ('ULTRATECH_OPC_53', 'BUILDING_MATERIALS', 'Ultratech', 'Ultratech OPC 53 Grade Cement (50 kg)', 'Ordinary Portland cement, 53 grade', 'per bag', 28, '{"Grade": "OPC 53", "Weight": "50 kg"}', 1),
  ('TMT_BAR_12MM', 'BUILDING_MATERIALS', 'Generic', 'TMT Steel Bar 12 mm (Fe 550D)', 'Sold per kilogram', 'per kg', 18, '{"Diameter": "12 mm", "Grade": "Fe 550D"}', 2),
  ('RIVER_SAND', 'BUILDING_MATERIALS', 'Generic', 'River Sand (per cubic foot)', 'Washed construction sand', 'per cu ft', 5, '{"Type": "River sand"}', 3)
) AS p(code, sub, brand, name, descr, unit, tax, specs, ord)
JOIN subcategories s ON s.code = p.sub AND s.category_id IN (SELECT id FROM categories WHERE vertical = 'MARKETPLACE')
JOIN brands b ON lower(b.name) = lower(p.brand)
ON CONFLICT (code) DO NOTHING;

-- Every product gets a standard variant; the LED bulbs also come in two colour temperatures.
INSERT INTO product_variants(product_id, name, sku)
SELECT p.id, 'Standard', p.code FROM products p
WHERE p.code NOT IN ('LED_BULB_9W', 'PHILIPS_LED_9W') AND NOT EXISTS (SELECT 1 FROM product_variants v WHERE v.product_id = p.id);

INSERT INTO product_variants(product_id, name, sku, attributes, sort_order)
SELECT p.id, v.name, p.code || '_' || v.suffix, v.attrs::jsonb, v.ord
FROM products p JOIN (VALUES ('Cool day light', 'CDL', '{"Colour": "6500 K cool day light"}', 1),
                             ('Warm white', 'WW', '{"Colour": "2700 K warm white"}', 2)) AS v(name, suffix, attrs, ord) ON TRUE
WHERE p.code IN ('LED_BULB_9W', 'PHILIPS_LED_9W')
ON CONFLICT (sku) DO NOTHING;
