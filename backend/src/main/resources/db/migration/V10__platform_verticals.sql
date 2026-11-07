-- Stage 3: BUZZER's four business verticals. Admin can switch each one on/off independently; every category belongs
-- to exactly one vertical. Disabled verticals disappear from customer browsing, search and checkout (data is kept).
CREATE TABLE platform_verticals (
    code        VARCHAR(30)  PRIMARY KEY CHECK (code ~ '^[A-Z_]+$'),
    name        VARCHAR(80)  NOT NULL,
    tagline     VARCHAR(200),
    icon        VARCHAR(40),
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order  INT          NOT NULL DEFAULT 0,
    updated_by  UUID         REFERENCES users(id),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO platform_verticals(code, name, tagline, icon, sort_order) VALUES
 ('MARKETPLACE', 'Shop & Buy', 'Products from local shops, wholesalers and distributors', 'store', 1),
 ('SERVICES', 'Book Services', 'Verified local professionals at your doorstep', 'tools', 2),
 ('TASKS', 'Tasks & Errands', 'Pickups, drops, collections and local errands', 'route', 3),
 ('ASSISTANCE', 'Personal Assistance', 'Your own BUZZER agent who gets it done', 'heart', 4);

ALTER TABLE categories ADD COLUMN vertical VARCHAR(30) NOT NULL DEFAULT 'SERVICES' REFERENCES platform_verticals(code);
CREATE INDEX ix_categories_vertical ON categories(vertical, sort_order);

UPDATE categories SET vertical = 'MARKETPLACE' WHERE code IN ('HARDWARE_SUPPLIES', 'PRODUCT_SOURCING');
UPDATE categories SET vertical = 'TASKS' WHERE code IN ('DAILY_ERRANDS', 'DELIVERY');
UPDATE categories SET vertical = 'ASSISTANCE' WHERE code IN ('SPECIAL_REQUESTS');
