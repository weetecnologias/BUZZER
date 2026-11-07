-- DEVELOPMENT / TEST ONLY: a demo hardware shop for the direct-quote flow.
--   Sri Sai Hardware   +91 90000 00026  (approved, KYC verified, all Hardware & Supplies subcategories)
--   Bright Spark Electricals also supplies electrical items, so customers can compare two quotes.

INSERT INTO users(phone, full_name) VALUES ('+919000000026', 'Sri Sai Hardware') ON CONFLICT (phone) DO NOTHING;

INSERT INTO user_roles(user_id, role)
SELECT id, 'PARTNER' FROM users WHERE phone = '+919000000026'
ON CONFLICT DO NOTHING;

INSERT INTO partners(user_id, partner_type, display_name, business_name, contact_phone, city_id, registration_status, kyc_status)
SELECT u.id, 'BUSINESS', 'Sri Sai Hardware', 'Sri Sai Hardware & Electricals', u.phone, (SELECT id FROM cities WHERE code = 'HYD'),
       'APPROVED', 'VERIFIED'
FROM users u WHERE u.phone = '+919000000026'
ON CONFLICT DO NOTHING;

INSERT INTO partner_capabilities(partner_id, subcategory_id, status, reviewed_at)
SELECT p.id, s.id, 'APPROVED', now()
FROM partners p JOIN users u ON u.id = p.user_id
JOIN subcategories s ON s.code IN ('HARDWARE_STORE', 'ELECTRICAL_SUPPLIES', 'PLUMBING_SUPPLIES', 'PAINT_SUPPLIES')
WHERE u.phone = '+919000000026'
ON CONFLICT DO NOTHING;

INSERT INTO partner_capabilities(partner_id, subcategory_id, status, reviewed_at)
SELECT p.id, s.id, 'APPROVED', now()
FROM partners p JOIN users u ON u.id = p.user_id JOIN subcategories s ON s.code = 'ELECTRICAL_SUPPLIES'
WHERE u.phone = '+919000000024'
ON CONFLICT DO NOTHING;

INSERT INTO terms_acceptances(user_id, terms_version_id)
SELECT u.id, (SELECT id FROM terms_versions WHERE audience = 'PARTNER' AND version = '2026.1')
FROM users u WHERE u.phone = '+919000000026'
ON CONFLICT DO NOTHING;

-- Sri Sai's approved shop prices (a little below the listed price) - pre-filled when they quote.
INSERT INTO partner_price_items(partner_id, service_item_id, price, unit, status, approved_price, reviewed_at)
SELECT p.id, i.id, round(i.base_price * 0.95), i.unit, 'APPROVED', round(i.base_price * 0.95), now()
FROM partners p JOIN users u ON u.id = p.user_id
JOIN service_items i ON i.code IN ('SCREWS_PACK', 'WALL_PLUGS', 'HAMMER', 'LED_BULB_9W', 'SWITCH_6A', 'SOCKET_16A', 'MCB_16A',
                                   'EXTENSION_BOARD', 'BIB_TAP', 'TEFLON_TAPE', 'WALL_PUTTY')
JOIN subcategories s ON s.id = i.subcategory_id JOIN categories c ON c.id = s.category_id AND c.code = 'HARDWARE_SUPPLIES'
WHERE u.phone = '+919000000026'
ON CONFLICT (partner_id, service_item_id) DO NOTHING;
