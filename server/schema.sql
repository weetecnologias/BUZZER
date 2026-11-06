-- BUZZER schema (SQLite). Mirrors the blueprint's database domains.
-- Money is stored as whole INR rupees. Timestamps are ISO-8601 UTC text.

-- ---------------------------------------------------------------- Identity
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  mobile TEXT UNIQUE,
  email TEXT UNIQUE,
  password_hash TEXT,
  status TEXT NOT NULL DEFAULT 'active',            -- active | suspended | deletion_requested
  language TEXT NOT NULL DEFAULT 'en',
  avatar_color TEXT,
  created_at TEXT NOT NULL,
  last_login_at TEXT
);
CREATE TABLE IF NOT EXISTS user_roles (
  user_id TEXT NOT NULL REFERENCES users(id),
  role TEXT NOT NULL,                               -- customer provider employer agent team_lead ops_admin finance_admin super_admin verification_reviewer
  PRIMARY KEY (user_id, role)
);
CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  portal TEXT NOT NULL,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  revoked INTEGER NOT NULL DEFAULT 0,
  ip TEXT
);
CREATE TABLE IF NOT EXISTS otps (
  id TEXT PRIMARY KEY,
  mobile TEXT NOT NULL,
  code_hash TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  used INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS addresses (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  label TEXT NOT NULL,
  line1 TEXT NOT NULL,
  locality TEXT NOT NULL,
  landmark TEXT,
  city TEXT NOT NULL DEFAULT 'Hyderabad',
  pincode TEXT,
  is_default INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS consents (
  user_id TEXT NOT NULL REFERENCES users(id),
  kind TEXT NOT NULL,                               -- terms privacy whatsapp promotional candidate_data_sharing provider_verification
  granted INTEGER NOT NULL,
  version TEXT NOT NULL DEFAULT '1.0',
  updated_at TEXT NOT NULL,
  PRIMARY KEY (user_id, kind)
);
CREATE TABLE IF NOT EXISTS customers (
  user_id TEXT PRIMARY KEY REFERENCES users(id),
  notification_prefs TEXT NOT NULL DEFAULT '{}',
  created_at TEXT NOT NULL
);

-- ------------------------------------------------- Customer & agent allocation
CREATE TABLE IF NOT EXISTS agents (
  user_id TEXT PRIMARY KEY REFERENCES users(id),
  display_name TEXT NOT NULL,
  team_lead_id TEXT,
  specialty TEXT,
  phone TEXT,
  is_pool INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS agent_workloads (
  agent_id TEXT PRIMARY KEY REFERENCES agents(user_id),
  active_requests INTEGER NOT NULL DEFAULT 0,
  capacity_limit INTEGER NOT NULL,
  availability_status TEXT NOT NULL DEFAULT 'available'   -- available | busy | away | offline
);
CREATE TABLE IF NOT EXISTS customer_agent_assignments (
  customer_id TEXT PRIMARY KEY REFERENCES users(id),
  primary_agent_id TEXT REFERENCES agents(user_id),
  backup_agent_id TEXT REFERENCES agents(user_id),
  status TEXT NOT NULL DEFAULT 'active',
  assigned_at TEXT NOT NULL,
  assigned_by TEXT,
  reason TEXT
);
CREATE TABLE IF NOT EXISTS assignment_history (
  id TEXT PRIMARY KEY,
  customer_id TEXT NOT NULL,
  request_id TEXT,
  from_agent_id TEXT,
  to_agent_id TEXT,
  reason TEXT,
  changed_by TEXT,
  changed_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS assignment_rules (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  rule_type TEXT NOT NULL,                          -- primary_default | new_customer | overload | absence | specialist
  description TEXT,
  config_json TEXT NOT NULL DEFAULT '{}',
  priority INTEGER NOT NULL DEFAULT 10,
  active INTEGER NOT NULL DEFAULT 1
);

-- ---------------------------------------------------------------- Catalogue
CREATE TABLE IF NOT EXISTS modules (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  description TEXT,
  sort INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS workflow_templates (
  id TEXT PRIMARY KEY,
  code TEXT UNIQUE NOT NULL,
  name TEXT NOT NULL,
  description TEXT,
  stages_json TEXT NOT NULL DEFAULT '[]'
);
CREATE TABLE IF NOT EXISTS workflow_steps (
  id TEXT PRIMARY KEY,
  template_id TEXT NOT NULL REFERENCES workflow_templates(id),
  seq INTEGER NOT NULL,
  status_key TEXT NOT NULL,
  label TEXT NOT NULL,
  sla_hours INTEGER NOT NULL DEFAULT 24,
  approval_gate INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS categories (
  id TEXT PRIMARY KEY,
  slug TEXT UNIQUE NOT NULL,
  name TEXT NOT NULL,
  module_id TEXT REFERENCES modules(id),
  description TEXT,
  icon TEXT,
  color TEXT,
  phase INTEGER NOT NULL DEFAULT 1,
  is_active INTEGER NOT NULL DEFAULT 0,
  workflow_template_id TEXT REFERENCES workflow_templates(id),
  schema_json TEXT NOT NULL DEFAULT '[]',
  includes_json TEXT NOT NULL DEFAULT '[]',
  how_json TEXT NOT NULL DEFAULT '[]',
  prepare_json TEXT NOT NULL DEFAULT '[]',
  keywords TEXT NOT NULL DEFAULT '',
  sort INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS subcategories (
  id TEXT PRIMARY KEY,
  category_id TEXT NOT NULL REFERENCES categories(id),
  name TEXT NOT NULL,
  keywords TEXT NOT NULL DEFAULT '',
  sort INTEGER NOT NULL DEFAULT 0
);

-- --------------------------------------------------------- Requests & workflow
CREATE TABLE IF NOT EXISTS requests (
  id TEXT PRIMARY KEY,
  reference TEXT UNIQUE NOT NULL,
  customer_id TEXT NOT NULL REFERENCES users(id),
  assigned_agent_id TEXT REFERENCES agents(user_id),
  category_id TEXT NOT NULL REFERENCES categories(id),
  subcategory_id TEXT REFERENCES subcategories(id),
  workflow_template_id TEXT REFERENCES workflow_templates(id),
  status TEXT NOT NULL,
  priority TEXT NOT NULL DEFAULT 'normal',
  service_address_id TEXT REFERENCES addresses(id),
  locality TEXT,
  preferred_at TEXT,
  budget_min INTEGER,
  budget_max INTEGER,
  title TEXT NOT NULL,
  description TEXT NOT NULL,
  fingerprint TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  status_changed_at TEXT NOT NULL,
  first_response_at TEXT,
  cancel_reason TEXT,
  closed_at TEXT
);
CREATE INDEX IF NOT EXISTS ix_requests_customer ON requests(customer_id);
CREATE INDEX IF NOT EXISTS ix_requests_agent ON requests(assigned_agent_id, status);
CREATE TABLE IF NOT EXISTS request_details (
  id TEXT PRIMARY KEY,
  request_id TEXT NOT NULL REFERENCES requests(id),
  field_key TEXT NOT NULL,
  field_label TEXT NOT NULL,
  value TEXT,
  UNIQUE (request_id, field_key)
);
CREATE TABLE IF NOT EXISTS request_status_history (
  id TEXT PRIMARY KEY,
  request_id TEXT NOT NULL REFERENCES requests(id),
  from_status TEXT,
  to_status TEXT NOT NULL,
  changed_by TEXT,
  reason TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_history_request ON request_status_history(request_id);
CREATE TABLE IF NOT EXISTS request_notes (
  id TEXT PRIMARY KEY,
  request_id TEXT NOT NULL REFERENCES requests(id),
  author_id TEXT NOT NULL,
  body TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS request_invites (
  id TEXT PRIMARY KEY,
  request_id TEXT NOT NULL REFERENCES requests(id),
  provider_id TEXT NOT NULL,
  invited_by TEXT,
  status TEXT NOT NULL DEFAULT 'invited',           -- invited | declined | quoted
  decline_reason TEXT,
  created_at TEXT NOT NULL,
  responded_at TEXT,
  UNIQUE (request_id, provider_id)
);
CREATE TABLE IF NOT EXISTS event_briefs (
  request_id TEXT PRIMARY KEY REFERENCES requests(id),
  kind TEXT NOT NULL,                               -- wedding | photography
  event_type TEXT,
  event_date TEXT,
  venue TEXT,
  locality TEXT,
  guest_count INTEGER,
  budget_min INTEGER,
  budget_max INTEGER,
  services TEXT,
  coverage_hours INTEGER,
  coverage_style TEXT,
  deliverables TEXT,
  package_pref TEXT,
  notes TEXT
);
CREATE TABLE IF NOT EXISTS event_milestones (
  id TEXT PRIMARY KEY,
  request_id TEXT NOT NULL REFERENCES requests(id),
  title TEXT NOT NULL,
  due_date TEXT,
  status TEXT NOT NULL DEFAULT 'pending',           -- pending | in_progress | done
  provider_id TEXT,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS contact_log (
  id TEXT PRIMARY KEY,
  request_id TEXT,
  agent_id TEXT NOT NULL,
  party_type TEXT NOT NULL,                         -- customer | provider
  party_id TEXT,
  party_name TEXT,
  channel TEXT NOT NULL,                            -- call | whatsapp | sms | email
  direction TEXT NOT NULL DEFAULT 'outbound',
  outcome TEXT,
  notes TEXT,
  created_at TEXT NOT NULL
);

-- ----------------------------------------------------------- Provider network
CREATE TABLE IF NOT EXISTS providers (
  id TEXT PRIMARY KEY,
  user_id TEXT REFERENCES users(id),
  business_name TEXT NOT NULL,
  type TEXT NOT NULL DEFAULT 'business',            -- individual | business
  kind TEXT NOT NULL DEFAULT 'general',             -- general | catering | photography
  description TEXT,
  phone TEXT,
  email TEXT,
  address TEXT,
  locality TEXT,
  verification_status TEXT NOT NULL DEFAULT 'unverified',  -- unverified | pending | verified | rejected
  status TEXT NOT NULL DEFAULT 'active',            -- active | suspended
  rating_avg REAL NOT NULL DEFAULT 0,
  rating_count INTEGER NOT NULL DEFAULT 0,
  jobs_completed INTEGER NOT NULL DEFAULT 0,
  response_rate INTEGER NOT NULL DEFAULT 0,
  commission_rate REAL,
  upi_id TEXT,
  bank_masked TEXT,
  source TEXT NOT NULL DEFAULT 'internal_directory',
  logo_color TEXT,
  max_jobs_per_day INTEGER NOT NULL DEFAULT 3,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS provider_services (
  id TEXT PRIMARY KEY,
  provider_id TEXT NOT NULL REFERENCES providers(id),
  category_id TEXT NOT NULL REFERENCES categories(id),
  name TEXT NOT NULL,
  base_price INTEGER NOT NULL DEFAULT 0,
  unit TEXT NOT NULL DEFAULT 'per job',
  description TEXT,
  active INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS provider_areas (
  id TEXT PRIMARY KEY,
  provider_id TEXT NOT NULL REFERENCES providers(id),
  locality TEXT NOT NULL,
  UNIQUE (provider_id, locality)
);
CREATE TABLE IF NOT EXISTS provider_documents (
  id TEXT PRIMARY KEY,
  provider_id TEXT NOT NULL REFERENCES providers(id),
  doc_type TEXT NOT NULL,                           -- id_proof address_proof gst business_photos licence
  doc_number TEXT,
  attachment_id TEXT,
  status TEXT NOT NULL DEFAULT 'pending',           -- pending | verified | rejected | expired
  expires_at TEXT,
  reviewed_by TEXT,
  reviewed_at TEXT,
  review_note TEXT,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS provider_availability (
  id TEXT PRIMARY KEY,
  provider_id TEXT NOT NULL REFERENCES providers(id),
  weekday INTEGER NOT NULL,                         -- 0 = Monday
  start_time TEXT NOT NULL,
  end_time TEXT NOT NULL,
  active INTEGER NOT NULL DEFAULT 1,
  UNIQUE (provider_id, weekday)
);
CREATE TABLE IF NOT EXISTS provider_blocked_dates (
  id TEXT PRIMARY KEY,
  provider_id TEXT NOT NULL REFERENCES providers(id),
  date TEXT NOT NULL,
  reason TEXT,
  UNIQUE (provider_id, date)
);
CREATE TABLE IF NOT EXISTS provider_packages (
  id TEXT PRIMARY KEY,
  provider_id TEXT NOT NULL REFERENCES providers(id),
  kind TEXT NOT NULL,                               -- catering | photography | other
  name TEXT NOT NULL,
  price INTEGER NOT NULL DEFAULT 0,
  details_json TEXT NOT NULL DEFAULT '{}',
  active INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS portfolio_items (
  id TEXT PRIMARY KEY,
  provider_id TEXT NOT NULL REFERENCES providers(id),
  title TEXT NOT NULL,
  caption TEXT,
  color TEXT,
  created_at TEXT NOT NULL
);

-- ----------------------------------------------------- Quotes & bookings
CREATE TABLE IF NOT EXISTS quotations (
  id TEXT PRIMARY KEY,
  request_id TEXT NOT NULL REFERENCES requests(id),
  provider_id TEXT NOT NULL REFERENCES providers(id),
  submitted_by TEXT,
  edited_by TEXT,
  status TEXT NOT NULL,                             -- submitted | shared | approved | rejected | clarification_requested | revised_pending_consent | not_selected | withdrawn | expired
  version INTEGER NOT NULL DEFAULT 1,
  total INTEGER NOT NULL,
  scope TEXT,
  exclusions TEXT,
  terms TEXT,
  warranty_days INTEGER NOT NULL DEFAULT 0,
  deposit_pct INTEGER NOT NULL DEFAULT 0,
  valid_until TEXT,
  est_start_at TEXT,
  est_duration TEXT,
  clarification_note TEXT,
  shared_at TEXT,
  shared_by TEXT,
  decided_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_quotes_request ON quotations(request_id);
CREATE TABLE IF NOT EXISTS quotation_items (
  id TEXT PRIMARY KEY,
  quotation_id TEXT NOT NULL REFERENCES quotations(id),
  description TEXT NOT NULL,
  qty INTEGER NOT NULL DEFAULT 1,
  unit_price INTEGER NOT NULL DEFAULT 0,
  amount INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS customer_approvals (
  id TEXT PRIMARY KEY,
  request_id TEXT NOT NULL,
  quotation_id TEXT NOT NULL,
  customer_id TEXT NOT NULL,
  decision TEXT NOT NULL,                           -- approved | rejected | clarification
  note TEXT,
  consent_text TEXT,
  ip TEXT,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS bookings (
  id TEXT PRIMARY KEY,
  reference TEXT UNIQUE NOT NULL,
  request_id TEXT NOT NULL REFERENCES requests(id),
  quotation_id TEXT NOT NULL REFERENCES quotations(id),
  provider_id TEXT NOT NULL REFERENCES providers(id),
  customer_id TEXT NOT NULL REFERENCES users(id),
  agent_id TEXT,
  status TEXT NOT NULL,                             -- confirmed | on_the_way | in_progress | work_done | completed | cancelled | pending_reconsent | disputed
  scheduled_at TEXT,
  amount INTEGER NOT NULL,
  deposit_amount INTEGER NOT NULL DEFAULT 0,
  paid_amount INTEGER NOT NULL DEFAULT 0,
  payment_status TEXT NOT NULL DEFAULT 'unpaid',    -- unpaid | deposit_paid | paid | refunded
  cancel_reason TEXT,
  refund_eligible INTEGER NOT NULL DEFAULT 0,
  completion_note TEXT,
  evidence_attachment_id TEXT,
  completed_at TEXT,
  customer_confirmed_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
-- Duplicate-booking guard: one live booking per request.
CREATE UNIQUE INDEX IF NOT EXISTS ux_one_live_booking ON bookings(request_id) WHERE status <> 'cancelled';
CREATE TABLE IF NOT EXISTS booking_events (
  id TEXT PRIMARY KEY,
  booking_id TEXT NOT NULL REFERENCES bookings(id),
  event TEXT NOT NULL,
  detail TEXT,
  actor_id TEXT,
  created_at TEXT NOT NULL
);

-- ---------------------------------------------------------------- Money
CREATE TABLE IF NOT EXISTS payment_transactions (
  id TEXT PRIMARY KEY,
  reference TEXT UNIQUE NOT NULL,
  booking_id TEXT,
  user_id TEXT NOT NULL,
  kind TEXT NOT NULL,                               -- customer_payment | membership | refund | payout
  method TEXT,
  amount INTEGER NOT NULL,
  status TEXT NOT NULL,                             -- initiated | success | failed | refunded
  gateway_ref TEXT,
  memo TEXT,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS refunds (
  id TEXT PRIMARY KEY,
  booking_id TEXT NOT NULL,
  requested_by TEXT,
  amount INTEGER NOT NULL,
  reason TEXT,
  status TEXT NOT NULL DEFAULT 'requested',         -- requested | approved | rejected | processed
  decided_by TEXT,
  decided_at TEXT,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS commission_rules (
  version INTEGER PRIMARY KEY,
  rate REAL NOT NULL,
  note TEXT,
  effective_from TEXT NOT NULL,
  created_by TEXT
);
CREATE TABLE IF NOT EXISTS commissions (
  id TEXT PRIMARY KEY,
  booking_id TEXT NOT NULL,
  provider_id TEXT NOT NULL,
  rate REAL NOT NULL,
  rule_version INTEGER,
  base_amount INTEGER NOT NULL,
  commission_amount INTEGER NOT NULL,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS payouts (
  id TEXT PRIMARY KEY,
  provider_id TEXT NOT NULL,
  amount INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'requested',         -- requested | paid | rejected
  reference TEXT,
  requested_at TEXT NOT NULL,
  paid_at TEXT,
  decided_by TEXT
);
-- Immutable: application code only ever INSERTs into ledger_entries.
CREATE TABLE IF NOT EXISTS ledger_entries (
  id TEXT PRIMARY KEY,
  booking_id TEXT,
  provider_id TEXT,
  account TEXT NOT NULL,                            -- escrow | provider_payable | buzzer_commission | membership_revenue
  debit INTEGER NOT NULL DEFAULT 0,
  credit INTEGER NOT NULL DEFAULT 0,
  memo TEXT,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS plans (
  id TEXT PRIMARY KEY,
  code TEXT UNIQUE NOT NULL,
  name TEXT NOT NULL,
  audience TEXT NOT NULL DEFAULT 'customer',
  price_month INTEGER NOT NULL DEFAULT 0,
  price_year INTEGER NOT NULL DEFAULT 0,
  benefits_json TEXT NOT NULL DEFAULT '[]',
  active INTEGER NOT NULL DEFAULT 1,
  sort INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS memberships (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  plan_id TEXT NOT NULL REFERENCES plans(id),
  interval TEXT NOT NULL DEFAULT 'month',
  status TEXT NOT NULL DEFAULT 'active',            -- active | paused | cancelled
  started_at TEXT NOT NULL,
  renewal_at TEXT,
  paused_at TEXT,
  cancelled_at TEXT
);

-- ------------------------------------------------------------------ Jobs
CREATE TABLE IF NOT EXISTS employers (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  company_name TEXT NOT NULL,
  industry TEXT,
  locality TEXT,
  contact_name TEXT,
  phone TEXT,
  verification_status TEXT NOT NULL DEFAULT 'pending',   -- pending | verified | rejected | blocked
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS candidates (
  id TEXT PRIMARY KEY,
  user_id TEXT UNIQUE NOT NULL REFERENCES users(id),
  headline TEXT,
  skills TEXT,
  experience_years INTEGER NOT NULL DEFAULT 0,
  preferred_localities TEXT,
  expected_salary INTEGER,
  shift_pref TEXT,
  data_sharing_consent INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS vacancies (
  id TEXT PRIMARY KEY,
  reference TEXT UNIQUE NOT NULL,
  employer_id TEXT NOT NULL REFERENCES employers(id),
  title TEXT NOT NULL,
  role_type TEXT NOT NULL,
  description TEXT,
  locality TEXT NOT NULL,
  salary_min INTEGER NOT NULL,
  salary_max INTEGER NOT NULL,
  pay_frequency TEXT NOT NULL DEFAULT 'month',
  employment_type TEXT NOT NULL DEFAULT 'full_time',
  shift TEXT,
  working_days TEXT,
  weekly_off TEXT,
  experience TEXT,
  skills TEXT,
  deadline TEXT,
  openings INTEGER NOT NULL DEFAULT 1,
  recruitment_fee_declared INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL,                             -- pending_verification | published | paused | closed | rejected | filled
  verified_by TEXT,
  verified_at TEXT,
  rejection_reason TEXT,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS job_applications (
  id TEXT PRIMARY KEY,
  vacancy_id TEXT NOT NULL REFERENCES vacancies(id),
  candidate_id TEXT NOT NULL REFERENCES candidates(id),
  status TEXT NOT NULL,                             -- applied | shortlisted | interview | hired | rejected | withdrawn
  consent_shared INTEGER NOT NULL DEFAULT 0,
  hiring_outcome TEXT,
  applied_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE (vacancy_id, candidate_id)
);
CREATE TABLE IF NOT EXISTS saved_jobs (
  candidate_id TEXT NOT NULL,
  vacancy_id TEXT NOT NULL,
  created_at TEXT NOT NULL,
  PRIMARY KEY (candidate_id, vacancy_id)
);
CREATE TABLE IF NOT EXISTS interviews (
  id TEXT PRIMARY KEY,
  application_id TEXT NOT NULL REFERENCES job_applications(id),
  scheduled_at TEXT NOT NULL,
  mode TEXT NOT NULL DEFAULT 'in_person',
  location TEXT,
  status TEXT NOT NULL DEFAULT 'scheduled',         -- scheduled | completed | cancelled | no_show
  notes TEXT
);
CREATE TABLE IF NOT EXISTS job_reports (
  id TEXT PRIMARY KEY,
  vacancy_id TEXT NOT NULL,
  reporter_id TEXT NOT NULL,
  reason TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'open',              -- open | actioned | dismissed
  created_at TEXT NOT NULL
);

-- ------------------------------------------------- Communication & trust
CREATE TABLE IF NOT EXISTS attachments (
  id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  filename TEXT NOT NULL,
  mime TEXT NOT NULL,
  size INTEGER NOT NULL,
  path TEXT NOT NULL,
  request_id TEXT,
  complaint_id TEXT,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS conversations (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,                               -- customer_agent | provider_agent
  customer_id TEXT,
  provider_id TEXT,
  agent_id TEXT,
  request_id TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS messages (
  id TEXT PRIMARY KEY,
  conversation_id TEXT NOT NULL REFERENCES conversations(id),
  sender_id TEXT NOT NULL,
  body TEXT NOT NULL,
  attachment_id TEXT,
  request_id TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_messages_conv ON messages(conversation_id, created_at);
CREATE TABLE IF NOT EXISTS notifications (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  type TEXT NOT NULL,
  event TEXT,
  title TEXT NOT NULL,
  body TEXT,
  link TEXT,
  channel TEXT NOT NULL DEFAULT 'in_app',
  delivery_status TEXT NOT NULL DEFAULT 'delivered',  -- queued | delivered | failed | simulated
  is_read INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_notifications_user ON notifications(user_id, created_at);
CREATE TABLE IF NOT EXISTS message_templates (
  id TEXT PRIMARY KEY,
  code TEXT UNIQUE NOT NULL,
  name TEXT NOT NULL,
  channel TEXT NOT NULL DEFAULT 'in_app',
  audience TEXT NOT NULL DEFAULT 'customer',
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  active INTEGER NOT NULL DEFAULT 1,
  updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS tasks (
  id TEXT PRIMARY KEY,
  agent_id TEXT NOT NULL,
  request_id TEXT,
  customer_id TEXT,
  title TEXT NOT NULL,
  due_at TEXT,
  priority TEXT NOT NULL DEFAULT 'normal',
  status TEXT NOT NULL DEFAULT 'open',              -- open | done
  created_at TEXT NOT NULL,
  completed_at TEXT
);
CREATE TABLE IF NOT EXISTS reviews (
  id TEXT PRIMARY KEY,
  booking_id TEXT,
  request_id TEXT,
  reviewer_id TEXT NOT NULL,
  subject_type TEXT NOT NULL,                       -- provider | agent
  subject_id TEXT NOT NULL,
  rating INTEGER NOT NULL,
  comment TEXT,
  reply TEXT,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS complaints (
  id TEXT PRIMARY KEY,
  reference TEXT UNIQUE NOT NULL,
  kind TEXT NOT NULL DEFAULT 'complaint',           -- complaint | dispute | partner_issue
  request_id TEXT,
  booking_id TEXT,
  raised_by TEXT NOT NULL,
  raised_by_role TEXT NOT NULL,
  against_type TEXT,
  against_id TEXT,
  topic TEXT NOT NULL,
  description TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'open',              -- open | investigating | escalated | resolved | closed
  priority TEXT NOT NULL DEFAULT 'normal',
  owner_id TEXT,
  sla_due_at TEXT,
  resolution TEXT,
  created_at TEXT NOT NULL,
  resolved_at TEXT
);
CREATE TABLE IF NOT EXISTS complaint_events (
  id TEXT PRIMARY KEY,
  complaint_id TEXT NOT NULL REFERENCES complaints(id),
  actor_id TEXT,
  event TEXT NOT NULL,
  note TEXT,
  created_at TEXT NOT NULL
);

-- ----------------------------------------------------------- Platform
CREATE TABLE IF NOT EXISTS audit_logs (
  id TEXT PRIMARY KEY,
  actor_id TEXT,
  actor_role TEXT,
  action TEXT NOT NULL,
  entity_type TEXT,
  entity_id TEXT,
  detail TEXT,
  ip TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_audit_created ON audit_logs(created_at);
CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  label TEXT,
  group_name TEXT NOT NULL DEFAULT 'general',
  updated_by TEXT,
  updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS integrations (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  kind TEXT NOT NULL,
  status TEXT NOT NULL,                             -- sandbox | connected | degraded | disconnected
  notes TEXT,
  last_checked_at TEXT
);
CREATE TABLE IF NOT EXISTS help_articles (
  id TEXT PRIMARY KEY,
  slug TEXT UNIQUE NOT NULL,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  audience TEXT NOT NULL DEFAULT 'customer',
  topic TEXT NOT NULL DEFAULT 'general',
  published INTEGER NOT NULL DEFAULT 1,
  updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS counters (
  name TEXT PRIMARY KEY,
  value INTEGER NOT NULL DEFAULT 0
);
