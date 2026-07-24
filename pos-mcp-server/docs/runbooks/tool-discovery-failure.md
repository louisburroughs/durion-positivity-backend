## Purpose

Runbook for failures in OpenAPI-driven MCP tool discovery/registration (#645): the MCP server ends up
with too few (or zero) discovered gateway tools, so the assistant cannot call downstream operations.

## Background

Discovery runs at startup and, when `mcp.server.discovery-refresh.enabled=true`, on a fixed delay.
Order of resolution (`ToolRegistrationServiceImpl.registerDiscoveredTools`):

1. **Gateway aggregate spec** — fetch `mcp.server.aggregate-spec-url` (e.g. `/v3/api-docs`), map
   allow-listed paths to tools, register them.
2. **Per-service Eureka fallback** — only if the aggregate is unavailable/empty or matches no tools.
   Discovers each candidate service's own `/v3/api-docs` via the registry
   (`OpenApiDocumentFetcher.fetchForService`) and registers those. Candidates are
   `mcp.server.included-services` when set, otherwise every registered service except the gateway.

Both stages are fail-soft: a single unreachable service or a bad spec is logged and skipped, never
aborting the batch.

## Symptoms

- Assistant reports it cannot perform gateway-backed actions; only static facade tools work.
- `tools_registered_total` and `tools_discovered_total` are cumulative counters — read them as
  *change over time*, not absolute values. Trouble looks like: the counter never left zero
  (`max_over_time(tools_registered_total[15m]) == 0`), or a discovery run added little/nothing
  (`increase(tools_registered_total[15m])` is zero or far below a prior window), or discovered
  outran registered (`increase(tools_discovered_total[…]) > increase(tools_registered_total[…])`).
- Logs show `Aggregate OpenAPI spec unavailable`, `No MCP tools matched the configured allowlist`,
  `falling back to per-service Eureka discovery`, or `Per-service Eureka fallback registered no tools`.

## Detection

- Alerts: `McpToolDiscoveryProducedNoTools` (P1), `McpToolRegistrationLaggingDiscovery` (P2),
  `McpToolRegistrationDroppedSharply` (P2), `McpPerServiceFallbackEngaged` (P3).
- Metrics: `tools_discovered_total`, `tools_registered_total`.
- Logs in `ToolRegistrationServiceImpl` and `OpenApiDocumentFetcher`.

## Immediate Actions

1. Confirm the gateway is up and serving its aggregate spec:
   `curl -s $GATEWAY/v3/api-docs | head` (and `/v3/api-docs/swagger-config` for the springdoc index).
   - If the aggregate is down but services are healthy, the per-service fallback should have engaged —
     verify it did (log line above) and that `tools_registered_total` recovered.
2. Check Eureka: are the expected services registered? (`$EUREKA/eureka/apps` or the dashboard.)
   An empty registry starves the fallback too.
3. Verify config on the MCP server: `mcp.server.aggregate-spec-url`,
   `mcp.server.included-path-prefixes` (allowlist — too narrow ⇒ zero matches),
   `mcp.server.excluded-path-fragments`, and `mcp.server.included-services` (fallback candidates).
4. If discovery-refresh is enabled, trigger/await the next cycle; otherwise a rolling restart re-runs
   startup discovery once the upstreams are healthy.

## Escalation

- Persisting >10 min after the gateway/registry are confirmed healthy: escalate to the platform/SRE
  owner with `tools_discovered_total` / `tools_registered_total` samples and the discovery log excerpt.
- Share the failing spec URL and one failing per-service doc URL.

## Rollback / Recovery

- If a recent gateway or service OpenAPI change regressed the spec (unparseable, wrong paths), roll it
  back or fix the spec; discovery recovers on the next cycle/restart.
- The periodic refresh and the fallback are both fail-soft and inert-by-default, so no MCP-server code
  rollback is normally required.

## Post-Incident Notes

- Record root cause (aggregate outage vs. spec regression vs. registry gap), whether the fallback
  engaged, and time-to-recovery.
- Tune alert grace periods/thresholds and, if the fallback carried load, confirm `included-services`
  is scoped correctly for that environment.
