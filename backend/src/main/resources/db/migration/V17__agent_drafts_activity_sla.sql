-- Stage 7: agents & personal assistance across all business verticals.
--   agent_skills      : which verticals an agent is trained for (eligibility for dedicated allocation)
--   vertical_sla      : first-response time per vertical (60-120 minutes in the pilot), replacing the single per-priority value
--   agent_drafts      : RFQs, product orders and service bookings an agent prepares; only the customer can approve them
--   agent_activities  : the agent activity log (calls, notes, vendor follow-ups, drafts, escalations)

-- ---- Agent eligibility ----------------------------------------------------------------------------------------
ALTER TABLE agents ADD COLUMN accepts_dedicated BOOLEAN NOT NULL DEFAULT TRUE;
COMMENT ON COLUMN agents.accepts_dedicated IS 'FALSE: trainee / manager-only agents never get a dedicated portfolio (they can still take one-off and escalated requests)';

CREATE TABLE agent_skills (
    agent_id  UUID        NOT NULL REFERENCES agents(id),
    vertical  VARCHAR(30) NOT NULL REFERENCES platform_verticals(code),
    PRIMARY KEY (agent_id, vertical)
);
-- Existing agents keep handling everything until an admin narrows it down.
INSERT INTO agent_skills(agent_id, vertical) SELECT a.id, v.code FROM agents a CROSS JOIN platform_verticals v ON CONFLICT DO NOTHING;

-- ---- First-response SLA per vertical --------------------------------------------------------------------------
CREATE TABLE vertical_sla (
    vertical                VARCHAR(30) PRIMARY KEY REFERENCES platform_verticals(code),
    first_response_minutes  INT         NOT NULL CHECK (first_response_minutes BETWEEN 15 AND 480),
    updated_by              UUID,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO vertical_sla(vertical, first_response_minutes) VALUES
 ('SERVICES', 60), ('TASKS', 60), ('MARKETPLACE', 120), ('ASSISTANCE', 120)
ON CONFLICT DO NOTHING;

-- ---- Drafts that need the customer's approval -----------------------------------------------------------------
CREATE SEQUENCE seq_draft_number START 1;

CREATE TABLE agent_drafts (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    draft_number       VARCHAR(40)   NOT NULL UNIQUE,
    customer_user_id   UUID          NOT NULL REFERENCES users(id),
    agent_id           UUID          NOT NULL REFERENCES agents(id),
    created_by         UUID          NOT NULL REFERENCES users(id),
    -- RFQ: quotation request to shops; PURCHASE: a one-shop product cart; BOOKING: a service slot with a chosen professional.
    kind               VARCHAR(20)   NOT NULL CHECK (kind IN ('RFQ', 'PURCHASE', 'BOOKING')),
    title              VARCHAR(200)  NOT NULL,
    note               VARCHAR(1000),
    payload            JSONB         NOT NULL,
    -- What the customer is shown: one line per item / step, prepared when the draft is sent.
    preview            JSONB         NOT NULL DEFAULT '[]'::jsonb,
    estimated_total    NUMERIC(12,2),
    -- SENT: waiting for the customer. Only the customer's own action moves it to APPROVED or REJECTED.
    status             VARCHAR(20)   NOT NULL DEFAULT 'SENT' CHECK (status IN ('SENT', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    decision_note      VARCHAR(500),
    result_request_id  UUID REFERENCES service_requests(id),
    expires_at         TIMESTAMPTZ   NOT NULL,
    decided_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_drafts_customer ON agent_drafts(customer_user_id, status, created_at DESC);
CREATE INDEX ix_drafts_agent ON agent_drafts(agent_id, status, created_at DESC);

-- ---- Agent activity log -----------------------------------------------------------------------------------
CREATE TABLE agent_activities (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id          UUID         NOT NULL REFERENCES agents(id),
    customer_user_id  UUID REFERENCES users(id),
    request_id        UUID REFERENCES service_requests(id),
    partner_id        UUID REFERENCES partners(id),
    draft_id          UUID REFERENCES agent_drafts(id),
    kind              VARCHAR(30)  NOT NULL CHECK (kind IN ('CALL', 'MESSAGE', 'NOTE', 'VENDOR_FOLLOW_UP', 'REQUEST_CREATED', 'DRAFT_SENT',
                                                            'DRAFT_APPROVED', 'DRAFT_REJECTED', 'DRAFT_CANCELLED', 'ESCALATION_RECEIVED',
                                                            'COVERING_FOR_PRIMARY')),
    summary           VARCHAR(500) NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_agent_activities_agent ON agent_activities(agent_id, created_at DESC);
CREATE INDEX ix_agent_activities_customer ON agent_activities(customer_user_id, created_at DESC);
CREATE INDEX ix_agent_activities_request ON agent_activities(request_id, created_at DESC) WHERE request_id IS NOT NULL;

INSERT INTO platform_settings(key, value, description) VALUES
 ('drafts.valid_hours', '72', 'How long a draft prepared by an agent waits for the customer before it expires'),
 ('agent.vendor_follow_up_minutes', '120', 'Requests waiting for vendor quotes longer than this show up as vendor follow-ups on the agent dashboard')
ON CONFLICT (key) DO NOTHING;
