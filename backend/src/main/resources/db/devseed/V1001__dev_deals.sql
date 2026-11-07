-- DEVELOPMENT / TEST ONLY: sample "Exclusive Deals" so the deals UI can be exercised.
-- Production deals are created by the BUZZER team in Admin -> Catalogue (original price + deal label)
-- once real partner offers are agreed; they are never seeded automatically.

INSERT INTO subcategories(category_id, code, name, icon, sort_order)
SELECT id, 'DEALS', 'Exclusive Deals', 'tag', 1 FROM categories WHERE code = 'DINING'
ON CONFLICT (category_id, code) DO UPDATE SET name = EXCLUDED.name, sort_order = 1;

INSERT INTO service_items(subcategory_id, code, name, description, pricing_type, base_price, original_price, deal_label, unit, sort_order)
SELECT s.id, d.code, d.name, d.descr, d.ptype, d.price, d.mrp, d.label, d.unit, d.ord
FROM subcategories s JOIN categories c ON c.id = s.category_id AND c.code = 'DINING'
JOIN (VALUES
  ('BUFFET_DEAL', 'Weekend Buffet at Partner Restaurants', 'Lunch or dinner buffet, Fri-Sun', 'PER_UNIT', 599::numeric, 899::numeric, 'Weekend special', 'per person', 1),
  ('FINE_DINE_DEAL', 'Fine Dining for Two', '3-course meal with mocktails', 'FIXED', 1999, 2999, 'Couples deal', NULL, 2),
  ('ROOFTOP_DEAL', 'Rooftop Cafe - Flat 25% Off', 'Pre-booked table, valid on food bill', 'FIXED', 0, NULL, 'Flat 25% off', NULL, 3),
  ('PLAYZONE_DEAL', 'Play Zone Family Pass (2 kids + 2 adults)', 'Unlimited play for 2 hours', 'FIXED', 1499, 2196, 'Family saver', NULL, 4)
) AS d(code, name, descr, ptype, price, mrp, label, unit, ord) ON s.code = 'DEALS'
ON CONFLICT (subcategory_id, code) DO NOTHING;

UPDATE service_items si SET original_price = 1599, deal_label = 'Limited offer'
FROM subcategories s WHERE si.subcategory_id = s.id AND s.code = 'CLEANING' AND si.code = 'KITCHEN_CLEAN';
UPDATE service_items si SET original_price = 799, deal_label = 'Summer offer'
FROM subcategories s WHERE si.subcategory_id = s.id AND s.code = 'AC' AND si.code = 'AC_JET_SERVICE';

-- Demo partner coverage for the new menus (Aqua Care also cleans; Bright Spark also does appliances).
INSERT INTO partner_capabilities(partner_id, subcategory_id, status, reviewed_at)
SELECT p.id, s.id, 'APPROVED', now() FROM partners p JOIN users u ON u.id = p.user_id
JOIN (VALUES ('+919000000023', 'CLEANING'), ('+919000000024', 'APPLIANCE'), ('+919000000024', 'AC')) AS x(phone, sub) ON u.phone = x.phone
JOIN subcategories s ON s.code = x.sub
ON CONFLICT DO NOTHING;
