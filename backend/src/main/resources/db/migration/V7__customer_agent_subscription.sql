-- Dedicated-agent subscription: customers choose "Subscribe for a dedicated agent" (matched on language & preferences)
-- or "Explore" the app first. Explore customers' requests are handled by the on-duty agent for that request.
ALTER TABLE customer_profiles
    ADD COLUMN agent_plan        VARCHAR(20) CHECK (agent_plan IN ('DEDICATED', 'EXPLORE')),
    ADD COLUMN agent_preferences JSONB,
    ADD COLUMN onboarded_at      TIMESTAMPTZ,
    ADD COLUMN subscribed_at     TIMESTAMPTZ;

-- Customers who already have a personal agent keep it and skip the welcome screen.
UPDATE customer_profiles cp
SET agent_plan = 'DEDICATED', onboarded_at = now(), subscribed_at = now()
WHERE EXISTS (SELECT 1 FROM customer_agent_links l WHERE l.customer_user_id = cp.user_id AND l.link_role = 'PRIMARY' AND l.active);

CREATE INDEX idx_customer_profiles_plan ON customer_profiles(agent_plan);

-- Who is handling a request: the customer's dedicated agent, or the on-duty agent (explore customers).
ALTER TABLE service_requests
    ADD COLUMN agent_source VARCHAR(20) NOT NULL DEFAULT 'DEDICATED' CHECK (agent_source IN ('DEDICATED', 'ON_DUTY'));
