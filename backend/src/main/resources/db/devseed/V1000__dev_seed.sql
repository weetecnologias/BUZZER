-- DEVELOPMENT / TEST SEED DATA ONLY (loaded only by the dev and test profiles, never in production).
-- Staff and partners sign in with their phone + OTP. In dev the OTP is returned by the API.
--
--   Super admin       +91 90000 00001
--   Agent manager     +91 90000 00010  (Suresh Reddy)
--   Agent             +91 90000 00011  (Rahul Sharma)
--   Agent             +91 90000 00012  (Priya Nair)
--   Plumber partners  +91 90000 00021 / 22 / 23 (approved + KYC verified, Plumbing)
--   Electrician       +91 90000 00024 (approved, Electrical)
--   Pending partner   +91 90000 00025 (submitted, awaiting review)

INSERT INTO users(phone, full_name) VALUES
 ('+919000000001','BUZZER Super Admin'),
 ('+919000000010','Suresh Reddy'),
 ('+919000000011','Rahul Sharma'),
 ('+919000000012','Priya Nair'),
 ('+919000000021','Ramesh Plumbing Works'),
 ('+919000000022','Quick Fix Plumbers'),
 ('+919000000023','Aqua Care Services'),
 ('+919000000024','Bright Spark Electricals'),
 ('+919000000025','New Partner Pending');

INSERT INTO user_roles(user_id, role)
SELECT id, r.role FROM users u JOIN (VALUES
 ('+919000000001','SUPER_ADMIN'),('+919000000001','ADMIN'),
 ('+919000000010','AGENT_MANAGER'),('+919000000010','AGENT'),
 ('+919000000011','AGENT'),('+919000000012','AGENT'),
 ('+919000000021','PARTNER'),('+919000000022','PARTNER'),('+919000000023','PARTNER'),('+919000000024','PARTNER'),('+919000000025','PARTNER')
) AS r(phone, role) ON u.phone = r.phone;

INSERT INTO agents(user_id, display_name, city_id, languages, is_manager, max_customers)
SELECT u.id, u.full_name, (SELECT id FROM cities WHERE code = 'HYD'), a.langs, a.mgr, 300
FROM users u JOIN (VALUES ('+919000000010','en,te,hi',TRUE),('+919000000011','en,hi,te',FALSE),('+919000000012','en,ml,te',FALSE)) AS a(phone, langs, mgr)
ON u.phone = a.phone;

UPDATE agents SET manager_id = (SELECT a.id FROM agents a JOIN users u ON u.id = a.user_id WHERE u.phone = '+919000000010')
WHERE user_id IN (SELECT id FROM users WHERE phone IN ('+919000000011','+919000000012'));

INSERT INTO partners(user_id, partner_type, display_name, business_name, contact_phone, city_id, registration_status, kyc_status)
SELECT u.id, p.ptype, u.full_name, p.biz, u.phone, (SELECT id FROM cities WHERE code = 'HYD'), p.reg, p.kyc
FROM users u JOIN (VALUES
 ('+919000000021','INDIVIDUAL',NULL,'APPROVED','VERIFIED'),
 ('+919000000022','BUSINESS','Quick Fix Plumbers Pvt Ltd','APPROVED','VERIFIED'),
 ('+919000000023','BUSINESS','Aqua Care Services LLP','APPROVED','VERIFIED'),
 ('+919000000024','INDIVIDUAL',NULL,'APPROVED','VERIFIED'),
 ('+919000000025','INDIVIDUAL',NULL,'SUBMITTED','SUBMITTED')
) AS p(phone, ptype, biz, reg, kyc) ON u.phone = p.phone;

INSERT INTO partner_capabilities(partner_id, subcategory_id, status, reviewed_at)
SELECT p.id, s.id, c.status, now() FROM partners p JOIN users u ON u.id = p.user_id
JOIN (VALUES ('+919000000021','PLUMBING','APPROVED'),('+919000000022','PLUMBING','APPROVED'),('+919000000023','PLUMBING','APPROVED'),
             ('+919000000024','ELECTRICAL','APPROVED'),('+919000000025','PLUMBING','REQUESTED')) AS c(phone, sub, status) ON u.phone = c.phone
JOIN subcategories s ON s.code = c.sub;

INSERT INTO terms_acceptances(user_id, terms_version_id)
SELECT p.user_id, (SELECT id FROM terms_versions WHERE audience = 'PARTNER' AND version = '2026.1') FROM partners p;
