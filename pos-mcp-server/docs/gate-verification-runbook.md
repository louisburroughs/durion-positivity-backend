# Gate Verification Runbook — pos-mcp-server NL interface

How to build and verify each phase gate **locally**. Two tiers:

- **A. Offline checks** — build + unit/structural tests. No Ollama, no Postgres. These are what's been verified so far and run in CI.
- **B. Live checks** — baseline metrics, fail-closed, persona behavior. Need a model backend + pgvector/Postgres. Deferred batch (path 2).

All commands run from the **worktree root** unless noted:
```
cd ~/IdeaProjects/durion-positivity-backend/.worktrees/nl-interface
```
Branch: `feat/nl-interface-gates`. Toolchain: JDK 25 (`java -version` → 25.x), Maven wrapper `./mvnw`.

---

## 0. One-time build (required before any test run)

A clean reactor build must run once so upstream modules are installed and annotation processing (Lombok) runs. **Do not** use bare `-am compile` from a clean worktree — use `install`:

```bash
./mvnw clean install -am -pl pos-mcp-server -DskipTests
```
Expected: `BUILD SUCCESS`. (A bare `compile` on a never-built worktree fails on Lombok getters — that is the build invocation, not a code bug.)

After this, incremental offline runs work:
```bash
./mvnw -o -pl pos-mcp-server <goal>
```
> **Lombok + JDK 25 gotcha:** a clean module recompile must go through the reactor with `-am`
> (`./mvnw -o -pl pos-mcp-server -am clean install -DskipTests`). A single broken source file aborts
> Lombok for the whole module, surfacing as misleading "cannot find symbol: getX()" on *unrelated*
> `@Data` entities (`NltiIntent`, `LlmApiConfig`). If you see those, find the **first** compile error
> in a changed file — that's the real cause, not the entity.

---

## A. Offline gate checks (no backend)

### Run every implemented gate's tests in one shot
```bash
./mvnw -o -pl pos-mcp-server test \
  -Dtest='Eval*,RolePromptAssemblyTest,MasterAgentRegistryLoaderTest,PermissionGatingInvariantTest,WorkflowStateTest,ObservabilityTimerSmokeTest,OpenApiToolProviderTest,RequestScopedUserContextTest,TierSelectorTest,NltiRouterTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `Tests run: 33, Failures: 0, Errors: 0, Skipped: 1` → `BUILD SUCCESS`.
(The 1 skipped is the Gate 0 fixture-minimum exit gate — see Gate 0 below.)

### Format check (drift guard — must be clean before commit)
```bash
./mvnw -o -pl pos-mcp-server spotless:check     # or spotless:apply to fix
```

### Per-gate breakdown

| Gate | Command | Expected |
|---|---|---|
| **0** Telemetry + eval harness | `./mvnw -o -pl pos-mcp-server test -Dtest='Eval*' -Dsurefire.failIfNoSpecifiedTests=false` | 9 run, 1 skipped, 0 failed |
| **1** Role-first layered prompts | `./mvnw -o -pl pos-mcp-server test -Dtest='RolePromptAssemblyTest' -Dsurefire.failIfNoSpecifiedTests=false` | 5 run, 0 failed |
| **2A** Shared path + preload | (covered by `RolePromptAssemblyTest#preloadCoverage`) | included above |
| **2B** Permission-gating cleanup | `./mvnw -o -pl pos-mcp-server test -Dtest='PermissionGatingInvariantTest,MasterAgentRegistryLoaderTest' -Dsurefire.failIfNoSpecifiedTests=false` | 3 run, 0 failed |

### Gate 0 config-hygiene assertions (no test — verify by inspection)
```bash
# tuning disabled by default
grep -A2 'tuning:' pos-mcp-server/src/main/resources/application.yml      # enabled: ${MCP_TUNING_ENABLED:false}
# single documented default model (yml and README agree)
grep 'OLLAMA_CHAT_MODEL' pos-mcp-server/src/main/resources/application.yml
grep 'chat-model.model-name' pos-mcp-server/README.md
```

### Gate 0 fixture-minimum exit gate (currently @Disabled)
Counts are seed-only (4/4/4 vs required 100/50/30). To check current counts:
```bash
for d in tool-selection rag-retrieval write-safety; do
  echo -n "$d: "
  python3 -c "import json,glob,sys;print(sum(len(json.load(open(f))['fixtures']) for f in glob.glob('pos-mcp-server/src/test/resources/eval/'+sys.argv[1]+'/*.json')))" "$d"
done
# current: tool-selection: 4 / rag-retrieval: 4 / write-safety: 4
```
When suites reach 100/50/30, remove the `@Disabled` on `EvalFixtureValidationTest#minimumFixtureCountsMet` and it must pass.

---

## B. Live gate checks (need backend) — deferred batch

These cover the HOLD items on Gates 0–2B: baseline metrics, RAG recall, fail-closed permission behavior, persona differences, and the V19/V17 migration.

### B.1 Backend env vars
```bash
export OLLAMA_CHAT_BASE_URL=https://ollama.com
export OLLAMA_CHAT_MODEL=qwen3.5:cloud
export OLLAMA_EMBEDDING_BASE_URL=http://ollama:11434     # internal host — needs tunnel/DNS
export OLLAMA_EMBEDDING_MODEL=nomic-embed-text
export OLLAMA_API_KEY=********                            # DO NOT commit; rotate after use
export MCP_DB_HOST=... MCP_DB_PORT=... MCP_DB_NAME=... MCP_DB_USER=... MCP_DB_PASSWORD=...  # via tunnel
```
> **Security:** keep the API key in the shell/secret store only. Never commit it; rotate if it has been pasted anywhere.
>
> **Embeddings (verified 2026-06-30):** the `ollama.com` cloud key is **chat-only** — `/api/embed` returns unauthorized. Point `OLLAMA_EMBEDDING_BASE_URL` at a **local/embedding-capable Ollama** running `nomic-embed-text` (not the internal `ollama:11434` and not the cloud chat endpoint). A 1-container local stack (`pgvector/pgvector:pg16` + local `ollama`) is enough to boot, apply all migrations (incl V19–V22), and embed the 13 preload docs.

### B.2 Chat backend reachability smoke test (verified working)
```bash
curl -sS --max-time 60 https://ollama.com/api/chat \
  -H "Authorization: Bearer ${OLLAMA_API_KEY}" -H 'Content-Type: application/json' \
  -d '{"model":"qwen3.5:cloud","stream":false,"messages":[{"role":"user","content":"Reply with: pong"}]}'
```
Expect HTTP 200 + `"content":"pong"`. (Observed ~28s — qwen3.5:cloud is a reasoning model; too slow for the Gate 4 T1 router, by design use a small model there.)

### B.3 Run the service against the live stack
```bash
./mvnw -o -pl pos-mcp-server spring-boot:run -Dspring-boot.run.profiles=alpha
```
Requires Postgres+pgvector reachable (tunnel) and the embedding host resolvable. On boot, Flyway applies migrations including **V19** (legacy-table rename) and **V20** (`nlti_session.workflow_state`).

> **Boot dependency (fixed):** context init requires `org.latencyutils:LatencyUtils` (Micrometer
> `AbstractTimer` → `org.LatencyUtils.PauseDetector`). It is now declared in `pos-mcp-server/pom.xml`
> (Boot 4.1 BOM does not manage it). Guarded by `ObservabilityTimerSmokeTest`. Without it, every
> Timer bean in `NltiObservabilityMetricsConfig` fails and the whole context (any live boot) is blocked.

> **Before first boot against a shared DB:** snapshot it. V19/V17 are reversible (`RENAME TO *_deprecated`, `IF EXISTS`) but take the snapshot anyway per the gate rollback policy.

### B.4 Baseline capture (Gate 0)
The driver is built: `BaselineCaptureIT` runs the tool-selection fixtures through the real selector
(`ToolRegistryService.resolveCandidateTools`), scores hit@5 / MRR via `EvalMetrics`, and writes
`target/eval/baseline-tool-selection.json`. Live-gated (won't run offline). With the stack up:
```bash
./mvnw -o -pl pos-mcp-server test -Dtest=BaselineCaptureIT \
    -Dmcp.eval.live=true -Dspring.profiles.active=alpha
# -> target/eval/baseline-tool-selection.json ; hard-fails on any forbidden-tool selection
```
Copy the numbers into `src/test/resources/eval/baseline.json`. Still manual / follow-up:
- RAG recall@k — needs the retriever + the doc-id metadata key (TODO in `BaselineCaptureIT`).
- latency p50/p95 by tier.

### B.5 Fail-closed spot check (Gate 2B HOLD)
With the service up, confirm a caller lacking a tool's permission never receives it, and `mcp_role`/`mcp_tool_role` are gone:
```bash
# tables renamed (no live query should reference the originals)
psql "$MCP_DB_URL" -c "\dt mcp_role*"        # expect only *_deprecated
psql "$MCP_DB_URL" -c "\dt mcp_tool_role*"   # expect only *_deprecated
```

### B.6 OpenAPI execution bridge (Gate 3)
**Implemented (committed, unit-tested):** `RequestScopedUserContext`, `DiscoveredOperation` +
`findDiscoveredCandidatesForPermissions`, exec-coordinate columns (V21/V19), `OpenApiOperationExecutor`,
`OpenApiToolProvider` (fail-closed, `isDynamic`). **Remaining before this section runs:**
(1) **Persist discovered ops to `mcp_tool`** — corrected finding: `ToolRegistrationServiceImpl` only registers ops with the in-memory `McpAsyncServer`; **no `source='openapi'` rows exist in the DB today**. Add row persistence (name/domain/description/`source='openapi'`/`http_method`/`http_path`/`service_id`/`input_schema` + an **embedding** via the model) AND seed `mcp_tool_permission` for them (else fail-closed → never selected). Surface method/path/serviceId from `OpenApiToolMapper`. Coord columns V21/V19 are ready.
(2) wire `.toolProvider(openApiToolProvider)` into both managers + `RequestScopedUserContext` set/clear around the blocking `agent.chat(...)`; (3) streaming Reactor-context propagation (until then streaming stays fail-closed → no discovered tools). See `gate3-openapi-bridge-design.md` G3.1/G3.3.

Requires the gateway aggregate spec reachable + discovered ops persisted with coords + permissions (step 1 above). With the service up (`alpha`):
```bash
# PRECHECK — discovered ops must exist in the DB at all (empty today until step 1 is implemented):
psql "$MCP_DB_URL" -c "SELECT count(*) FROM mcp_tool WHERE source='openapi';"
# and carry execution coordinates + permission rows:
psql "$MCP_DB_URL" -c "SELECT name, http_method, http_path FROM mcp_tool WHERE source='openapi' LIMIT 5;"
psql "$MCP_DB_URL" -c "SELECT t.name FROM mcp_tool t JOIN mcp_tool_permission p ON p.tool_id=t.id WHERE t.source='openapi' LIMIT 5;"
```
- **End-to-end (positive):** as a user holding the op's permission, send a `/v1/mcp/chat` message that should trigger a `source='openapi'` op **with no facade equivalent**; confirm a real gateway result (not a hallucinated success).
- **Negative (leakage/permission):** as a user **lacking** that permission, confirm the op never appears in the agent's tools and cannot be executed — including reusing a cached agent that a higher-permission user just used (cached-agent leakage check).
- **Streaming parity:** repeat both via `/v1/mcp/chat/stream` (separate Reactor-context propagation path — must enforce the negative case too).
- **Facade regression:** confirm existing facade tools still work.
- **Workflow gating (Gate 2C live):** seed `mcp_tool_workflow` for a non-IDLE state, set `NltiSession.workflow_state`, confirm that state's tools activate and IDLE-only tools do not.

### B.7 Tiered model router (Gate 4)
**Implemented (committed, unit-tested):** `ModelTier`, `RequestComplexity`, `RouterClassification`
(+ safe default), `TierSelector` (pure rule), `NltiRouter` (`@Profile alpha`, strict-JSON parse +
safe default → T2-complex). **Remaining before this section runs:** (1) tier→model resolver with
`mcp.model.router/simple/complex` beans; (2) wire the router into both managers (cache key gains the
tier; per-request model selection) + shared T0; (3) telemetry emission. See `gate4-tiered-router-design.md`.

Requires the T1/T2-simple/T2-complex models configured + reachable.
- **Routing split:** run the tool-selection fixtures; confirm ≥80% of `single-lookup` route to T2-simple and 100% of write/accounting/tax/admin/security route to T2-complex (read `routing.tier` from telemetry).
- **Safe fallback:** force malformed router JSON (e.g. point the router at a non-JSON model) → request still completes via the T2-complex safe default; logged.
- **Quality/latency:** answer-quality eval ≥ Gate 1 baseline; p95 within the soft SLO.
- **Fallback vs tier:** trigger `mcp.model.fallback` and confirm telemetry shows `fallback_used=true` independent of `routing.tier`.

### B.8 RAG expansion, permission-aware filtering, hybrid retrieval (Gate 5 — after implementation per `gate5-rag-hybrid-design.md`)
Requires pgvector + the embedding model; run the RAG-retrieval fixtures through the #783 harness.
- **Recall:** exact WO/invoice/PO/VIN/SKU/account-code/claim fixtures improve recall@k; record dense-only vs hybrid side by side.
- **Visibility (permission-first):** admin/security docs never returned to non-admin/non-security fixtures; a permission-elevated user retrieves per permissions, not nominal role.
- **Embedding migration:** with `bge-m3`/1024 active, recall@k ≥ the 768 baseline before switching; 768 column retained until validated.
- **Chunking:** glossary/identifier docs use small chunks; prose/playbooks larger.
- Doc hygiene: every preload/ingested doc has deterministic id, content hash, `rag_scope`, `required_permissions`.

### B.9 Write-action confirmation gate (Gate 6 — after implementation per `gate6-write-confirmation-design.md`)
Run the `write-safety` fixtures against the live NLTI-session path (`/v1/nlt/requests` + `/confirm`).
The exit criterion is **all write-safety fixtures pass**:
- No mutation without an explicit `/confirm`; plan args == executed args (no re-parse on confirm).
- Expired plan (past `mcp.nlti.write.plan-ttl`) does not execute; idempotent re-confirm does not double-write.
- Material argument change cancels + re-previews; missing required arg → clarification, not a guess.
- Inferred-default args disclosed in the preview; HIGH-risk inferred defaults rejected / require explicit selection.
- Lower-permission caller cannot execute a higher-permission plan (permission re-checked at confirm).
- Changed source entity version since plan → forced re-preview (risk ≥ MEDIUM).
- Downstream business-rule rejection surfaced accurately (not swallowed/faked).
- Audit chain present: PLAN → CONFIRMATION → EXECUTION_STEP → EXECUTION_COMPLETE/FAILED.
- Rollback check: with write tools suppressed, the interface is read-only.

### B.10 Admin tooling, dashboards, tuning controls (Gate 7 — after implementation per `gate7-admin-observability-design.md`)
- **Admin RBAC:** unauthorized user → 403 on `/v1/mcp/admin/tool-permissions`; authorized admin can list/add/remove a `(tool, permission_code)` mapping.
- **Cache-safe:** a mapping change takes effect after the agent-cache TTL / explicit invalidation (not mid-request).
- **Audit:** the change is recorded (who / what / old→new / when) via `AuditLedgerService`.
- **Dashboards:** panels (routing, tier usage, fallback, tool-selection quality, permission rejects, RAG recall, prompt-layer usage, write confirmations/cancellations/expirations/failures, p50/p95) reconcile with `nlti.request.telemetry` + Prometheus meters.
- **Alerts** fire for: failed-tool-call spike, permission-reject spike, fallback overuse, write-failure rate, confirmation-mismatch attempt, retrieval regression, latency SLO breach.
- **Tuning:** `mcp.tuning.mode=shadow` computes but does NOT mutate `mcp_tool.priority`; `=live` cannot promote shadow→live unless the #783 harness shows improvement (or approved neutral).

---

## C. Full module test suite (optional, broader)
```bash
./mvnw -o -pl pos-mcp-server test
```
Note: some pre-existing tests may require the `alpha` profile / backend; the gate tests in section A are the curated offline set.

---

## Quick reference

| I want to… | Command |
|---|---|
| Build once | `./mvnw clean install -am -pl pos-mcp-server -DskipTests` |
| Verify all implemented gates (offline) | `./mvnw -o -pl pos-mcp-server test -Dtest='Eval*,RolePromptAssemblyTest,MasterAgentRegistryLoaderTest,PermissionGatingInvariantTest' -Dsurefire.failIfNoSpecifiedTests=false` |
| Check formatting | `./mvnw -o -pl pos-mcp-server spotless:check` |
| Smoke-test chat backend | curl in B.2 |
| Run service (live) | `./mvnw -o -pl pos-mcp-server spring-boot:run -Dspring-boot.run.profiles=alpha` |

Gate status + sign-offs live in [`implementation_checklist.md`](implementation_checklist.md). Gate definitions in [`implementation_phase_gates.md`](implementation_phase_gates.md).
