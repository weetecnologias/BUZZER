-- Direct quotes: customers send a cart (menu items + extra items not on the menu) straight to verified partners.
-- Partners mark availability and price per item; quotes reach the customer automatically. Agents are only for
-- subscribers ("Send to my agent") and as an automatic fallback when no partner can quote.

-- ---- Requests ---------------------------------------------------------------------------------------------
ALTER TABLE service_requests DROP CONSTRAINT IF EXISTS service_requests_booking_mode_check;
ALTER TABLE service_requests ADD CONSTRAINT service_requests_booking_mode_check
    CHECK (booking_mode IN ('SELF', 'AGENT_ASSISTED', 'AGENT_DIRECT', 'DIRECT_QUOTE'));

ALTER TABLE service_requests DROP CONSTRAINT IF EXISTS service_requests_agent_source_check;
ALTER TABLE service_requests ADD CONSTRAINT service_requests_agent_source_check
    CHECK (agent_source IN ('DEDICATED', 'ON_DUTY', 'NONE'));

ALTER TABLE service_requests
    -- AUTOMATED: partners quote and the customer decides without agent work. AGENT: an agent drives the request.
    ADD COLUMN handling_mode      VARCHAR(20) NOT NULL DEFAULT 'AGENT' CHECK (handling_mode IN ('AGENT', 'AUTOMATED')),
    -- When the handling agent first opened the ticket (drives the NEW highlight in the agent inbox).
    ADD COLUMN agent_seen_at      TIMESTAMPTZ,
    -- When an automated request fell back to an agent (no partner / no quotes in time).
    ADD COLUMN handed_to_agent_at TIMESTAMPTZ,
    ADD COLUMN handover_reason    TEXT;

CREATE INDEX ix_requests_automated_waiting ON service_requests(created_at)
    WHERE handling_mode = 'AUTOMATED' AND status = 'PARTNER_QUOTATION';
CREATE INDEX ix_requests_agent_unseen ON service_requests(agent_id) WHERE agent_seen_at IS NULL;

-- ---- Quotations -------------------------------------------------------------------------------------------
ALTER TABLE quotations DROP CONSTRAINT IF EXISTS quotations_status_check;
ALTER TABLE quotations ADD CONSTRAINT quotations_status_check
    CHECK (status IN ('SUBMITTED', 'SHARED', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED', 'CLOSED', 'REVISION_REQUESTED', 'REVISED'));

ALTER TABLE quotations
    -- Per item: key, name, quantity, available, unit_price, line_total, note.
    ADD COLUMN line_items    JSONB,
    ADD COLUMN revision_no   INT NOT NULL DEFAULT 1,
    -- What the customer asked the partner to change.
    ADD COLUMN revision_note TEXT,
    ADD COLUMN partner_note  TEXT;

-- ---- Settings ---------------------------------------------------------------------------------------------
INSERT INTO platform_settings(key, value, description) VALUES
 ('direct_quote.max_partners', '5', 'How many verified partners receive a direct quote request automatically'),
 ('direct_quote.no_quote_minutes', '60', 'If no partner quotes within this many minutes, the request is handed to an agent')
ON CONFLICT (key) DO NOTHING;

-- ---- Example: Hardware & Supplies (shops that quote availability + price for items) ---------------------
INSERT INTO categories(code, name, description, icon, sort_order, workflow_template, is_global_special)
VALUES ('HARDWARE_SUPPLIES', 'Hardware & Supplies', 'Hardware, electrical, plumbing and paint supplies from local shops - delivered', 'store', 3,
        'MENU_DRIVEN', FALSE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO category_cities(category_id, city_id)
SELECT c.id, ci.id FROM categories c CROSS JOIN cities ci WHERE c.code = 'HARDWARE_SUPPLIES' AND ci.active
ON CONFLICT DO NOTHING;

INSERT INTO subcategories(category_id, code, name, icon, sort_order)
SELECT c.id, s.code, s.name, s.icon, s.ord FROM categories c JOIN (VALUES
  ('HARDWARE_STORE', 'Hardware Store', 'hammer', 1),
  ('ELECTRICAL_SUPPLIES', 'Electrical Supplies', 'bulb', 2),
  ('PLUMBING_SUPPLIES', 'Plumbing & Sanitary', 'pipe', 3),
  ('PAINT_SUPPLIES', 'Paints & Tools', 'paint', 4)
) AS s(code, name, icon, ord) ON TRUE
WHERE c.code = 'HARDWARE_SUPPLIES'
ON CONFLICT (category_id, code) DO NOTHING;

INSERT INTO service_items(subcategory_id, code, name, pricing_type, base_price, unit, menu_group, sort_order, icon)
SELECT s.id, i.code, i.name, 'FIXED', i.price, i.unit, i.grp, i.ord, i.icon
FROM subcategories s JOIN categories c ON c.id = s.category_id AND c.code = 'HARDWARE_SUPPLIES'
JOIN (VALUES
  ('HARDWARE_STORE', 'SCREWS_PACK', 'Wood Screws (pack of 50)', 120::numeric, 'per pack', 'Fasteners', 1, 'nut'),
  ('HARDWARE_STORE', 'WALL_PLUGS', 'Wall Plugs / Rawl Plugs (pack of 50)', 60, 'per pack', 'Fasteners', 2, 'nut'),
  ('HARDWARE_STORE', 'ANCHOR_BOLTS', 'Anchor Bolts 10 mm (pack of 10)', 180, 'per pack', 'Fasteners', 3, 'nut'),
  ('HARDWARE_STORE', 'HAMMER', 'Claw Hammer', 350, 'each', 'Hand tools', 4, 'hammer'),
  ('HARDWARE_STORE', 'SCREWDRIVER_SET', 'Screwdriver Set (6 pcs)', 299, 'per set', 'Hand tools', 5, 'tools'),
  ('HARDWARE_STORE', 'MEASURING_TAPE', 'Measuring Tape 5 m', 180, 'each', 'Hand tools', 6, 'tools'),
  ('HARDWARE_STORE', 'DOOR_HINGES', 'Door Hinges 4 inch (pair)', 140, 'per pair', 'Door & furniture fittings', 7, 'key'),
  ('HARDWARE_STORE', 'TOWER_BOLT', 'Tower Bolt 6 inch', 90, 'each', 'Door & furniture fittings', 8, 'key'),
  ('HARDWARE_STORE', 'PADLOCK', 'Padlock 50 mm with 3 keys', 320, 'each', 'Door & furniture fittings', 9, 'key'),
  ('ELECTRICAL_SUPPLIES', 'LED_BULB_9W', 'LED Bulb 9 W', 99, 'each', 'Lighting', 1, 'bulb'),
  ('ELECTRICAL_SUPPLIES', 'LED_TUBE_20W', 'LED Tube Light 20 W', 349, 'each', 'Lighting', 2, 'bulb'),
  ('ELECTRICAL_SUPPLIES', 'SWITCH_6A', 'Modular Switch 6 A', 45, 'each', 'Switches & sockets', 3, 'plug'),
  ('ELECTRICAL_SUPPLIES', 'SOCKET_16A', 'Modular Socket 16 A', 120, 'each', 'Switches & sockets', 4, 'plug'),
  ('ELECTRICAL_SUPPLIES', 'EXTENSION_BOARD', 'Extension Board 4+1 with 2 m cord', 450, 'each', 'Switches & sockets', 5, 'plug'),
  ('ELECTRICAL_SUPPLIES', 'MCB_16A', 'MCB 16 A Single Pole', 260, 'each', 'Wiring & safety', 6, 'bolt'),
  ('ELECTRICAL_SUPPLIES', 'WIRE_1_5', 'Copper Wire 1.5 sq mm (90 m coil)', 1650, 'per coil', 'Wiring & safety', 7, 'bolt'),
  ('ELECTRICAL_SUPPLIES', 'INSULATION_TAPE', 'Insulation Tape', 25, 'each', 'Wiring & safety', 8, 'bolt'),
  ('PLUMBING_SUPPLIES', 'PVC_PIPE_HALF', 'PVC Pipe 1/2 inch (3 m)', 160, 'per length', 'Pipes & fittings', 1, 'pipe'),
  ('PLUMBING_SUPPLIES', 'ELBOW_HALF', 'PVC Elbow 1/2 inch', 15, 'each', 'Pipes & fittings', 2, 'pipe'),
  ('PLUMBING_SUPPLIES', 'TEFLON_TAPE', 'Teflon Tape', 20, 'each', 'Pipes & fittings', 3, 'pipe'),
  ('PLUMBING_SUPPLIES', 'BIB_TAP', 'Bib Tap (brass, chrome)', 450, 'each', 'Taps & valves', 4, 'pipe'),
  ('PLUMBING_SUPPLIES', 'ANGLE_VALVE', 'Angle Valve', 380, 'each', 'Taps & valves', 5, 'pipe'),
  ('PLUMBING_SUPPLIES', 'HEALTH_FAUCET', 'Health Faucet with Hose', 650, 'each', 'Bathroom', 6, 'pipe'),
  ('PLUMBING_SUPPLIES', 'SHOWER_HEAD', 'Shower Head', 550, 'each', 'Bathroom', 7, 'pipe'),
  ('PAINT_SUPPLIES', 'WALL_PUTTY', 'Wall Putty (5 kg)', 420, 'per bag', 'Paint & primer', 1, 'paint'),
  ('PAINT_SUPPLIES', 'PRIMER_1L', 'Wall Primer (1 L)', 280, 'per litre', 'Paint & primer', 2, 'paint'),
  ('PAINT_SUPPLIES', 'EMULSION_1L', 'Interior Emulsion Paint (1 L)', 450, 'per litre', 'Paint & primer', 3, 'paint'),
  ('PAINT_SUPPLIES', 'PAINT_BRUSH', 'Paint Brush 3 inch', 90, 'each', 'Tools', 4, 'brush'),
  ('PAINT_SUPPLIES', 'PAINT_ROLLER', 'Paint Roller 9 inch with tray', 260, 'per set', 'Tools', 5, 'brush'),
  ('PAINT_SUPPLIES', 'SANDPAPER', 'Sandpaper Sheets (pack of 10)', 80, 'per pack', 'Tools', 6, 'brush')
) AS i(sub, code, name, price, unit, grp, ord, icon) ON s.code = i.sub
ON CONFLICT (subcategory_id, code) DO NOTHING;
