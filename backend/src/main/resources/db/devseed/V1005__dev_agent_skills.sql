-- DEVELOPMENT / TEST ONLY: on a fresh database V17 runs before the seeded agents exist, so give them their skills here
-- (all verticals, like the V17 backfill; idempotent).
INSERT INTO agent_skills(agent_id, vertical) SELECT a.id, v.code FROM agents a CROSS JOIN platform_verticals v ON CONFLICT DO NOTHING;
