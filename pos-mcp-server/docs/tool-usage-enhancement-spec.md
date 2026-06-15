---
title: Tool Usage Enhancement Spec
description: Implementation-ready spec for improving MCP tool selection, caching, and transport parity in pos-mcp-server.
status: deprecated
updated: 2026-06-11
---

This specification consolidates the findings from the existing NLQ-to-API
analysis documents into one implementation plan for improving tool usage in
`pos-mcp-server`. It is based on the current alpha-profile behavior in the
runtime code, not on intended design alone.

## Problem Statement

The current MCP chat orchestration path does not consistently present the right
tool set to the model.

- Blocking chat performs semantic selection, but the semantic search is run
  against the global enabled tool catalog before role and workflow gating are
  enforced.
- Streaming chat does not use the same selection logic as blocking chat and can
  expose a materially broader tool surface for the same user intent.
- Role-to-tool preloading is incomplete because the runtime registry hardcodes a
  subset of supported roles.
- Cached agents do not honor the configured TTL, so prompt and tool-mapping
  changes can remain stale after configuration updates.
- Workflow-state-aware tool routing is described conceptually, but the runtime
  still assumes a single `IDLE` state for selection and preloading.

These gaps reduce recall for allowed tools, create behavior drift across
endpoints, weaken operational predictability, and make configuration changes
hard to trust in production.

## Solution

Implement one shared tool-selection pipeline that is role-aware,
workflow-aware, cache-coherent, and transport-neutral.

The target design is:

- Resolve the caller's primary role through one shared component used by both
  chat controllers.
- Resolve candidate tools from a repository query that applies role and
  workflow gating before or during semantic ranking.
- Use the same selection contract for blocking and streaming chat so endpoint
  choice changes transport behavior only, not tool-selection semantics.
- Apply TTL and explicit invalidation to agent caches so prompt and tool changes
  become visible without process restart.
- Replace hardcoded preload assumptions with role data sourced from the same
  authoritative role mapping used by the rest of the system.
- Preserve deterministic domain fallback only where it provides a deliberate
  recovery path, with explicit confidence rules rather than ad hoc keyword
  drift.

## User Stories

1. As a cashier, I want the assistant to see only cashier-allowed tools that
   are relevant to my request, so that the model does not waste turns on tools
   I cannot use.

2. As a technician, I want my role-specific tools to be available without code
   changes to a hardcoded preload list, so that technician workflows work as
   soon as the database mappings are configured.

3. As an admin, I want access-related queries to reliably select the admin tool
   set, so that user, role, and permission questions do not fall into a weaker
   semantic search path.

4. As a service writer, I want order and customer questions to return the same
   effective tool surface whether I use blocking chat or streaming chat, so that
   transport choice does not change system behavior.

5. As a platform operator, I want prompt edits and tool-role mapping changes to
   take effect within a bounded time window, so that production tuning does not
   require restarts.

6. As an operator managing tool metadata, I want semantic selection to consider
   only tools the caller is allowed to use, so that a globally similar but
   unauthorized tool does not crowd out the correct authorized tool.

7. As an engineer, I want one selection service shared across blocking and
   streaming orchestration, so that behavior is easier to reason about, test,
   and instrument.

8. As an on-call engineer, I want logs and metrics that explain which tools were
   gated, ranked, selected, and cached, so that tool-misrouting incidents can be
   debugged quickly.

9. As a product owner, I want workflow-state support to either work end to end
   or be explicitly deferred, so that the design does not promise behavior that
   the runtime cannot deliver.

10. As a security reviewer, I want prompt resolution and tool selection to be
    observable when the system falls back to defaults, so that authorization-
    adjacent configuration drift is visible.

11. As a model-tuning operator, I want deterministic routing to remain available
    for clear, high-confidence domains, so that obvious requests do not depend
    entirely on embedding quality.

12. As a backend maintainer, I want role resolution to come from one source of
    truth, so that controller changes and registry changes do not drift apart.

13. As a support user, I want short but task-oriented requests like `stock part
1234` to reach the correct tool path, so that terse operational phrasing is
    not misclassified or under-selected.

14. As a release manager, I want the tool-selection changes to roll out in
    phases with feature flags or safe defaults where needed, so that recall and
    latency can be monitored before wider adoption.

## Implementation Decisions

- Introduce a shared role-resolution component that encapsulates role priority,
  fallback behavior, and any future expansion of supported roles.

- Replace the current global semantic nearest-neighbor query with a role-gated
  and workflow-gated retrieval path. Preferred implementation order:
  role/workflow gating in the retrieval query itself; acceptable interim step:
  query a much wider candidate pool while keeping the gated scoring contract the
  same.

- Keep the semantic scoring pipeline, but normalize or explicitly weight the
  semantic, priority, latency, and cost inputs before additional tuning work is
  attempted.

- Keep deterministic fallback routing, but narrow it to intentional,
  well-defined domain recovery rules. It should act as a confidence-based
  supplement to semantic retrieval, not as an unrelated second selection system.

- Create one shared tool-selection service contract that returns:
  gated candidates, selected runtime tool beans, fallback additions, selection
  diagnostics, and a stable cache key.

- Make both blocking and streaming orchestration depend on that shared selection
  contract. The simple-chat fast path may remain blocking-only if justified, but
  the non-simple tool path must be semantically equivalent across transports.

- Apply the configured cache TTL to the role-agent cache in addition to request
  and chat-memory caches.

- Add explicit invalidation hooks for prompt changes and tool-role mapping
  changes. At minimum, invalidation must evict cached agents for affected roles.

- Remove hardcoded preload-role lists from the runtime registry. Roles must be
  loaded from authoritative persisted role data or from one shared application
  enumeration that is also used by the controllers.

- Treat missing role-specific prompts as an operational warning. Falling back to
  `default` or the built-in prompt should emit structured logs and metrics.

- Defer full workflow-state-aware tool routing unless the session model can
  persist and retrieve workflow state reliably. Until then, the spec assumes the
  runtime remains on `IDLE`, and product documentation must not claim otherwise.

- Preserve small default candidate counts for the current `llama3.1:8b` model
  unless low-confidence conditions justify temporary widening. The first fix is
  gated retrieval quality, not broader prompt stuffing.

- Instrument the new flow with metrics for candidate pool size, gated pool size,
  selected tool count, deterministic fallback usage, cache hit rate, cache
  invalidations, prompt fallback frequency, and latency by transport.

## Delivery Plan

1. Build the shared role-resolution component and replace controller-local role
   priority logic.

2. Remove hardcoded role preloading and load role mappings from authoritative
   role data.

3. Implement gated retrieval for tool selection and add selection diagnostics.

4. Refactor blocking orchestration to consume the shared selection result with
   normalized scoring and deliberate fallback behavior.

5. Refactor streaming orchestration to use the same shared selection result.

6. Add TTL and invalidation for cached agents.

7. Add observability for prompt fallback, selection drift, cache churn, and
   transport parity.

8. Reassess candidate limits, deterministic routing breadth, and workflow-state
   expansion only after the above behavior is stable and measurable.

## Testing Decisions

- A good test validates externally observable behavior: which role resolves,
  which tools are eligible, which tools are selected, whether cache invalidation
  takes effect, and whether blocking and streaming produce the same effective
  tool surface for the same request.

- Unit tests should cover the shared role resolver, gated selection service,
  deterministic fallback rules, cache-key generation, and score normalization.

- Repository tests should verify that gated retrieval excludes unauthorized
  tools before ranking outcomes are produced.

- Orchestration tests should verify:
  blocking vs. streaming parity, correct fallback to full-role tools only when
  selection truly produces no usable tool set, and TTL/invalidation behavior for
  prompt or tool-mapping changes.

- Controller tests should verify that both endpoints resolve the same primary
  role for the same authority set.

- Integration tests should cover at least one scenario each for cashier,
  technician, admin, and `ROLE_USER` fallback so the preload and selection bugs
  cannot regress silently.

- Regression tests for the simple-chat classifier should focus on terse,
  task-oriented messages that should reach tool selection rather than the
  no-tool fast path.

- Prior art for these tests should come from the existing controller,
  orchestration, and repository test patterns already used in `pos-mcp-server`,
  with emphasis on behavior-driven assertions rather than internals of
  LangChain4j proxy construction.

## Success Criteria

- A role-allowed tool that is semantically relevant is no longer excluded by a
  globally limited pre-gated candidate set.

- `ROLE_TECHNICIAN` and `ROLE_USER` can be supported without code changes to a
  preload constant.

- Prompt edits and tool-mapping changes become visible within the configured TTL
  or immediately after explicit invalidation.

- Blocking and streaming chat produce the same selected tool set for the same
  role, workflow state, and non-simple message.

- Selection logs and metrics are sufficient to explain why a tool was or was not
  available.

## Out of Scope

- Replacing LangChain4j with a different agent framework.

- Expanding the MCP protocol tool-registration system used for external clients.

- General RAG redesign, document taxonomy redesign, or large-scale prompt
  authoring beyond the minimum observability and fallback requirements needed for
  tool-selection quality.

- Arbitrary text-to-SQL or direct ad hoc database querying.

- Full workflow-engine implementation beyond establishing a clean boundary for
  future workflow-state-aware selection.

## Further Notes

- The database-backed `mcp_tool.description` field remains the primary tuning
  input for semantic retrieval. Java `@Tool` and parameter descriptions still
  matter, but they influence tool calling after selection rather than the
  retrieval step itself.

- This spec intentionally prioritizes correctness and operational coherence over
  broader tool exposure. Increasing candidate counts or redesigning agent-cache
  architecture should be deferred until gated retrieval, cache freshness, and
  transport parity are in place.

- If this spec is accepted, the next deliverable should be a small execution
  plan that breaks the work into repository, orchestration, cache, and
  observability slices with explicit regression tests for each slice.
