# Runbook — failure scenarios & recovery

| Scenario | Detection | Automatic behaviour | Operator action |
|---|---|---|---|
| One frontend down / redeploying | Health page shows that app UNAVAILABLE | Other apps and backend unaffected (separate deploys) | Roll back that app only |
| Backend instance crash | Readiness probe fails | LB removes it; K8s restarts; PDB keeps ≥ 2 serving | None unless crash-looping → roll back |
| Availability-zone loss | Node/zone alerts | Pods rescheduled to other zones (topology spread); DB fails over to standby | Watch replication, confirm write traffic recovered |
| Database primary failure | DB health UNAVAILABLE / provider event | Managed failover promotes standby (typically 1–2 min); Hikari reconnects | Verify `pg_is_in_recovery() = false`, check error spike cleared |
| Data corruption / bad delete | Reconciliation or user report | — | PITR restore to new instance; surgical re-apply (backup runbook) |
| Outbox / notification provider outage | Outbox backlog age rising; dead-letters | Retries with exponential backoff; dead-letter after max attempts; business data unaffected | Fix provider; requeue: `UPDATE outbox_events SET status='PENDING', attempts=0, next_attempt_at=now() WHERE status='DEAD' AND ...` |
| Payment gateway outage | `PAYMENT_GATEWAY_ERROR` exception; failure rate | Payment marked FAILED, request stays PAYMENT_PENDING, customer can retry | Communicate; reconcile once gateway recovers |
| Duplicate / delayed webhooks | — | De-duplicated by `(provider, event_id)`; idempotent success handling | None |
| Regional cloud outage | All health red | — | Restore latest cross-region snapshot in DR region, repoint DNS; RTO target 30 min requires pre-provisioned DR infrastructure |

All recovery actions must be recorded with time, operator and outcome. Scenarios 12–15 of the acceptance criteria
require a provisioned cloud environment and have **not yet been exercised**.
