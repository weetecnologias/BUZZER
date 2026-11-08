-- DEVELOPMENT / TEST ONLY: marketplace demo data.
--   Sri Sai Hardware          +91 90000 00026  retail shop, Kukatpally, pickup + delivery (Rs 40, free above Rs 1,000)
--   Bright Spark Electricals  +91 90000 00024  electrician who also sells electrical supplies, Madhapur, pickup only
--   Deccan Building Supplies  +91 90000 00027  wholesaler, Miyapur, bulk prices and minimum order quantities, delivery
--   FreshMart Supermarket     +91 90000 00028  grocery, Kondapur, pickup + delivery
--   Vijaya Electronics        +91 90000 00029  NEW shop waiting for admin approval (role and listings pending)
-- Runs after V12 on a fresh database, so it repeats the V12 role/location backfill for the seeded partners (idempotent).

-- ---- New partners -------------------------------------------------------------------------------------------
INSERT INTO users(phone, full_name) VALUES
 ('+919000000027', 'Deccan Building Supplies'), ('+919000000028', 'FreshMart Supermarket'), ('+919000000029', 'Vijaya Electronics')
ON CONFLICT (phone) DO NOTHING;

INSERT INTO user_roles(user_id, role)
SELECT id, 'PARTNER' FROM users WHERE phone IN ('+919000000027', '+919000000028', '+919000000029')
ON CONFLICT DO NOTHING;

INSERT INTO partners(user_id, partner_type, display_name, business_name, contact_phone, city_id, address, registration_status, kyc_status)
SELECT u.id, 'BUSINESS', v.name, v.business, u.phone, (SELECT id FROM cities WHERE code = 'HYD'), v.addr, v.reg, v.kyc
FROM users u JOIN (VALUES
  ('+919000000027', 'Deccan Building Supplies', 'Deccan Building Supplies (Wholesale)', 'Plot 14, Industrial Area, Miyapur', 'APPROVED', 'VERIFIED'),
  ('+919000000028', 'FreshMart Supermarket', 'FreshMart Supermarket', 'Botanical Garden Road, Kondapur', 'APPROVED', 'VERIFIED'),
  ('+919000000029', 'Vijaya Electronics', 'Vijaya Electronics & Appliances', 'Main Road, Ameerpet', 'SUBMITTED', 'SUBMITTED')
) AS v(phone, name, business, addr, reg, kyc) ON v.phone = u.phone
ON CONFLICT DO NOTHING;

INSERT INTO partner_capabilities(partner_id, subcategory_id, status, reviewed_at)
SELECT p.id, s.id, v.status, CASE WHEN v.status = 'APPROVED' THEN now() END
FROM partners p JOIN users u ON u.id = p.user_id
JOIN (VALUES
  ('+919000000027', 'BUILDING_MATERIALS', 'APPROVED'), ('+919000000027', 'HARDWARE_STORE', 'APPROVED'),
  ('+919000000027', 'PLUMBING_SUPPLIES', 'APPROVED'), ('+919000000027', 'PAINT_SUPPLIES', 'APPROVED'),
  ('+919000000028', 'STAPLES', 'APPROVED'), ('+919000000028', 'HOUSEHOLD_ESSENTIALS', 'APPROVED'), ('+919000000028', 'BEVERAGES_SNACKS', 'APPROVED'),
  ('+919000000029', 'ELECTRICAL_SUPPLIES', 'REQUESTED'), ('+919000000029', 'HOME_APPLIANCES', 'REQUESTED')
) AS v(phone, sub, status) ON v.phone = u.phone
JOIN subcategories s ON s.code = v.sub AND s.category_id IN (SELECT id FROM categories WHERE vertical = 'MARKETPLACE')
ON CONFLICT DO NOTHING;

INSERT INTO terms_acceptances(user_id, terms_version_id)
SELECT u.id, (SELECT id FROM terms_versions WHERE audience = 'PARTNER' AND version = '2026.1')
FROM users u WHERE u.phone IN ('+919000000027', '+919000000028', '+919000000029')
ON CONFLICT DO NOTHING;

-- ---- Roles (same backfill as V12, then the explicit shop types) ----------------------------------------------
INSERT INTO partner_roles(partner_id, role, status, reviewed_at, review_note)
SELECT DISTINCT pc.partner_id,
       CASE c.vertical WHEN 'MARKETPLACE' THEN 'RETAIL_SHOP' WHEN 'TASKS' THEN 'TASK_PARTNER' ELSE 'SERVICE_PROVIDER' END,
       CASE WHEN p.registration_status = 'APPROVED' THEN 'APPROVED' ELSE 'REQUESTED' END,
       CASE WHEN p.registration_status = 'APPROVED' THEN now() END, 'Dev seed'
FROM partner_capabilities pc JOIN partners p ON p.id = pc.partner_id
JOIN subcategories s ON s.id = pc.subcategory_id JOIN categories c ON c.id = s.category_id
WHERE pc.status IN ('APPROVED', 'REQUESTED')
ON CONFLICT DO NOTHING;

INSERT INTO partner_roles(partner_id, role, status, review_note)
SELECT p.id, 'SERVICE_PROVIDER', CASE WHEN p.registration_status = 'APPROVED' THEN 'APPROVED' ELSE 'REQUESTED' END, 'Dev seed'
FROM partners p WHERE NOT EXISTS (SELECT 1 FROM partner_roles r WHERE r.partner_id = p.id);

-- Deccan is a wholesaler (not a retail shop).
DELETE FROM partner_roles WHERE role = 'RETAIL_SHOP' AND partner_id = (SELECT p.id FROM partners p JOIN users u ON u.id = p.user_id WHERE u.phone = '+919000000027');
INSERT INTO partner_roles(partner_id, role, status, reviewed_at, review_note)
SELECT p.id, 'WHOLESALER', 'APPROVED', now(), 'Dev seed' FROM partners p JOIN users u ON u.id = p.user_id WHERE u.phone = '+919000000027'
ON CONFLICT DO NOTHING;

-- ---- Locations ----------------------------------------------------------------------------------------------
INSERT INTO partner_locations(partner_id, name, address, city_id, locality_id, latitude, longitude, service_radius_km, phone,
                              pickup_enabled, delivery_enabled, is_primary)
SELECT p.id, COALESCE(p.business_name, p.display_name), p.address, p.city_id, l.id,
       COALESCE(l.latitude, ci.latitude), COALESCE(l.longitude, ci.longitude), LEAST(GREATEST(COALESCE(ci.service_radius_km, 40), 1), 200),
       p.contact_phone, TRUE, FALSE, TRUE
FROM partners p JOIN cities ci ON ci.id = p.city_id
LEFT JOIN LATERAL (SELECT lo.id, lo.latitude, lo.longitude FROM partner_service_areas sa JOIN localities lo ON lo.id = sa.locality_id
                   WHERE sa.partner_id = p.id ORDER BY lo.name LIMIT 1) l ON TRUE
WHERE NOT EXISTS (SELECT 1 FROM partner_locations x WHERE x.partner_id = p.id);

UPDATE partner_locations pl SET name = v.name, address = v.addr, locality_id = lo.id, latitude = lo.latitude, longitude = lo.longitude,
       service_radius_km = v.radius, opening_hours = v.hours, pickup_enabled = v.pickup, delivery_enabled = v.delivery,
       delivery_fee = v.fee, free_delivery_above = v.free_above, min_order_value = v.min_order, updated_at = now()
FROM partners p JOIN users u ON u.id = p.user_id
JOIN (VALUES
  ('+919000000026', 'Sri Sai Hardware - Kukatpally', 'Shop 3, KPHB Main Road, Kukatpally', 'Kukatpally', 8::numeric, '9 AM - 9 PM, all days', TRUE, TRUE, 40::numeric, 1000::numeric, 0::numeric),
  ('+919000000024', 'Bright Spark Electricals - Madhapur', '2-48, Ayyappa Society, Madhapur', 'Madhapur', 6, '10 AM - 8 PM, closed Sunday', TRUE, FALSE, 0, NULL, 0),
  ('+919000000027', 'Deccan Building Supplies - Miyapur', 'Plot 14, Industrial Area, Miyapur', 'Miyapur', 20, '8 AM - 7 PM, all days', TRUE, TRUE, 300, 25000, 2000),
  ('+919000000028', 'FreshMart - Kondapur', 'Botanical Garden Road, Kondapur', 'Kondapur', 5, '7 AM - 11 PM, all days', TRUE, TRUE, 30, 500, 199),
  ('+919000000029', 'Vijaya Electronics - Ameerpet', 'Main Road, Ameerpet', 'Ameerpet', 10, '10 AM - 9 PM', TRUE, TRUE, 50, 2000, 0)
) AS v(phone, name, addr, loc, radius, hours, pickup, delivery, fee, free_above, min_order) ON v.phone = u.phone
JOIN localities lo ON lo.name = v.loc AND lo.city_id = p.city_id
WHERE pl.partner_id = p.id AND pl.is_primary;

-- ---- Grocery catalogue (dev only) ---------------------------------------------------------------------------
INSERT INTO brands(name) VALUES ('Aashirvaad'), ('Tata Sampann'), ('Fortune'), ('Surf Excel'), ('India Gate') ON CONFLICT DO NOTHING;

INSERT INTO products(code, subcategory_id, brand_id, name, description, unit, tax_rate_percent, specs, sort_order)
SELECT v.code, s.id, b.id, v.name, v.descr, v.unit, v.tax, v.specs::jsonb, v.ord
FROM (VALUES
  ('AASHIRVAAD_ATTA_5KG', 'STAPLES', 'Aashirvaad', 'Aashirvaad Whole Wheat Atta (5 kg)', 'Shudh chakki atta', 'per pack', 5, '{"Weight": "5 kg"}', 1),
  ('TATA_TOOR_DAL_1KG', 'STAPLES', 'Tata Sampann', 'Tata Sampann Toor Dal (1 kg)', 'Unpolished toor dal', 'per pack', 5, '{"Weight": "1 kg"}', 2),
  ('INDIA_GATE_SONA_10KG', 'STAPLES', 'India Gate', 'India Gate Sona Masoori Rice (10 kg)', 'Everyday rice', 'per bag', 5, '{"Weight": "10 kg"}', 3),
  ('FORTUNE_SUNFLOWER_1L', 'STAPLES', 'Fortune', 'Fortune Sunflower Oil (1 L)', 'Refined sunflower oil', 'per pouch', 5, '{"Volume": "1 L"}', 4),
  ('SURF_EXCEL_1KG', 'HOUSEHOLD_ESSENTIALS', 'Surf Excel', 'Surf Excel Easy Wash Detergent (1 kg)', 'Detergent powder', 'per pack', 18, '{"Weight": "1 kg"}', 1)
) AS v(code, sub, brand, name, descr, unit, tax, specs, ord)
JOIN subcategories s ON s.code = v.sub AND s.category_id IN (SELECT id FROM categories WHERE vertical = 'MARKETPLACE')
JOIN brands b ON lower(b.name) = lower(v.brand)
ON CONFLICT (code) DO NOTHING;

INSERT INTO product_variants(product_id, name, sku)
SELECT p.id, 'Standard', p.code FROM products p
WHERE p.code IN ('AASHIRVAAD_ATTA_5KG', 'TATA_TOOR_DAL_1KG', 'INDIA_GATE_SONA_10KG', 'FORTUNE_SUNFLOWER_1L', 'SURF_EXCEL_1KG')
  AND NOT EXISTS (SELECT 1 FROM product_variants v WHERE v.product_id = p.id);

-- ---- Listings ---------------------------------------------------------------------------------------------
INSERT INTO vendor_listings(partner_id, variant_id, price, mrp, stock_status, moq, bulk_price, bulk_min_qty, lead_time_days, status, reviewed_at)
SELECT p.id, pv.id, v.price, v.mrp, v.stock, v.moq, v.bulk_price, v.bulk_min, v.lead, v.status, CASE WHEN v.status = 'ACTIVE' THEN now() END
FROM (VALUES
  -- Sri Sai Hardware (retail)
  ('+919000000026', 'LED_BULB_9W_CDL', 94::numeric, 99::numeric, 'IN_STOCK', 1, NULL::numeric, NULL::int, 0, 'ACTIVE'),
  ('+919000000026', 'LED_BULB_9W_WW', 94, 99, 'LIMITED', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000026', 'SWITCH_6A', 43, 45, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000026', 'SOCKET_16A', 114, 120, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000026', 'MCB_16A', 247, 260, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000026', 'HAVELLS_MCB_16A_SP', 345, 389, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000026', 'EXTENSION_BOARD', 428, 450, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000026', 'HAMMER', 333, 350, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000026', 'SCREWS_PACK', 114, 120, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000026', 'WALL_PLUGS', 57, 60, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000026', 'MEASURING_TAPE', 175, 180, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000026', 'BIB_TAP', 428, 450, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000026', 'TEFLON_TAPE', 19, 20, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000026', 'WALL_PUTTY', 399, 420, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000026', 'ASIAN_TRACTOR_EMULSION_1L', 265, 285, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000026', 'PAINT_ROLLER', 250, 260, 'OUT_OF_STOCK', 1, NULL, NULL, 2, 'ACTIVE'),
  -- Bright Spark Electricals
  ('+919000000024', 'LED_BULB_9W_CDL', 89, 99, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000024', 'SWITCH_6A', 42, 45, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000024', 'SOCKET_16A', 118, 120, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000024', 'MCB_16A', 239, 260, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000024', 'HAVELLS_MCB_16A_SP', 335, 389, 'LIMITED', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000024', 'ANCHOR_ROMA_SWITCH_6A', 54, 62, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000024', 'PHILIPS_LED_9W_CDL', 119, 135, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000024', 'FINOLEX_WIRE_1_5', 1590, 1799, 'ON_REQUEST', 1, NULL, NULL, 1, 'ACTIVE'),
  ('+919000000024', 'INSULATION_TAPE', 22, 25, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  -- Deccan Building Supplies (wholesale: minimum quantities and bulk tiers)
  ('+919000000027', 'ULTRATECH_OPC_53', 395, 430, 'IN_STOCK', 10, 380, 50, 0, 'ACTIVE'),
  ('+919000000027', 'TMT_BAR_12MM', 68, NULL, 'IN_STOCK', 100, 66, 1000, 1, 'ACTIVE'),
  ('+919000000027', 'RIVER_SAND', 55, NULL, 'IN_STOCK', 100, NULL, NULL, 1, 'ACTIVE'),
  ('+919000000027', 'SUPREME_PVC_PIPE_HALF', 150, 175, 'IN_STOCK', 10, 140, 50, 0, 'ACTIVE'),
  ('+919000000027', 'PVC_PIPE_HALF', 138, 160, 'IN_STOCK', 10, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000027', 'ELBOW_HALF', 11, 15, 'IN_STOCK', 50, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000027', 'WALL_PUTTY', 379, 420, 'IN_STOCK', 5, 365, 25, 0, 'ACTIVE'),
  -- FreshMart Supermarket
  ('+919000000028', 'AASHIRVAAD_ATTA_5KG', 265, 289, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000028', 'TATA_TOOR_DAL_1KG', 172, 189, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000028', 'INDIA_GATE_SONA_10KG', 699, 799, 'LIMITED', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000028', 'FORTUNE_SUNFLOWER_1L', 145, 165, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  ('+919000000028', 'SURF_EXCEL_1KG', 132, 140, 'IN_STOCK', 1, NULL, NULL, 0, 'ACTIVE'),
  -- Vijaya Electronics: waiting for admin review
  ('+919000000029', 'PHILIPS_LED_9W_CDL', 115, 135, 'IN_STOCK', 1, NULL, NULL, 0, 'PENDING'),
  ('+919000000029', 'EXTENSION_BOARD', 410, 450, 'IN_STOCK', 1, NULL, NULL, 0, 'PENDING')
) AS v(phone, sku, price, mrp, stock, moq, bulk_price, bulk_min, lead, status)
JOIN users u ON u.phone = v.phone JOIN partners p ON p.user_id = u.id
JOIN product_variants pv ON pv.sku = v.sku
ON CONFLICT (partner_id, variant_id) DO NOTHING;
