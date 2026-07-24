# Tool Discovery Alert Rules (#645)

Alert conditions for OpenAPI-driven MCP tool discovery and registration. The MCP server discovers
gateway operations at startup (and, when `mcp.server.discovery-refresh.enabled=true`, periodically)
and registers them as MCP tools. Two Micrometer counters back these alerts (Prometheus names in
parentheses):

- `tools.discovered` (`tools_discovered_total`) — operations matched to tools during a discovery run,
  from either the gateway aggregate spec or the per-service Eureka fallback.
- `tools.registered` (`tools_registered_total`) — tools successfully added to the MCP server.

Both are cumulative counters that advance during each discovery run (startup + each refresh cycle).

1. Name: McpToolDiscoveryProducedNoTools
   - Trigger: the server has been up past its discovery grace period but has registered no tools —
     `max_over_time(tools_registered_total[15m]) == 0` while the instance is `up`. Indicates the
     gateway aggregate spec was unavailable/empty **and** the per-service Eureka fallback found no
     reachable service specs.
   - Severity: P1 (the assistant can call no discovered gateway tools)
   - Runbook: pos-mcp-server/docs/runbooks/tool-discovery-failure.md

2. Name: McpToolRegistrationLaggingDiscovery
   - Trigger: a sustained gap between discovered and registered tools —
     `(tools_discovered_total - tools_registered_total) > 0` for 10 minutes. Some matched operations
     repeatedly fail to register (e.g. `McpAsyncServer.addTool` errors).
   - Severity: P2
   - Runbook: pos-mcp-server/docs/runbooks/tool-discovery-failure.md

3. Name: McpToolCountDroppedSharply
   - Trigger: registered tool count falls well below its recent norm across a refresh —
     `tools_registered_total < 0.5 * max_over_time(tools_registered_total[1h])`. A refresh cycle
     discovered far fewer tools than usual (partial gateway outage, spec regression, or many services
     de-registered from Eureka). Only meaningful when `discovery-refresh.enabled=true`.
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
