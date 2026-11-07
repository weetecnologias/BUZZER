-- Coordinates for "use current location" (nearest locality lookup without an external geocoding provider)
-- and optional images for catalogue items. All editable by admins.

ALTER TABLE cities ADD COLUMN latitude NUMERIC(10,7), ADD COLUMN longitude NUMERIC(10,7), ADD COLUMN service_radius_km NUMERIC(6,2) NOT NULL DEFAULT 40;
ALTER TABLE localities ADD COLUMN latitude NUMERIC(10,7), ADD COLUMN longitude NUMERIC(10,7);
ALTER TABLE subcategories ADD COLUMN image_url VARCHAR(500);
ALTER TABLE service_items ADD COLUMN image_url VARCHAR(500);

UPDATE cities SET latitude = 17.3850, longitude = 78.4867 WHERE code = 'HYD';

-- Approximate locality centre points (Hyderabad launch).
UPDATE localities l SET latitude = v.lat, longitude = v.lng
FROM (VALUES
  ('Kondapur', 17.4615, 78.3639), ('Madhapur', 17.4483, 78.3915), ('Gachibowli', 17.4401, 78.3489),
  ('Kukatpally', 17.4849, 78.4138), ('Hitech City', 17.4474, 78.3762), ('Banjara Hills', 17.4156, 78.4347),
  ('Jubilee Hills', 17.4326, 78.4071), ('Ameerpet', 17.4375, 78.4482), ('Begumpet', 17.4447, 78.4664),
  ('Secunderabad', 17.4399, 78.4983), ('Miyapur', 17.4968, 78.3614), ('LB Nagar', 17.3457, 78.5522),
  ('Dilsukhnagar', 17.3688, 78.5247), ('Manikonda', 17.4045, 78.3866), ('Kompally', 17.5367, 78.4846),
  ('Uppal', 17.4058, 78.5591)
) AS v(name, lat, lng)
JOIN cities c ON c.code = 'HYD'
WHERE l.city_id = c.id AND l.name = v.name;
