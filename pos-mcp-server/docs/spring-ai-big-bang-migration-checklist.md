---
title: Spring AI Big-Bang Migration Checklist
status: draft
owner: pos-mcp-server
last_updated: 2026-07-17
profile: alpha
---

## Purpose

Use this checklist to migrate pos-mcp-server from LangChain4j to Spring AI in one controlled alpha cutover.

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

### Design and Mapping

- [ ] Complete one-to-one API mapping doc: LangChain4j class -> Spring AI class.
- [ ] Explicitly document all expected behavior differences.
- [ ] Confirm dynamic tool-provider strategy for per-request permission gating.
- [ ] Confirm streaming tool-calling loop strategy and memory advisor ordering.

### Code Inventory Closure

- [ ] Production LangChain4j touchpoints reviewed and grouped by track.
- [ ] Test migration backlog prepared for all affected tests.
- [ ] Removal list prepared for LangChain4j dependencies and config keys.

### Data and Config Readiness

- [ ] Verify all required Spring AI properties are defined for alpha.
- [ ] Verify Ollama chat + embedding endpoints are reachable from alpha runtime.
- [ ] Verify pgvector schema and indexes are healthy before cutover.
- [ ] Capture full config snapshot from alpha (app config + secrets references).

### Operational Safety

- [ ] Rollback plan rehearsed end-to-end in non-prod.
- [ ] Rollback artifact (previous build + config) is immutable and ready.
- [ ] Change freeze announced with start/end timestamps.
- [ ] Incident owner, rollback owner, and validation owner assigned.

## Build and Test Gate (T-1 day)

### Compile and Static Quality

- [ ] Module builds clean with Spring AI dependencies only.
- [ ] No production imports from dev.langchain4j remain.
- [ ] Architecture tests pass.

### Functional Regression Pack

- [ ] Blocking chat happy path passes.
- [ ] Streaming chat happy path passes.
- [ ] Tool-calling happy path passes for at least one tool per major domain.
- [ ] Dynamic OpenAPI tool discovery and execution pass with permission constraints.
- [ ] Permission fail-closed behavior passes negative tests.
- [ ] RAG retrieval pass: expected relevant docs returned for known fixtures.
- [ ] Embedding ingestion pass: document ingest + re-query success.
- [ ] Session memory behavior pass for multi-turn conversations.

### Performance Guardrails

- [ ] p50 and p95 latency within agreed alpha thresholds vs baseline.
- [ ] Error rate is not worse than baseline by agreed tolerance.
- [ ] Token/tool loop does not exceed timeout budget under normal load.

## Cutover Day Checklist (T0)

### Pre-Cutover (Start of Window)

- [ ] Confirm freeze is active.
- [ ] Confirm latest validated artifact hash.
- [ ] Confirm rollback artifact hash.
- [ ] Confirm DB backup/snapshot is complete and restorable.
- [ ] Confirm dashboards and alerts are green pre-change.

### Deploy and Switch

- [ ] Deploy Spring AI build to alpha.
- [ ] Apply config namespace migration (langchain4j prefix to spring.ai prefix and module-local keys).
- [ ] Remove/disable LangChain4j auto-config exclusions and old starters.
- [ ] Restart service and verify startup health endpoints.

### Immediate Smoke (first 15 minutes)

- [ ] Blocking chat returns valid response.
- [ ] Streaming chat emits and completes cleanly.
- [ ] At least 3 facade tools execute successfully.
- [ ] One dynamic OpenAPI-discovered tool executes successfully.
- [ ] Permission-denied tool request remains denied.
- [ ] RAG lookup returns grounded context for a known query.
- [ ] No P1/P2 errors in logs related to model, tool loop, or vector search.

## Stabilization Window (T0 to T+24h)

### Monitoring

- [ ] Error budget consumption reviewed every 30 minutes for first 4 hours.
- [ ] Tool execution failures tracked by tool name and permission set.
- [ ] RAG miss rate and empty-context rate tracked.
- [ ] Streaming interruption rate tracked.

### Validation

- [ ] Run full regression suite post-deploy.
- [ ] Compare key metrics against pre-cutover baseline.
- [ ] Confirm no auth leakage across sessions.
- [ ] Confirm no cross-user tool exposure.

## Rollback Triggers

Rollback immediately if any of the following occur:

- [ ] P1 incident affecting chat/tool execution availability.
- [ ] Permission boundary breach or fail-open evidence.
- [ ] Sustained error rate above agreed threshold for more than 15 minutes.
- [ ] Streaming path instability causing customer-visible failures.
- [ ] Irrecoverable config mismatch not fixable within 15 minutes.

## Rollback Procedure (single pass)

- [ ] Stop current deployment.
- [ ] Redeploy previous LangChain4j artifact.
- [ ] Restore previous runtime config snapshot.
- [ ] Verify health, blocking chat, streaming chat, and one tool execution.
- [ ] Announce rollback completion and open follow-up incident doc.

## Completion Criteria

Mark migration complete only when all are true:

- [ ] No production LangChain4j dependencies in module build.
- [ ] No production LangChain4j imports remain in source.
- [ ] All cutover and stabilization checks pass.
- [ ] Runbook and README updated to Spring AI terminology and properties.
- [ ] Post-migration review document is published with lessons learned.

## Evidence Log

- Cutover start:
- Cutover end:
- Artifact deployed:
- Rollback artifact:
- Dashboard links:
- Regression run ID:
- Incident IDs (if any):
- Final decision: PASS or ROLLBACK
