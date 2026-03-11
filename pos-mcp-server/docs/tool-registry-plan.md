## Plan: POS MCP Tool Registry

Implement a production-ready registry in pos-mcp-server that narrows MCP tool selection using role/workflow/intent gating, OpenAI embeddings (configurable provider), deterministic scoring, and audit-driven adaptive priority tuning enabled by default with a runtime kill switch. Expose admin/write APIs with strict RBAC and event logging.

**Execution Checklist**

- [ ] Lock architecture choices from stakeholder decisions (now confirmed).
- [ ] Enforce role-source-of-truth rule: mcp_role mirrors security-service role codes only.
- [ ] Build registry schema/entities/repositories including intent mapping.
- [ ] Implement configurable embedding provider (OpenAI first).
- [ ] Implement candidate resolver with session-store workflow state.
- [ ] Integrate resolver into MCP runtime and fallback behavior.
- [ ] Implement audit logging + adaptive tuning (enabled by default, toggle-able).
- [ ] Expose admin/write APIs secured by permissions.
- [ ] Add tests, docs, and production readiness checks.

**Steps**

1. Phase 1 - Scope Lock and Decision Capture

- [ ] Record final decisions:
- [ ] Embeddings: OpenAI as default provider, provider selection configurable by properties.
- [ ] Include intent mapping in v1.
- [ ] Workflow state source: session-store (best-practice, safer consistency).
- [ ] Adaptive tuning: enabled by default, runtime toggle to disable.
- [ ] Expose admin/write APIs for registry management.
- [ ] Define deferred items: multi-armed bandit and per-role adaptive tuning experiments.
- Depends on: none.

1. Phase 2 - Data Model and Persistence

- [ ] Add entities (internal package) for:
- [ ] mcp_tool
- [ ] mcp_role
- [ ] mcp_tool_role
- [ ] mcp_workflow_state
- [ ] mcp_tool_workflow
- [ ] mcp_intent
- [ ] mcp_intent_tool
- [ ] mcp_tool_invocation_log
- [ ] Define mcp_role as a mirrored lookup of security-service canonical role codes.
- [ ] Disallow local-only role creation that does not exist in security service.
- [ ] Add startup sync/validation for role catalog and fail-closed behavior for unknown roles.
- [ ] Apply standards: UUIDv7 IDs, createdAt/updatedAt auditing, no cross-module leaks.
- [ ] Add repositories for CRUD + query methods for role/workflow/intent filtering.
- [ ] Implement vector ranking query path for PostgreSQL/pgvector, plus deterministic fallback path for non-vector environments.
- Depends on: Phase 1.

1. Phase 3 - Configurable Embedding Layer

- [ ] Add EmbeddingService interface and provider strategy.
- [ ] Implement OpenAIEmbeddingService first.
- [ ] Add property-driven provider configuration (e.g., openai|azure|disabled), model name, timeout, key source.
- [ ] Add safe degraded behavior when embedding provider unavailable.
- Depends on: Phase 2.

1. Phase 4 - Registry Resolution Core

- [ ] Add ToolSelectionContext including userInput, role, workflowState, intent, session metadata.
- [ ] Add ToolScorer deterministic formula (semantic rank + priority boost - latency penalty - cost penalty).
- [ ] Implement ToolRegistryService.resolveCandidateTools(context, topK):
- [ ] role/workflow/intent prefilter
- [ ] embedding top-K retrieval
- [ ] intersection and deterministic stable ordering
- [ ] return top 3-5 tools
- [ ] Ensure no comparator side effects; ordering must be repeatable for same inputs.
- Depends on: Phase 3.

1. Phase 5 - Session-Store Workflow State Integration

- [ ] Implement workflow state persistence/retrieval via session-store abstraction.
- [ ] Resolve current workflow state from session at request time.
- [ ] Enforce safe defaults (IDLE) when state missing/expired.
- [ ] Add concurrency/expiry safeguards and observability for stale state.
- Depends on: Phase 4 (parallelizable after context model exists).

1. Phase 6 - MCP Runtime Integration

- [ ] Integrate registry candidate output into existing MCP discovery/invocation flow.
- [ ] Preserve OpenAPI discovery and proxy execution; registry narrows choice set only.
- [ ] Add deterministic fallback if registry/session/embedding failures occur.
- Depends on: Phases 4 and 5.

1. Phase 7 - Audit Logging and Adaptive Priority

- [ ] Implement ToolAuditService logging selection + execution outcomes immediately after calls.
- [ ] Implement scheduled ToolPriorityTuningService (daily) using 7-day window, sample threshold, clamp, smoothing.
- [ ] Set adaptive tuning enabled by default.
- [ ] Add explicit feature toggle to disable adaptive tuning at runtime/config level.
- [ ] Add guardrails for low-sample tools and outlier latencies.
- Depends on: Phase 6.

1. Phase 8 - Admin/Write APIs and Security

- [ ] Add controller endpoints for create/update/delete of tools, role mappings, workflow mappings, and intent mappings.
- [ ] Enforce @PreAuthorize permissions on all endpoints.
- [ ] Add permissions in permissions.yaml for read/write/admin scopes.
- [ ] Add @EmitEvent for state-changing operations and register event types.
- [ ] Add request validation and safe error handling.
- Depends on: Phase 2 (can progress in parallel with Phases 5-7 once entities are ready).

1. Phase 9 - Testing and Verification

- [ ] Unit test scorer and resolver edge cases (empty gates, tie scores, provider outage, fallback mode).
- [ ] Integration test repository filters for role/workflow/intent.
- [ ] Integration test session-store workflow resolution behavior.
- [ ] Test adaptive tuning enabled-by-default and disable toggle behavior.
- [ ] Test admin/write APIs for RBAC success/failure scenarios.
- [ ] Run module tests and architecture tests.
- Depends on: Phases 2-8.

1. Phase 10 - Documentation and Operations

- [ ] Document config: embedding provider settings, OpenAI defaults, session-store requirements, adaptive toggle.
- [ ] Document admin API contracts and permissions model.
- [ ] Document seeding/runbook for initial tool/role/workflow/intent metadata.
- [ ] Document fallback and incident troubleshooting paths.
- Depends on: all prior phases.

**Relevant files**

- /home/n541342/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/discovery/OpenApiToolMapper.java - preserve tool discovery behavior.
- /home/n541342/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/discovery/OperationProxyFactory.java - invocation hooks and audit capture point.
- /home/n541342/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/ToolRegistrationServiceImpl.java - candidate registry integration.
- /home/n541342/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/discovery/ToolBootstrapRunner.java - lifecycle wiring for registry setup.
- /home/n541342/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/resources/permissions.yaml - new read/write/admin permission entries.
- /home/n541342/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/config/McpEventTypes.java - event registrations for admin writes.
- /home/n541342/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/config/McpEventTypeInitializer.java - startup event type registration.
- /home/n541342/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/test/java/com/positivity/mcp/ArchitectureTest.java - architecture constraints to keep passing.
- /home/n541342/IdeaProjects/durion-positivity-backend/pos-mcp-server/docs/tool-registry-implementation.md - baseline registry algorithm.
- /home/n541342/IdeaProjects/durion-positivity-backend/pos-mcp-server/docs/tool-audit-logging.md - adaptive tuning and audit schema reference.
- /home/n541342/IdeaProjects/durion-positivity-backend/pos-mcp-server/docs/domain-facade-tools.md - facade boundaries and intent-routing context.

**Verification**

1. Verify registry output size remains 3-5 candidates for representative requests.
2. Verify intent + role + workflow gating excludes unauthorized/inapplicable tools.
3. Verify session-store state transitions are reflected in allowed tools.
4. Verify OpenAI embedding path works and provider switch is configuration-only.
5. Verify adaptive tuning runs by default and can be disabled with toggle.
6. Verify admin/write APIs enforce RBAC and emit audit events.
7. Verify unknown or unsynced roles fail closed (no privileged tool access).
8. Run module build/tests and confirm architecture tests pass.

**Decisions Confirmed**

- Embedding provider: OpenAI first, provider-configurable.
- Intent mapping: included in v1.
- Workflow state source: session-store.
- Adaptive tuning: enabled by default, disable toggle provided.
- API surface: include admin/write endpoints.
- mcp_role maps to security-service canonical roles; security service remains source of truth.

**Open Questions**

1. Session-store technology choice and ownership boundary:

- Recommendation: use existing platform session mechanism if available; otherwise Redis-backed session store with TTL + optimistic concurrency.

2. Admin API granularity:

- Recommendation: separate endpoints by resource (tool, role-map, workflow-map, intent-map) with explicit scoped permissions.

3. Production safety defaults for adaptive tuning:

- Recommendation: include minimum sample size, max daily delta limit, and audit-only dry-run mode for first rollout window.

4. Secrets/config source for OpenAI key in each environment:

- Recommendation: secret store/env var only, never checked-in config.

**Role Sync Strategy**

1. Source of truth

- Security service owns canonical roles and role assignments.
- mcp_role is a mirrored lookup table used only for registry gating joins.

1. API contract (read-only)

- Initial sync endpoint (catalog): GET /v1/security/roles
- Optional membership validation endpoint: GET /v1/security/users/{userId}/roles
- Required fields per role: roleCode (stable key), displayName, enabled, updatedAt
- mcp_role should store roleCode as unique business key and optional display metadata.

1. Sync lifecycle

- Startup sync: mandatory before enabling tool registry candidate selection.
- Periodic refresh: every 5-15 minutes (configurable), plus manual admin-triggered refresh endpoint.
- On-demand revalidation: for cache misses or unknown incoming role codes.

1. Conflict and failure policy

- Unknown/unsynced role codes must fail closed (no privileged tool access).
- If security role API is unavailable at startup: keep registry in restricted mode (deny privileged candidates) and emit alerts.
- If periodic refresh fails: retain last known-good role catalog with max staleness threshold; after threshold, fail closed.

1. Observability and controls

- Metrics: lastSyncTimestamp, syncDurationMs, syncSuccess/failure count, staleCatalogAgeSeconds, unknownRoleCount.
- Logs: structured role sync outcome and mismatch details without PII.
- Feature flags: role-sync.enabled, role-sync.interval, role-sync.max-staleness, role-sync.startup-required.

1. Security boundaries

- Admin/write APIs in mcp-server may manage tool-to-role mappings only.
- Admin/write APIs must not create canonical roles; role creation/edit remains in security service.

**Further Considerations**

1. Add seed/bootstrap strategy for initial role/workflow/intent mappings to avoid empty candidate sets at launch.
2. Define SLO for registry overhead (e.g., added latency budget) and alert thresholds.
3. Consider versioning intent taxonomy to avoid breaking behavior during intent model evolution.
