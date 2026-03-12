## Purpose

Runbook for handling audit ledger write failures detected by the `nlt.audit.write_failures` metric.

## Symptoms

- Spike in `nlt.audit.write_failures` metric
- Errors in logs indicating DB write errors, constraint violations, or storage unavailability
- NLTI operations may succeed but audit records are missing

## Detection

- Alert: `AuditWriteFailuresDetected` firing
- Application logs containing `AuditRepository` or `audit write` errors
- DB monitoring shows slow queries, locks, or connection pool exhaustion

## Immediate Actions

1. Stop any high-volume replays or batch jobs that may be saturating DB.
2. Check DB connectivity, connection pool, and recent schema migrations.
3. If transient, restart the audit writer component or connection pool gracefully.
4. If dead-letter queue exists, ensure failed audit entries are being queued for retry.

## Escalation

- Escalate to Database and Storage SRE teams if failures persist > 10 minutes.
- Provide sample failing events and exception stack traces.

## Rollback / Recovery

- If a migration caused the issue, coordinate rollback of migration or schema fix.
- Run repair jobs to re-ingest missing audit entries from queued/durable sources when safe.

## Post-Incident Notes

- Record the number of lost vs recovered audit entries, root cause, and permanent fixes.
- Consider increasing replication/retention or adding write-retry/backoff for audit writes.
