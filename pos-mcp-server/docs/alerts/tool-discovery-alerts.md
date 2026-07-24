# Tool Discovery Alert Rules (#645)

Alert conditions for OpenAPI-driven MCP tool discovery and registration. The MCP server discovers
gateway operations at startup (and, when `mcp.server.discovery-refresh.enabled=true`, periodically)
and registers them as MCP tools. Two Micrometer counters back these alerts (Prometheus names in
parentheses):

- `tools.discovered` (`tools_discovered_total`) — operations matched to tools during a discovery run,
  from either the gateway aggregate spec or the per-service Eureka fallback.
- `tools.registered` (`tools_registered_total`) — tools successfully added to the MCP server.

Both are **cumulative counters** that only ever advance, incrementing during each discovery run
(startup + each refresh cycle). Their raw values are not comparable across time — alert on their
*change over a window* with `increase(...)`, never on the raw value or a raw difference (which stays
permanently true after the first-ever failure).

1. Name: McpToolDiscoveryProducedNoTools
   - Trigger: the server has been up past its discovery grace period but has registered no tools —
     `max_over_time(tools_registered_total[15m]) == 0` while the instance is `up`. (Comparing the raw
     value to zero is valid here: a counter that never left zero means nothing ever registered.)
     Indicates the gateway aggregate spec was unavailable/empty **and** the per-service Eureka fallback
     found no reachable service specs.
   - Severity: P1 (the assistant can call no discovered gateway tools)
   - Runbook: pos-mcp-server/docs/runbooks/tool-discovery-failure.md

2. Name: McpToolRegistrationLaggingDiscovery
   - Trigger: within a discovery run, more operations were discovered than were registered —
     `increase(tools_discovered_total[10m]) > increase(tools_registered_total[10m])`. Comparing
     per-window *increases* (not the raw cumulative difference) means the alert reflects the current
     run, not a single historical failure. Fires when matched operations repeatedly fail to register
     (e.g. `McpAsyncServer.addTool` errors). Only evaluates meaningfully in windows containing a
     discovery run (startup or, with `discovery-refresh.enabled=true`, a refresh cycle).
   - Severity: P2
   - Runbook: pos-mcp-server/docs/runbooks/tool-discovery-failure.md

3. Name: McpToolRegistrationDroppedSharply
   - Trigger: a refresh cycle registered far fewer tools than the previous cycle — comparing
     consecutive per-window increments:
     `increase(tools_registered_total[15m]) < 0.5 * increase(tools_registered_total[15m] offset 15m)`
     while the earlier window's increase was non-zero. Signals a partial gateway outage, a spec
     regression, or many services de-registering from Eureka. Requires `discovery-refresh.enabled=true`
     (otherwise there is only the one startup run to measure); tune the window to the refresh interval.
   - Severity: P2
   - Runbook: pos-mcp-server/docs/runbooks/tool-discovery-failure.md

4. Name: McpPerServiceFallbackEngaged
   - Trigger: log-based — the message `falling back to per-service Eureka discovery` appears at WARN/
     INFO. Not an outage on its own (the fallback is doing its job), but it means the gateway aggregate
     endpoint is not serving tools and should be investigated.
   - Severity: P3 (informational / investigate)
   - Runbook: pos-mcp-server/docs/runbooks/tool-discovery-failure.md

## Implementation Notes

- Grace-period thresholds (e.g. 15m) should exceed startup discovery time plus Eureka registration
  lag on the target environment; tune per environment.
- Alerts 1–3 are metric-based (Prometheus). Alert 4 is log-based (Loki/CloudWatch Logs) — key off the
  fixed log phrases in `ToolRegistrationServiceImpl` / `OpenApiDocumentFetcher`.
- Connect each alert to the on-call rotation and notification channels.
