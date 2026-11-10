-- Stage 6: services & provider workflows. Customers pick a time slot and a provider themselves (instant booking);
-- providers keep a weekly calendar with time off and add completion evidence (before / after photos) next to the OTPs.

-- ---- Provider calendar ----------------------------------------------------------------------------------------
CREATE TABLE partner_availability (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_id  UUID     NOT NULL REFERENCES partners(id),
    -- ISO weekday: 1 = Monday ... 7 = Sunday. Several windows per day are allowed (e.g. 9-13 and 15-19).
    weekday     SMALLINT NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    start_time  TIME     NOT NULL,
    end_time    TIME     NOT NULL,
    CHECK (end_time > start_time),
    UNIQUE (partner_id, weekday, start_time)
);
CREATE INDEX ix_partner_availability_partner ON partner_availability(partner_id);

CREATE TABLE partner_time_off (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_id  UUID         NOT NULL REFERENCES partners(id),
    starts_on   DATE         NOT NULL,
    ends_on     DATE         NOT NULL,
    reason      VARCHAR(200),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (ends_on >= starts_on)
);
CREATE INDEX ix_partner_time_off_partner ON partner_time_off(partner_id, ends_on);

-- Existing service providers start with Monday-Saturday 09:00-19:00 (they can change it in the partner app).
INSERT INTO partner_availability(partner_id, weekday, start_time, end_time)
SELECT DISTINCT r.partner_id, d.wd, TIME '09:00', TIME '19:00'
FROM partner_roles r CROSS JOIN generate_series(1, 6) AS d(wd)
WHERE r.role IN ('SERVICE_PROVIDER', 'TASK_PARTNER')
ON CONFLICT DO NOTHING;

-- ---- Services: how long a job takes (blocks the provider's calendar) ------------------------------------------
ALTER TABLE service_items ADD COLUMN duration_minutes INT NOT NULL DEFAULT 60 CHECK (duration_minutes BETWEEN 15 AND 720);
UPDATE service_items si SET duration_minutes = 120
FROM subcategories s WHERE s.id = si.subcategory_id AND s.code IN ('PAINTING', 'DEEP_CLEANING', 'CARPENTRY', 'PEST_CONTROL');

-- ---- Requests: customer picked the provider and the slot ----------------------------------------------------
ALTER TABLE service_requests DROP CONSTRAINT IF EXISTS service_requests_booking_mode_check;
ALTER TABLE service_requests ADD CONSTRAINT service_requests_booking_mode_check
    CHECK (booking_mode IN ('SELF', 'AGENT_ASSISTED', 'AGENT_DIRECT', 'DIRECT_QUOTE', 'PROVIDER_SELECTED'));
CREATE INDEX ix_requests_preferred_start ON service_requests(preferred_start) WHERE preferred_start IS NOT NULL;

-- ---- Completion evidence: providers attach before / after photos to the job ---------------------------------
ALTER TABLE request_attachments DROP CONSTRAINT IF EXISTS request_attachments_uploader_role_check;
ALTER TABLE request_attachments ADD CONSTRAINT request_attachments_uploader_role_check
    CHECK (uploader_role IN ('CUSTOMER', 'AGENT', 'ADMIN', 'PARTNER'));
ALTER TABLE request_attachments
    ADD COLUMN purpose VARCHAR(20) NOT NULL DEFAULT 'GENERAL' CHECK (purpose IN ('GENERAL', 'BEFORE', 'AFTER'));

INSERT INTO platform_settings(key, value, description) VALUES
 ('booking.slot_minutes', '60', 'Instant booking: slot grid step in minutes'),
 ('booking.day_start_hour', '8', 'Instant booking: first slot of the day (local time, 24 h)'),
 ('booking.day_end_hour', '20', 'Instant booking: no job may end after this hour (local time, 24 h)'),
 ('booking.days_ahead', '7', 'Instant booking: how many days ahead customers can book'),
 ('booking.min_lead_minutes', '90', 'Instant booking: earliest slot is at least this many minutes from now'),
 ('booking.travel_buffer_minutes', '30', 'Gap kept free between two jobs of the same provider'),
 ('booking.completion_photo_required', 'false', 'true: providers must add an AFTER photo before asking the customer to confirm completion'),
 ('platform.timezone', 'Asia/Kolkata', 'Time zone used for booking slots and provider calendars')
ON CONFLICT (key) DO NOTHING;
