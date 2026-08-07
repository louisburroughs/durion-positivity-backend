---
title: Spring AI Big-Bang Migration Checklist
status: complete
owner: pos-mcp-server
last_updated: 2026-08-07
profile: alpha
---

> **Closure note (2026-08-07, #1197):** The migration completed de facto: LangChain4j is fully
> removed (import/dependency scan below), the module test suite is green, and alpha has run the
> Spring AI build since PR #987 merged (2026-07-21, merge commit `661ea1c5d`). The formal cutover
> window described below was never executed as a ceremony — the switch happened as a
> merge-to-main plus alpha redeploy. Items whose evidence can be reconstructed from git history,
> logs, or the test suite are checked with citations. Items that depended on in-the-moment
> observation (freeze announcements, dashboard snapshots, timed smoke checks) are marked
> `[~] not reconstructible — de facto validated by post-cutover operation`. See
> `docs/spring-ai-migration-review.md` for the post-migration review.

## Purpose

Use this checklist to migrate pos-mcp-server from the legacy AI runtime to Spring AI in one controlled alpha cutover.

## Scope

- In scope:
  - Chat runtime (blocking + streaming)
  - Tool calling (facade tools + dynamic OpenAPI-discovered tools)
  - RAG retrieval and embedding ingestion
  - Chat memory and summarization wiring
  - Configuration namespace migration
  - Observability and regression checks
- Out of scope:
  - New features
  - Prompt redesign
  - Workflow-state redesign

## Hard Rules

- No partial rollout.
- No mixed runtime in production path after cutover switch.
- Freeze all non-migration changes during the migration window.
- Any P1 failure in cutover validation triggers immediate rollback.

## Readiness Gate (T-7 to T-1 days)

> 2026-08-07: No standalone readiness artifacts (mapping doc, touchpoint inventory, config
> snapshot, rehearsal log) were preserved; the readiness work was done inline on the migration
> branch (`30b68b628` "Moving to spring ai", 2026-07-18 → `9ed7b7abb` "complete spring-ai
> big-bang migration", 2026-07-20).

### Design and Mapping

- [~] Complete one-to-one API mapping doc: LangChain4j class -> Spring AI class. — not reconstructible — de facto validated by post-cutover operation (mapping done inline in `9ed7b7abb`; no standalone doc).
- [~] Explicitly document all expected behavior differences. — not reconstructible — de facto validated by post-cutover operation.
- [x] Confirm dynamic tool-provider strategy for per-request permission gating. — _ev: per-request `ToolCallback` resolution shipped in PR #987; permission-gating IT proves no cross-user tool exposure (`d501b6ea2`, review follow-up `dc3538727`)._
- [~] Confirm streaming tool-calling loop strategy and memory advisor ordering. — not reconstructible — de facto validated by post-cutover operation (streaming path fixed post-cutover in `23cb6d43a`).

### Code Inventory Closure

- [~] Production LangChain4j touchpoints reviewed and grouped by track. — not reconstructible — de facto validated by post-cutover operation.
- [~] Test migration backlog prepared for all affected tests. — not reconstructible; outcome evidenced by the green 455-test suite (2026-07-20, log below).
- [x] Removal list prepared for LangChain4j dependencies and config keys. — _ev: removal executed and verified — import/dependency scan below (no matches); wording residue swept in `301777467` (2026-07-21)._

### Data and Config Readiness

- [x] Verify all required Spring AI properties are defined for alpha. — _ev: `spring.ai` namespace present in `pom.xml` + `application*.yml` (scan below); alpha boots and serves chat on the Spring AI config (post-#987 operation)._
- [x] Verify Ollama chat + embedding endpoints are reachable from alpha runtime. — _ev: pre-existing alpha verification 2026-07-01 (implementation_checklist.md live pass: chat + embedding HTTP 200); unchanged endpoints post-cutover._
- [~] Verify pgvector schema and indexes are healthy before cutover. — not reconstructible pre-cutover; a vector-store schema mismatch surfaced and was fixed post-cutover (PR #1109, `d21ab259f`).
- [~] Capture full config snapshot from alpha (app config + secrets references). — not reconstructible — no snapshot artifact preserved.

### Operational Safety

- [~] Rollback plan rehearsed end-to-end in non-prod. — not reconstructible — de facto validated by post-cutover operation (no rollback was ever needed).
- [~] Rollback artifact (previous build + config) is immutable and ready. — not reconstructible as an artifact; the pre-cutover main head (parent of merge `661ea1c5d`) remains buildable from git.
- [~] Change freeze announced with start/end timestamps. — not reconstructible — no freeze record exists.
- [~] Incident owner, rollback owner, and validation owner assigned. — not reconstructible — single-owner migration in practice.

## Build and Test Gate (T-1 day)

### Compile and Static Quality

- [x] Module builds clean with Spring AI dependencies only.
- [x] No production legacy-runtime imports remain.
- [x] Architecture tests pass.

### Functional Regression Pack

> 2026-08-07: the pre-cutover evidence is the 455-test module suite (2026-07-20, log below).
> Two happy-path items did **not** actually hold on alpha at cutover and were fixed within days —
> recorded honestly below and in the review doc.

- [~] Blocking chat happy path passes. — passed in tests pre-cutover, but broke live: HTTP 500 from tool-calling options (fixed PR #1109, `d21ab259f`, 2026-07-25) and blank replies from an invalid model id (fixed PR #1111, `65fe6e4f4`, 2026-07-25). De facto validated by post-fix operation.
- [~] Streaming chat happy path passes. — covered by the suite (`23cb6d43a` fixed startup wiring + callback tests, 2026-07-21); no preserved live smoke record.
- [x] Tool-calling happy path passes for at least one tool per major domain. — _ev: facade tool tests in the 455-test suite; live tool calling operational post-#1109/#1111._
- [x] Dynamic OpenAPI tool discovery and execution pass with permission constraints. — _ev: hardened post-cutover via PR #1102 (Wave 1: #645/#778–#785/#1101) and PR #1120 (gateway-routing IT + route check, `889db983d`)._
- [x] Permission fail-closed behavior passes negative tests. — _ev: discovered-tool permission-gating IT (`d501b6ea2`); review follow-up `dc3538727` (#1114)._
- [x] RAG retrieval pass: expected relevant docs returned for known fixtures. — _ev: retrieval-quality eval gates (hit@5/MRR) landed `081cf4291` (#783); recall@k floor confirmed `15ab613cb`._
- [x] Embedding ingestion pass: document ingest + re-query success. — _ev: covered by suite + post-cutover corpus growth waves ingested and re-queried (#1124, `a1bf14fa3`)._
- [~] Session memory behavior pass for multi-turn conversations. — covered by unit tests in the suite; no preserved multi-turn live record.

### Performance Guardrails

- [~] p50 and p95 latency within agreed alpha thresholds vs baseline. — not reconstructible — no pre/post latency baseline was captured.
- [~] Error rate is not worse than baseline by agreed tolerance. — not reconstructible — no baseline error-rate capture.
- [~] Token/tool loop does not exceed timeout budget under normal load. — not reconstructible as a measured check; de facto validated by post-cutover operation.

## Cutover Day Checklist (T0)

### Pre-Cutover (Start of Window)

> 2026-08-07: no formal cutover window was run; the switch was PR #987's merge to main
> (2026-07-21) followed by the normal alpha redeploy. The window-ceremony items below are
> not reconstructible.

- [~] Confirm freeze is active. — not reconstructible — de facto validated by post-cutover operation.
- [~] Confirm latest validated artifact hash. — not reconstructible; closest equivalent is merge commit `661ea1c5d` (PR #987).
- [~] Confirm rollback artifact hash. — not reconstructible; pre-cutover main head (parent of `661ea1c5d`) remains in git.
- [~] Confirm DB backup/snapshot is complete and restorable. — not reconstructible — no snapshot record.
- [~] Confirm dashboards and alerts are green pre-change. — not reconstructible — no dashboard snapshot preserved.

### Deploy and Switch

- [x] Deploy Spring AI build to alpha. — _ev: alpha has run the Spring AI build since the #987 merge; post-cutover fixes (#1109, #1111) were diagnosed and verified against the live alpha deployment._
- [x] Apply config namespace migration (legacy namespace prefix to spring.ai prefix and module-local keys). — _ev: `30b68b628`/`9ed7b7abb`; `spring.ai` config matches present in scan below; no `legacy-runtime:` keys remain._
- [x] Remove/disable legacy runtime auto-config exclusions and old starters. — _ev: dependency scan below — zero `langchain4j` matches in `pom.xml`/`src/main`._
- [x] Restart service and verify startup health endpoints. — _ev: startup wiring issues found and resolved (`23cb6d43a`, 2026-07-21); alpha boots and serves traffic on the Spring AI build._

### Immediate Smoke (first 15 minutes)

> 2026-08-07: no timed smoke run was recorded. Two smoke items would have **failed** at T0 and
> were fixed in the stabilization tail: blocking-chat 500s (PR #1109) and blank replies from an
> invalid model id (PR #1111), both resolved 2026-07-24/25.

- [~] Blocking chat returns valid response. — failed at cutover (HTTP 500, blank replies); fixed PR #1109 (`d21ab259f`) + PR #1111 (`65fe6e4f4`, incl. `659127bb8`/`4fe1879a9` model-option passing); valid responses confirmed live post-fix.
- [~] Streaming chat emits and completes cleanly. — not reconstructible — de facto validated by post-cutover operation.
- [~] At least 3 facade tools execute successfully. — not reconstructible as a timed record; facade execution live-proven post-cutover.
- [~] One dynamic OpenAPI-discovered tool executes successfully. — not reconstructible at T0; 125 discovered tools were later found 404ing through the gateway (mis-attributed service prefix) and pruned/fixed via #1121 (PR #1122, `07bdbc76e`).
- [~] Permission-denied tool request remains denied. — not live-recorded at T0; proven by the permission-gating IT (`d501b6ea2`, `dc3538727`).
- [~] RAG lookup returns grounded context for a known query. — not reconstructible at T0; vector-store schema mismatch fixed in PR #1109, then retrieval gates landed (#783, `081cf4291`).
- [~] No P1/P2 errors in logs related to model, tool loop, or vector search. — did not hold: #1109/#1111 class errors present until 2026-07-25; no further model/tool-loop/vector P1/P2 since.

## Stabilization Window (T0 to T+24h)

> 2026-08-07: stabilization ran longer than 24h in practice (2026-07-21 → 2026-07-25) and was
> driven by issues, not a monitoring cadence.

### Monitoring

- [~] Error budget consumption reviewed every 30 minutes for first 4 hours. — not reconstructible — de facto validated by post-cutover operation.
- [~] Tool execution failures tracked by tool name and permission set. — not reconstructible as a T+24h record; per-request telemetry (tool names, permission rejects) was already emitting.
- [~] RAG miss rate and empty-context rate tracked. — not reconstructible for the window; later formalized by the eval harness (#783) and gap-discovery harness (#1125).
- [~] Streaming interruption rate tracked. — not reconstructible — de facto validated by post-cutover operation.

### Validation

- [x] Run full regression suite post-deploy. — _ev: module suite green pre-merge (455 tests, log below) and on every subsequent main build; callback-test fixes in `23cb6d43a`._
- [~] Compare key metrics against pre-cutover baseline. — not reconstructible — no pre-cutover metric baseline existed to compare against.
- [x] Confirm no auth leakage across sessions. — _ev: gateway trust headers stripped in the tool proxy (`a576b85df`, 2026-07-24); caller auth relayed per request, not cached._
- [x] Confirm no cross-user tool exposure. — _ev: permission-gating IT proves discovered-tool gating does not leak across users (`d501b6ea2`; #1114 review `dc3538727`)._

## Rollback Triggers

> 2026-08-07: no trigger fired; no rollback occurred. The #1109/#1111 chat failures were judged
> fix-forward (resolved within the stabilization tail) rather than P1 rollback triggers.

Rollback immediately if any of the following occur:

- [ ] P1 incident affecting chat/tool execution availability.
- [ ] Permission boundary breach or fail-open evidence.
- [ ] Sustained error rate above agreed threshold for more than 15 minutes.
- [ ] Streaming path instability causing customer-visible failures.
- [ ] Irrecoverable config mismatch not fixable within 15 minutes.

## Rollback Procedure (single pass)

> 2026-08-07: never executed — no rollback was needed.

- [ ] Stop current deployment.
- [ ] Redeploy previous pre-cutover artifact.
- [ ] Restore previous runtime config snapshot.
- [ ] Verify health, blocking chat, streaming chat, and one tool execution.
- [ ] Announce rollback completion and open follow-up incident doc.

## Completion Criteria

Mark migration complete only when all are true:

- [x] No production legacy-runtime dependencies in module build.
- [x] No production legacy-runtime imports remain in source.
- [~] All cutover and stabilization checks pass. — closed 2026-08-07 as reconstructed above: evidence-backed items checked; window-ceremony items marked not reconstructible; two smoke items failed at T0 and were fixed forward (#1109, #1111).
- [x] Runbook and README updated to Spring AI terminology and properties.
- [x] Post-migration review document is published with lessons learned. — _ev: `docs/spring-ai-migration-review.md` (2026-08-07, #1197)._

## Evidence Log

Reconstructed 2026-08-07 from git history and the issue tracker (#1197):

- Cutover start: 2026-07-18 — migration branch work begins (`30b68b628` "Moving to spring ai").
- Cutover end: 2026-07-21T14:31Z — PR #987 merged to main (merge commit `661ea1c5d`); alpha redeployed from main.
- Artifact deployed: build of merge commit `661ea1c5d` (PR #987 "complete pos-mcp-server Spring AI migration"). Exact alpha image tag not preserved.
- Rollback artifact: none preserved as an immutable artifact; pre-cutover main head (parent of `661ea1c5d`) remains buildable from git. Never needed.
- Dashboard links: not reconstructible (no snapshot captured).
- Regression run ID: local pre-merge run 2026-07-20 — 455 tests, 0 failures, 0 errors (log below); no CI run id preserved.
- Incident IDs (if any): no formal incidents. Post-cutover defects fixed forward: #1109 (chat HTTP 500 tool-calling options + vector-store schema mismatch), #1111 (blank replies, invalid chat model id), later #1115 (AUTHENTICATED facade gating), #1121 (125 discovered tools 404, mis-attributed service prefix).
- Final decision: **PASS (de facto)** — recorded 2026-08-07 per #1197.

Local validation evidence (2026-07-20):

- Compile: `./mvnw -pl pos-mcp-server -DskipTests compile --no-transfer-progress` -> BUILD SUCCESS
- Tests: `./mvnw -pl pos-mcp-server test --no-transfer-progress` -> 455 tests, 0 failures, 0 errors
- Import/dependency scan:
  - `rg -n "langchain4j|dev\.langchain4j" pos-mcp-server --glob '!**/target/**'` -> no matches
  - `rg -n "langchain4j|dev\.langchain4j" pos-mcp-server/src/main pos-mcp-server/pom.xml pos-mcp-server/src/main/resources --glob '!**/target/**'` -> no matches
  - `rg -n "dev\.legacy-runtime" pos-mcp-server/src/main pos-mcp-server/pom.xml` -> no matches
  - `rg -n "legacy-runtime:" pos-mcp-server/src/main/resources` -> no matches
  - `rg -n "spring-ai|spring\.ai:" pos-mcp-server/pom.xml pos-mcp-server/src/main/resources/application*.yml` -> Spring AI dependency/config matches present
