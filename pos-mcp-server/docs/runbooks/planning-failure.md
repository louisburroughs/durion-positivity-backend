## Purpose

Runbook for NLTI planning engine failures or cases where the planner returns an empty plan.

## Symptoms

- `nlt.planning.latency_ms` spikes or planner returns errors
- Increased `nlt.error.count` with planning-stage tags
- Responses indicating inability to generate plan or prompts for clarification repeatedly

## Detection

- Alert: `NLTIPlanningOrExecutionLatencyAnomaly` or `HighNLTIErrorRate` with planning stage
- Planner logs showing exceptions, model rate limits, or invalid input errors
- Telemetry: `nlt.intent.clarification.count` abnormal rise

## Immediate Actions

1. Capture planner logs, request payloads (redact PII) and model/service errors.
2. If model provider is degraded, switch to fallback model provider if configured.
3. If input parsing produced malformed intent, trigger intent clarification path rather than failing.
4. Restart planner service/component if it's a transient internal error.

## Escalation

- Escalate to ML/Planner service owner if planner service is unavailable or model provider reports outage.
- Provide payload samples, timestamps, and correlation IDs.

## Rollback / Recovery

- If a recent change to planner code caused regressions, roll back to last known-good version.
- Re-run any queued planning requests after recovery, ensuring idempotency.

## Post-Incident Notes

- Document root cause (model rate-limit, parsing bug, bad prompts), and any prompt/pipeline fixes.
- Add unit/integration tests for malformed inputs and fallback provider behavior.
