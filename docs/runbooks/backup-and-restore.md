# Runbook — backup & restore

## Backups (production)

1. Managed PostgreSQL automated snapshots: daily, 35-day retention, encrypted with a customer-managed key.
2. Continuous WAL archiving for point-in-time recovery (RPO target ≤ 5 min).
3. Snapshot copies to a separate backup account/region with object lock (protects against accidental deletion,
   compromised credentials and corruption — a standby replica would replicate those too).

## Restore drill (monthly, and before every major release)

1. Pick a random target time within the last 7 days.
2. Restore a **new** instance from PITR at that time (never over production).
3. Run the backend against it in an isolated namespace with `SPRING_PROFILES_ACTIVE` unset and providers in test mode.
4. Verify: Flyway reports the expected version; row counts of `service_requests`, `payments`, `ledger_entries` are
   plausible for the target time; `SELECT sum(amount) ... FROM ledger_entries GROUP BY account` reconciles with the
   finance report for that date.
5. Record duration (RTO evidence), data age (RPO evidence) and the operator in the log below.
6. Destroy the restored instance.

## Logical restore of a single table/request (corruption or bad admin action)

1. PITR-restore to a temporary instance just before the incident.
2. `pg_dump --data-only --table=<table> --where`-style export of the affected rows.
3. Re-apply through an audited script reviewed by two people; record an `audit_events` entry.

## Drill log

| Date | Operator | Target time | Restore duration | Data age | Result |
|---|---|---|---|---|---|
| — | — | — | — | — | Not yet performed (no cloud environment provisioned) |
