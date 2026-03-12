# NLTI Alert Rules

This file defines NLTI alert conditions and links to runbooks.

1. Name: HighNLTIErrorRate
   - Trigger: Spike in `nlt.error.count` over a 5 minute window compared to baseline (e.g., >5x baseline or absolute threshold)
   - Severity: P1
   - Runbook: pos-mcp-server/docs/runbooks/authz-outage.md (placeholder)

2. Name: HighNLTIRequestLatency
   - Trigger: `nlt.request.latency_ms` p99 exceeds configured SLA (example: 2000 ms) for 5 minutes
   - Severity: P2
   - Runbook: pos-mcp-server/docs/runbooks/downstream-timeout.md (placeholder)

3. Name: AuditWriteFailuresDetected
   - Trigger: `nlt.audit.write_failures` > 0 for any 5 minute window
   - Severity: P1
   - Runbook: pos-mcp-server/docs/runbooks/audit-storage-failure.md (placeholder)

4. Name: NLTIPlanningOrExecutionLatencyAnomaly
   - Trigger: `nlt.planning.latency_ms` OR `nlt.execution.latency_ms` exceed stage thresholds (e.g., p95 > 1000ms) for 3 minutes
   - Severity: P2
   - Runbook: pos-mcp-server/docs/runbooks/planning-failure.md (placeholder)

## Implementation Notes

- Flesh out precise thresholds and alerting expressions in monitoring tooling (Prometheus/Grafana/Cloud Monitoring).
- Connect each alert to on-call rotation and notification channels.
