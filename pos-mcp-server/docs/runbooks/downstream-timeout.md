## Purpose

Runbook for downstream service timeouts encountered during NLTI execution.

## Symptoms

- NLTI requests hang or fail with timeout errors
- `nlt.execution.latency_ms` and `nlt.request.latency_ms` increase
- Traces show long wait on downstream HTTP/gRPC calls

## Detection

- Alert: `NLTIPlanningOrExecutionLatencyAnomaly` or `HighNLTIRequestLatency` firing
- Traces in distributed tracing showing downstream spans with high duration
- Metrics: increased 5xx or timeout counters on downstream clients

## Immediate Actions

1. Identify affected downstream service(s) via traces and logs.
2. Reduce client timeout thresholds temporarily to fail-fast if appropriate.
3. Retry with exponential backoff where safe; mark idempotent operations only.
4. If circuit breaker exists, evaluate opening/closing thresholds and adjust cautiously.

## Escalation

- Escalate to downstream service owner and SRE if timeouts persist > 5 minutes.
- Share trace IDs and request samples.

## Rollback / Recovery

- If a recent deploy to downstream service caused regression, coordinate rollback.
- If adding a mitigation in pos-mcp-server, deploy small patch with guarded feature flag.

## Post-Incident Notes

- Document root cause, whether retries helped, any changes to timeout or circuit breaker defaults.
- Add dashboards/alerts or synthetic tests to detect earlier.
