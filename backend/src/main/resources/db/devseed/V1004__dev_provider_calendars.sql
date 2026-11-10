-- DEVELOPMENT / TEST ONLY: working hours for the seeded service providers (on a fresh database V16 runs before the
-- dev seeds create their partner roles, so the V16 backfill is repeated here; idempotent).
INSERT INTO partner_availability(partner_id, weekday, start_time, end_time)
SELECT DISTINCT r.partner_id, d.wd, TIME '09:00', TIME '19:00'
FROM partner_roles r CROSS JOIN generate_series(1, 6) AS d(wd)
WHERE r.role IN ('SERVICE_PROVIDER', 'TASK_PARTNER')
ON CONFLICT DO NOTHING;

-- Quick Fix Plumbers also works Sundays (morning only), so customers see a difference between providers.
INSERT INTO partner_availability(partner_id, weekday, start_time, end_time)
SELECT p.id, 7, TIME '09:00', TIME '13:00' FROM partners p JOIN users u ON u.id = p.user_id WHERE u.phone = '+919000000022'
ON CONFLICT DO NOTHING;
