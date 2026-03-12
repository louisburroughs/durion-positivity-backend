## NLTI Overview Dashboard

Summary: High-level dashboard for NLTI request health, latency, errors, and intent/clarification activity.

## Panels

- Panel: NLTI Request Count
  - Metric: `nlt.request.count`
  - Type: timeseries
  - Description: Total NLTI requests per minute, split by status code and route.

- Panel: NLTI Request Latency (p50/p95/p99)
  - Metric: `nlt.request.latency_ms`
  - Type: timeseries with p50/p95/p99
  - Description: End-to-end NLTI request latency in milliseconds.

- Panel: NLTI Planning Latency
  - Metric: `nlt.planning.latency_ms`
  - Type: timeseries
  - Description: Time spent in NLTI planning stage (ms) with percentile bands.

- Panel: NLTI Execution Latency
  - Metric: `nlt.execution.latency_ms`
  - Type: timeseries
  - Description: Time spent in NLTI execution stage (ms).

- Panel: NLTI Errors
  - Metric: `nlt.error.count`
  - Type: alerting/graph
  - Description: Error count for NLTI requests, grouped by error type and stage.

- Panel: Audit Write Failures
  - Metric: `nlt.audit.write_failures`
  - Type: single stat / timeseries
  - Description: Number of failed audit ledger writes over time.

- Panel: Intent Parse Attempts
  - Metric: `nlt.intent.parse.count`
  - Type: timeseries
  - Description: Number of intent parses attempted (useful to spot parsing regressions).

- Panel: Intent Clarification Count
  - Metric: `nlt.intent.clarification.count`
  - Type: timeseries
  - Description: Number of times the system required intent clarification from the user.

## Notes

- Use percentile aggregations where supported for latency panels.
- Add drilldowns to traces and request logs for any spike windows.
