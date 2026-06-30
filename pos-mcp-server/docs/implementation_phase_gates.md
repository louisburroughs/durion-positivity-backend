# Natural-Language Interface Implementation Plan with Phase Gates

## Purpose

This plan converts the NL interface design for `pos-mcp-server` into an execution framework with explicit phase gates. Each gate is designed to answer four questions before work proceeds:

1. **Completeness:** Did we implement all required scope for this phase?
2. **Correctness:** Does the implementation behave as intended?
3. **Safety:** Did we preserve permission boundaries, write controls, and rollback options?
4. **Drift control:** Did we avoid expanding or changing scope without explicit approval?

No phase should begin until the prior phase gate is passed or formally waived.

---

# Gate 0 — Measurement, Telemetry, and Config Hygiene

## Phase goal

Create the measurement foundation before changing prompts, routing, retrieval, tool execution, or writes.

## Required scope

- Build the evaluation harness.
- Add telemetry event schema and emitter.
- Reconcile model configuration drift.
- Disable adaptive tuning by default.
- Capture baseline metrics.

## Completeness gate

This phase is complete only when all of the following are true:

- Evaluation harness exists and runs in CI.
- Fixture sets meet minimum size:
  - At least 100 tool-selection fixtures.
  - At least 50 RAG retrieval fixtures.
  - At least 30 write-action safety fixtures.

- Baseline metrics are recorded:
  - tool-selection hit@5,
  - tool-selection MRR,
  - RAG recall@k,
  - write-gate safety results,
  - p50/p95 latency by tier where available.

- Telemetry event schema captures:
  - correlation ID,
  - session ID,
  - primary role,
  - router decision,
  - selected model tier,
  - actual model name,
  - fallback usage,
  - selected tools,
  - permission-rejected tools,
  - retrieved RAG document IDs and scores,
  - prompt layers included,
  - write-risk level,
  - confirmation outcome,
  - latency by tier,
  - unsupported or low-grounding flags where detectable.

- `application.yml` and README identify one deliberate default model.
- `mcp.tuning.enabled=false` is set.
- Adaptive tuning does not mutate live tool priorities.

## Correctness tests

- CI executes the harness from a clean checkout.
- Fixture failures are visible and actionable.
- Telemetry emits one structured event per request.
- Telemetry is separate from audit events and tool invocation logs.
- Config documentation matches runtime defaults.

## Drift checks

Reject phase completion if:

- Teams start prompt, routing, retrieval, or write-gate behavior changes before baseline metrics exist.
- New models are introduced without documenting default, fallback, and intended tier.
- Adaptive tuning remains live before regression gates exist.
- Telemetry is postponed to a later phase.

## Exit decision

Proceed only if baseline numbers are captured and CI can detect regressions.

## Rollback

Measurement-only rollback. Revert harness/config additions if necessary. Runtime behavior should remain unchanged except telemetry emission and tuning disablement.

---

# Gate 1 — Role-First Layered Prompts

## Phase goal

Make the assistant role-aware without changing authorization behavior.

## Required scope

- Seed role persona prompts.
- Implement layered prompt assembly:
  - BASE,
  - ROLE,
  - DOMAIN,
  - TOOL-USE,
  - optional WRITE-GATE later.

- Resolve prompt role-first, domain-second.
- Preserve existing 16 domain prompts.
- Align blocking and streaming prompt resolution.
- Emit prompt-layer telemetry.

## Completeness gate

This phase is complete only when:

- Role prompts are seeded for:
  - `ROLE_SERVICE_ADVISOR`,
  - `ROLE_TECHNICIAN`,
  - `ROLE_DISPATCHER`,
  - `ROLE_LOCATION_MANAGER`,
  - `ROLE_ACCOUNT_MANAGER`,
  - `ROLE_ACCOUNTING_ASSOCIATE`,
  - `ROLE_ADMIN`,
  - `ROLE_SYSTEM_ADMINISTRATOR`,
  - `ROLE_USER`.

- `ROLE_CUSTOMER` and `ROLE_SELF_SERVICE_CUSTOMER` are not seeded for this internal-only interface.
- Prompt assembly includes BASE + ROLE + DOMAIN + TOOL-USE for ordinary requests.
- Prompt assembly does not use role to grant tool access.
- Domain prompts continue to apply by RAG scope/domain.
- Blocking and streaming use the same prompt-resolution logic.
- Telemetry shows which prompt layers were included.

## Correctness tests

- Same query from technician and accounting roles produces different persona framing.
- Same query from two roles with identical permissions does not change accessible tools.
- Same query through blocking and streaming produces the same prompt layer selection.
- Answer-quality eval is equal to or better than Phase 0 baseline.
- Tool-selection metrics do not regress beyond threshold:
  - hit@5 drop no greater than 2 percentage points,
  - MRR drop no greater than 5%, unless explicitly approved.

## Drift checks

Reject phase completion if:

- Role persona is used as an authorization mechanism.
- Role-only RAG filtering is introduced.
- New prompt layers are added without telemetry.
- Domain prompts are rewritten unnecessarily.
- Customer-facing personas appear in seed data.

## Exit decision

Proceed only if role-aware behavior is visible, measurable, and does not alter permission boundaries.

## Rollback

Feature-flag role-first resolution. Fall back to prior domain-derived prompt resolution if evals regress. Seeded prompts remain harmless as additive data.

---

# Gate 2A — Shared Blocking/Streaming Orchestration Path

## Phase goal

Ensure blocking and streaming requests use one coherent orchestration path.

## Required scope

- Unify tool-selection path.
- Unify prompt-resolution path.
- Fix role preload.
- Enforce cache TTL or explicit cache invalidation.

## Completeness gate

This phase is complete only when:

- Blocking and streaming managers call shared selection logic.
- Blocking and streaming managers call shared prompt assembly logic.
- `ROLE_TECHNICIAN` and `ROLE_USER` are included in preload.
- Agent cache respects `mcp.agent.cache-ttl-minutes`.
- Cache invalidation behavior is documented.
- Telemetry can distinguish endpoint type while showing equivalent orchestration decisions.

## Correctness tests

- Same user, same permissions, same request:
  - same candidate tools,
  - same prompt layers,
  - same persona,
  - same RAG scope,
  - same workflow state.

- Cache entries older than TTL are not reused.
- Preload includes all roles in `MCP_ROLE_PRIORITY`.
- Tool-selection hit@5 and MRR remain within regression thresholds.

## Drift checks

Reject phase completion if:

- Blocking and streaming continue to maintain divergent logic.
- Fixes are duplicated in both managers instead of centralized.
- Cache behavior is undocumented.
- Role preload is partially patched but not aligned with `MCP_ROLE_PRIORITY`.

## Exit decision

Proceed only when endpoint behavior is functionally identical for the same request context.

## Rollback

Keep old per-manager paths behind a temporary flag during cutover. Revert to old paths only if shared orchestration causes measurable regression.

---

# Gate 2B — Permission-Gating Cleanup and Security Sentinel

## Phase goal

Make authorization explainable solely by permission codes and workflow state.

## Required scope

- Remove legacy role-gating path.
- Migrate or deprecate legacy role-gating tables.
- Add `AUTHENTICATED` sentinel behavior.
- Add role-default-permissions endpoint for cache prebuild.
- Keep fail-closed semantics.

## Completeness gate

This phase is complete only when:

- Tool-selection query does not reference role.
- `ToolRegistryRoleMapper` is removed or fully bypassed.
- `mcp_role` and `mcp_tool_role` are renamed to deprecated tables or otherwise safely retired.
- `mcp_tool_permission` is the source of permission mapping.
- Unguarded operations emit or are treated as requiring `AUTHENTICATED`.
- Missing permissions do not produce fail-open access.
- Role-default-permissions endpoint exists and is used for cache prebuild.
- DB snapshot or rollback migration exists before destructive schema changes.

## Correctness tests

- A user with no required permission cannot select the tool.
- A tool with zero permission rows is not selected.
- A user with required permission can select the tool regardless of nominal role.
- `AUTHENTICATED` tools are available only to authenticated users.
- Legacy role-gating tables are not consulted at runtime.
- Tool-selection metrics remain within thresholds.

## Drift checks

Reject phase completion if:

- Role remains in gating SQL.
- Any fallback grants access because permissions are absent.
- Sentinel rollout is partial and undocumented.
- Destructive migrations are applied without rollback/snapshot.
- Permission behavior differs between blocking and streaming.

## Exit decision

Proceed only when every selected tool can be explained by:

> permissionCodes ∩ mcp_tool_permission ∩ workflowState

## Rollback

Prefer renaming legacy tables to `*_deprecated` for one release instead of immediate drop. Restore snapshot if migration causes production issue.

---

# Gate 2C — Workflow State Beyond `IDLE`

## Phase goal

Activate workflow-specific tool sets deterministically.

## Required scope

- Persist workflow state on `NltiSession`.
- Thread workflow state into `ToolSelectionContext`.
- Preload non-IDLE workflow tool sets.
- Reconcile workflow state with conversation state.

## Completeness gate

This phase is complete only when:

- `NltiSession` stores current workflow state.
- Tool selection receives workflow state from the session.
- `WORKFLOW_IDLE` is no longer hardcoded in session managers.
- Non-IDLE states such as `CREATING_PO` and `PROCESSING_RETURN` activate their intended tools.
- Workflow state is distinct from conversation lifecycle state.
- Workflow transitions are logged in telemetry.

## Correctness tests

- IDLE session receives only IDLE-eligible tools.
- `CREATING_PO` session receives PO-creation workflow tools.
- `PROCESSING_RETURN` session receives return-processing tools.
- Changing workflow state changes available tools only as expected.
- Permission gating still applies inside every workflow state.
- Blocking and streaming remain equivalent.

## Drift checks

Reject phase completion if:

- Workflow state becomes a substitute for permission checks.
- Conversation state and workflow state are conflated.
- Non-IDLE tool sets are activated globally.
- Managers still default every request to `WORKFLOW_IDLE`.

## Exit decision

Proceed only when non-IDLE workflows are testable and permission-safe.

## Rollback

Feature-flag workflow state selection. Default back to `IDLE` if needed.

---

# Gate 3 — OpenAPI Tool Execution Bridge

## Phase goal

Allow the assistant to execute discovered OpenAPI operations without requiring a hand-written facade for each operation.

## Required scope

- Implement LangChain4j `ToolProvider` for `source='openapi'`.
- Invoke operations through `OperationProxyFactory`.
- Propagate user context and permission codes.
- Prevent cached-agent permission leakage.
- Keep facade tools working.

## Completeness gate

This phase is complete only when:

- OpenAPI-discovered operations can become agent-callable tools.
- Only selected and permission-eligible operations are exposed to the agent.
- Proxied calls include current user context.
- Cached agents cannot expose tools from a prior higher-permission user.
- Facade tools and OpenAPI tools can coexist.
- Telemetry distinguishes facade vs OpenAPI tool source.

## Correctness tests

- End-to-end test executes a discovered operation with no facade.
- Lower-permission user cannot call higher-permission OpenAPI operation.
- Permission re-check happens at call time, not only cache-build time.
- Operation arguments are schema-validated before proxy call.
- Failed proxy calls produce controlled errors, not hallucinated success.
- Blocking and streaming support the bridge identically.

## Drift checks

Reject phase completion if:

- All 500+ operations are exposed without candidate selection.
- OpenAPI operations bypass permission checks.
- The LLM can choose arbitrary URLs or operations outside registry.
- Tool schemas are generated without argument validation.
- Facade behavior regresses.

## Exit decision

Proceed only when one discovered non-facade operation is safely callable end-to-end.

## Rollback

Disable OpenAPI `ToolProvider` by flag. System falls back to facade-only execution.

---

# Gate 4 — Tiered Model Router

## Phase goal

Route requests to the cheapest model tier that preserves quality and safety.

## Required scope

- Add T1 router/classifier.
- Classify:
  - intent,
  - risk,
  - domain,
  - complexity.

- Route simple queries to T2-simple.
- Route complex, write, accounting, tax, admin, and security requests to T2-complex.
- Keep fallback model strategy orthogonal.
- Emit router telemetry.

## Completeness gate

This phase is complete only when:

- T1 router returns strict JSON.
- Router temperature is zero or equivalent deterministic setting.
- Router output is validated before use.
- Invalid router output falls back safely.
- ACTION requests always route to T2-complex.
- `NltiRiskLevel≥MEDIUM` routes to T2-complex.
- Multi-tool expected requests route to T2-complex.
- Model tier and actual model name appear in telemetry.

## Correctness tests

- At least 80% of simple fixture queries route to T2-simple.
- All write fixtures route to T2-complex.
- All accounting/tax/admin/security fixtures route to T2-complex.
- Invalid JSON from router does not break request processing.
- Answer-quality eval is equal to or better than Phase 1.
- p95 latency stays within the soft SLO.
- Fallback model usage is visible in telemetry.

## Drift checks

Reject phase completion if:

- Cost reduction is achieved by routing risky requests to small models.
- Router decisions are not logged.
- Router classification overrides permission gating.
- Fallback behavior is confused with tier-routing behavior.
- Prompt changes are bundled into this phase without separate eval.

## Exit decision

Proceed only when cost savings are measurable and quality/safety are preserved.

## Rollback

Route all requests to T2-complex through a feature flag. This increases cost but restores known-good quality.

---

# Gate 5 — RAG Expansion, Permission-Aware Filtering, and Hybrid Retrieval

## Phase goal

Improve answer grounding, exact-code retrieval, and permission-safe knowledge visibility.

## Required scope

- Add P1/P2 RAG documents.
- Tag all documents with:
  - `rag-scope`,
  - `min-permission` or permission set.

- Enforce permission-aware RAG filtering.
- Add hybrid retrieval.
- Migrate embeddings to `bge-m3` 1024-dimensional vectors.
- Preserve rollback path during migration.

## Completeness gate

This phase is complete only when:

- New RAG documents exist for:
  - “What can I ask” capability catalog,
  - cross-domain workflow playbooks,
  - glossary and identifier formats,
  - order,
  - pricing,
  - tax,
  - customer,
  - vehicle,
  - catalog,
  - reporting metrics,
  - governance/approval gates,
  - observability/event tracing.

- Every RAG document has permission metadata.
- Admin/security docs require admin/security permissions.
- Role is not the sole RAG visibility gate.
- Hybrid dense + BM25 or FTS retrieval is active behind the retrieval harness.
- 1024-dimensional embedding path is validated.
- Prior 768-dimensional embedding data is retained until validation is complete.

## Correctness tests

- Exact work order, invoice, PO, VIN, SKU, account-code, and claim-code fixtures improve recall.
- Admin-only documents are never returned to non-admin fixtures.
- Permission-elevated users retrieve according to permissions, not nominal role.
- RAG recall@k improves or remains within approved threshold.
- Dense-only and hybrid retrieval comparisons are recorded.
- Chunking strategy is validated:
  - small chunks for glossary/identifier docs,
  - larger chunks for prose/playbook docs.

## Drift checks

Reject phase completion if:

- Documents are added without permission tags.
- Role-only RAG filtering is introduced.
- Embedding migration is done piecemeal.
- Hybrid weights are tuned by intuition rather than harness results.
- New RAG docs become a substitute for missing API/tool grounding.
- Admin docs are visible to floor-staff fixtures.

## Exit decision

Proceed only when retrieval quality improves and visibility rules are proven.

## Rollback

Snapshot embedding tables before migration. Keep both 768 and 1024 paths until 1024 path passes evaluation. Revert retrieval config to prior dense path if hybrid retrieval regresses.

---

# Gate 6 — Write-Action Confirmation Gate

## Phase goal

Enable safe gated writes through preview, explicit confirmation, and exact persisted execution.

## Required scope

- Implement conversation lifecycle states.
- Implement write plan creation.
- Add pending confirmation.
- Add plan expiry.
- Add idempotency key.
- Add argument provenance.
- Add stale-data protection.
- Add dual permission checks.
- Add audit chain.
- Inject WRITE-GATE prompt layer.
- Suppress direct mutation by the model.

## Completeness gate

This phase is complete only when:

- `NltiRequestStatus` supports:
  - `PENDING_CONFIRMATION`,
  - `CONFIRMED`,
  - `EXECUTING`,
  - `CANCELLED`,
  - `EXPIRED`,
  - existing terminal states.

- ACTION requests produce PLAN, not immediate execution.
- Write plans persist:
  - target tool,
  - exact arguments,
  - idempotency key,
  - risk level,
  - argument provenance,
  - source entity versions where available,
  - expiration timestamp.

- Confirmation executes exact persisted args.
- Confirmation does not re-parse user text.
- Plan expires after configured TTL.
- Material user changes cancel and replace prior pending plan.
- At most one pending plan exists per session.
- Permission is checked at plan time and execution time.
- Medium/high-risk writes re-read source records before execution where possible.
- Changed source data forces re-preview.
- All write steps emit audit events:
  - PLAN,
  - CONFIRMATION,
  - EXECUTION_STEP,
  - EXECUTION_COMPLETE or EXECUTION_FAILED.

- WRITE-GATE prompt layer is active when write-capable tools are candidates.

## Correctness tests

- No mutation occurs without confirmation.
- Plan args equal executed args.
- Expired confirmation does not execute.
- Re-sent confirmation with same idempotency key does not double-execute.
- User changes material argument while pending → old plan cancelled, new preview created.
- Missing required argument → clarification, not guessed value.
- Inferred defaults are visible in preview.
- High-risk inferred defaults are rejected or require explicit user selection.
- Lower-permission user cannot execute a plan created by a higher-permission context.
- Changed entity version before confirm forces re-preview.
- Downstream business-rule rejection is surfaced accurately.

## Drift checks

Reject phase completion if:

- Model can call write tools directly.
- Confirmation re-runs intent parsing instead of using persisted args.
- Preview omits important arguments.
- Inferred defaults are hidden.
- Permission is checked only once.
- Write gate bypasses downstream validation.
- Multiple pending plans can coexist ambiguously.
- Audit chain is incomplete.

## Exit decision

Proceed only when all write-action safety fixtures pass.

## Rollback

Flip NL interface to read-only by suppressing write-capable tools. This is the safe degradation path.

---

# Gate 7 — Admin Tooling, Dashboards, and Tuning Controls

## Phase goal

Make the system operable, auditable, and tunable without uncontrolled drift.

## Required scope

- Add audited admin endpoints for `mcp_tool_permission`.
- Add dashboards over telemetry.
- Add alerts for safety and quality regressions.
- Move adaptive tuning from disabled to shadow, then controlled live only after approval.
- Update runbooks and documentation.

## Completeness gate

This phase is complete only when:

- Permission mappings are curatable at runtime by authorized admins.
- Admin changes are audited.
- Admin endpoints are permission-gated.
- Dashboard exists for:
  - routing decisions,
  - model-tier usage,
  - fallback usage,
  - tool-selection quality,
  - permission rejects,
  - RAG recall,
  - prompt-layer usage,
  - write confirmations,
  - write cancellations,
  - write expirations,
  - write failures,
  - latency p50/p95.

- Alerts exist for:
  - spike in failed tool calls,
  - spike in permission rejects,
  - fallback overuse,
  - write failure rate,
  - confirmation mismatch attempt,
  - retrieval regression,
  - latency SLO breach.

- Adaptive tuning runs in shadow mode before live promotion.
- Tuning promotion requires eval improvement or approved neutral result.

## Correctness tests

- Unauthorized user cannot access admin endpoints.
- Admin permission changes take effect after cache invalidation or TTL.
- Audit log records who changed what and when.
- Shadow tuning does not mutate live priority.
- Live tuning cannot promote if eval thresholds fail.
- Dashboard numbers reconcile with telemetry events.

## Drift checks

Reject phase completion if:

- Admin endpoints bypass audit.
- Permission edits are not TTL/cache-safe.
- Adaptive tuning can silently mutate priorities.
- Dashboards rely on ad hoc logs instead of structured telemetry.
- Runbooks are missing or stale.

## Exit decision

Proceed to production hardening only when runtime curation and observability are in place.

## Rollback

Disable admin endpoints and return adaptive tuning to shadow mode.

---

# Cross-Phase Drift Controls

These controls apply to every phase.

## 1. Scope lock

Each phase may only implement the items listed in its required scope unless a change request is approved.

A change request must include:

- reason for change,
- affected phase,
- affected tests,
- affected rollback strategy,
- updated exit criteria.

## 2. Regression lock

No phase may proceed if it causes unapproved regression in:

- tool-selection hit@5,
- tool-selection MRR,
- RAG recall@k,
- write safety tests,
- permission safety tests,
- blocking/streaming equivalence,
- p95 latency.

## 3. Permission lock

No phase may introduce:

- role-only authorization,
- model-only authorization,
- fail-open behavior,
- cross-tenant retrieval,
- direct model-generated SQL,
- ungated writes.

## 4. Prompt lock

Prompt changes must:

- be layered,
- be visible in telemetry,
- preserve anti-hallucination rules,
- preserve tool-before-answer behavior,
- distinguish confirmed facts from inference,
- avoid giving role personas access semantics.

## 5. Write lock

No write-capable behavior may be enabled unless:

- write tools are suppressed from direct model execution,
- preview is generated first,
- explicit confirmation is required,
- persisted args are executed exactly,
- permission is checked twice,
- audit chain is complete,
- rollback to read-only is available.

## 6. Retrieval lock

No RAG document may be accepted unless it has:

- deterministic ID,
- content hash,
- `rag-scope`,
- permission metadata,
- documented chunking strategy.

## 7. Model lock

No new model may become default unless documented with:

- tier,
- purpose,
- fallback behavior,
- latency impact,
- data-residency/cost implications,
- eval results.

---

# Phase Gate Review Template

Use this template at the end of every phase.

## Phase

`<phase name>`

## Scope completed

- [ ] Required item 1
- [ ] Required item 2
- [ ] Required item 3

## Metrics

| Metric                 | Baseline | Current | Pass/Fail |
| ---------------------- | -------: | ------: | --------- |
| Tool hit@5             |          |         |           |
| Tool MRR               |          |         |           |
| RAG recall@k           |          |         |           |
| Write safety pass rate |          |         |           |
| p95 latency            |          |         |           |

## Safety checks

- [ ] Permission boundaries preserved.
- [ ] No role-only authorization introduced.
- [ ] No ungated write path introduced.
- [ ] Blocking and streaming remain equivalent.
- [ ] Rollback path tested or documented.

## Drift checks

- [ ] No unapproved scope added.
- [ ] No required scope deferred silently.
- [ ] Documentation updated.
- [ ] Telemetry updated.
- [ ] Runbooks updated where applicable.

## Decision

- [ ] Pass.
- [ ] Pass with approved exception.
- [ ] Hold.
- [ ] Roll back.

## Exceptions

`<document approved exceptions, owner, and expiration date>`

## Next phase approval

`<approver / date / conditions>`
