# Gate Close-Out Execution Plan (#1212–#1219)

> **Purpose:** Execution plan for closing the eight open HOLD gates recorded in
> `implementation_checklist.md` (Gate 0 through Gate 7). Companion to that file — this document
> tracks *how* the close-out work is sequenced and staffed; the checklist remains the source of
> truth for gate status, evidence, and sign-off.
>
> **Created:** 2026-08-18. **Tracks:** #1212, #1213, #1214, #1215, #1216, #1217, #1218, #1219,
> plus two build-gap stories discovered while planning: #1367, #1368.

## Execution status (2026-08-19)

| Wave | Status | Record |
|---|---|---|
| 0 | **DONE** | PR #1370 (merged) — closed #1367, #1368 |
| 1 | **DONE** | PRs #1371, #1382, #1385–#1390 — gates 0/1/2A/2C/5/6 all carry dated live evidence in `implementation_checklist.md` |
| 2 | **DONE** | PRs #1391–#1393 — Gate 4 router evidence (7/7, p95 5653 ms) |
| 3 | **IN PROGRESS** | shadow soak started 2026-08-19 ~16:50 UTC (PR #1394 + manual compose sync); 3 nights, then one gated live promotion (~2026-08-22 UTC), then revert |
| 4 | pending | single HOLD→Pass PR; blocked on decision G and the Gate 5 `security.guide` policy call |

Standing findings feeding sign-off (each documented in the relevant gate block / PR):

- the NLTI submit path emits no request telemetry (→ #1397)
- `IntentParserServiceImpl:40` makes the write gate unreachable for create/update phrasings (→ #1398)
- `model.fallbackUsed` is hardcoded false with no failover path in code (folded into #1397's scope)
- the gateway api-docs aggregation excludes pos-mcp-server, and 12 of 23 aggregated services
  produce no discovered tools
- shop-manager's `ext_vehicle` replica is empty on alpha (appointment creation impossible via API)
- `security-service_deleterole` / `_deleterole_1` discovered-tool name collision
- CI's Detect Changed Services skips `docker-compose.yml` changes (two silent non-deploys)
- `security.guide` RAG visibility policy call (→ #1396, blocks Gate 5 Pass)

## Issue map

| Issue | Gate | One-line scope |
|---|---|---|
| #1212 | 0 | Fixture minimum counts + alpha `BaselineCaptureIT` + baseline metrics table |
| #1213 | 1 | Prompt-layer telemetry emission + live answer-quality eval |
| #1214 | 2A | Live blocking-vs-streaming equivalence + metrics fill |
| #1215 | 2C | Non-IDLE workflow activation live (`mcp_tool_workflow` seed + session propagation) |
| #1216 | 4 | Live routing-mix, latency, and quality metrics for the tiered router |
| #1217 | 5 | Retrieval-lock sweep over the 39-doc corpus |
| #1218 | 6 | Live write-gate full-flow verification on alpha |
| #1219 | 7 | Live admin-flow, dashboard, and shadow→live tuning verification (final gate) |
| #1367 | (build gap) | Live HTTP verification harness — dependency for #1213/#1214/#1215/#1216/#1218/#1219 |
| #1368 | (build gap) | Gate 7 NLTI Grafana dashboard build-out — dependency for #1219 |

## Findings that changed the plan

Offline checks against `main` before planning turned up three issues whose stated blocker is
already resolved in code — the remaining work is recording evidence, not building anything:

- **#1212 item 1** ("fixtures at seed level, `@Disabled minimumFixtureCountsMet`") is stale.
  `./mvnw -pl pos-mcp-server -Dtest=EvalFixtureValidationTest test` → 6 run, 0 skipped, BUILD
  SUCCESS. Counts: tool-selection 105 (≥100), rag-retrieval 60 (≥50), write-safety 34 (≥30).
- **#1213 item 1** ("prompt layers not written into telemetry") is stale. Both managers emit it —
  `SessionAgentManager.java:284`, `StreamingSessionAgentManager.java:240,278` →
  `NltiRequestTelemetryFactory` → `NltiRequestTelemetry.Rag.promptLayers`.
- **#1215 item 1** ("seed `mcp_tool_workflow` with a non-IDLE mapping") is mostly stale.
  `V4__tool_registry_seed_data.sql:305-325` already seeds CREATING_PO / RECEIVING_ASN /
  INVENTORY_RECON. **Real gap:** `PROCESSING_RETURN` has zero tool mappings, and the Gate 2C
  completeness list explicitly requires it.

Two genuine build gaps had no owning issue, so they were filed separately (#1367, #1368) rather
than folded into a gate issue's scope:

1. No live HTTP harness existed. `scripts/eval_live.py` drives Postgres+pgvector and the
   embedding model directly — it never calls `/v1/mcp/chat`, `/chat/stream`, or
   `/v1/nlt/requests`. Six of the eight gate issues need exactly that.
2. Gate 7 dashboards don't exist. `docs/dashboards/nlti-overview.md` has 8 request-health panels;
   Gate 7 needs routing/tier/fallback/prompt-layer/permission-reject/RAG-recall/write-confirmation
   panels too, and no NLTI dashboard JSON is provisioned under
   `observability/grafana/provisioning/dashboards/json/`.

**Hard constraint:** live runs only work from a host with line-of-sight to the alpha DB + local
Ollama embedding container (`scripts/run-live-eval.sh`, `scripts/eval-cron.sh`). Every live item
below is either run by whoever has that access, or needs access granted to this session.

## Dependency graph

```
#1217 (Gate 5 sweep) ── independent ── DO FIRST

#1367 (live harness) ──┬──► #1213, #1214, #1215, #1216, #1218
                        └──► #1219 (admin/write suites)

#1368 (dashboards) ────────► #1219

#1212 (Gate 0 baseline) ──┬──► #1216 (quality delta vs baseline)
                            └──► #1219 (live tuning promotion needs fresh passing eval file)

#1213 (telemetry) ──┬──► #1214 (equivalence compares prompt layers)
                     ├──► #1215 (workflow in telemetry)
                     ├──► #1216 (routing.tier read from telemetry)
                     └──► #1219 (dashboards read telemetry)

#1215 ──► #1214 (equivalence clause: "same workflow state")
#1216 ──► #1219 (tier panels need real tier traffic)
#1218 ── independent of the above, needs its own alpha write-target decision
```

Critical path: **#1367 → #1212 → #1216 → #1219** (dashboards #1368 feed the same endpoint and
gate on the same soak).

## Waves

### Wave 0 — offline, no alpha access needed

| # | Work | Serves |
|---|---|---|
| 0.1 | Record fixture-count evidence + rerun log into the Gate 0 checklist; strike the stale "seed-only 4/4/4" HOLD line | #1212 |
| 0.2 | Retrieval-lock sweep: **build** an offline `RetrievalLockTest` (doesn't exist yet) asserting all 39 `application.yml` static docs have deterministic id, `rag-scope`, `required-permissions`, `source-path`, documented chunking; **build** `scripts/rag_lock_sweep.py` (doesn't exist yet) for the live half (content hash vs `rag_preload` rows) — Wave 1 step 2 runs this script, so it must land in this wave first | #1217 |
| 0.3 | Build `scripts/nlti_live_verify.py` — per-suite harness (`equivalence`, `persona`, `workflow`, `router`, `write-gate`, `admin`); auths as N personas via the gateway, harvests `nlti.request.telemetry` from Loki by correlationId, emits JSON + paste-ready markdown evidence | **#1367**, feeds #1213 #1214 #1215 #1216 #1218 #1219 |
| 0.4 | `V34__processing_return_workflow_seed.sql` (+ h2 twin), pending decision D below | #1215 |
| 0.5 | Dashboard JSON + extend `docs/dashboards/nlti-overview.md` with the missing routing/tier/fallback/RAG/prompt-layer/write-gate panels; cross-check `docs/alerts/nlti-alerts.md` covers the 7 required alerts | **#1368**, feeds #1219 |
| 0.6 | ✅ **Done (2026-08-18).** Un-archived to [`gate-verification-runbook.md`](gate-verification-runbook.md) as the live procedure; retired-worktree instructions corrected, and §B.6–B.10 marked superseded in place by the #1367 harness (see its *Harness supersession map*) | all |

Estimated: ~1.5–2 days, mostly 0.3.

### Wave 1 — first alpha session (~2–3h), needs alpha access

Run against the #1367 harness instead of ad hoc commands:

1. `scripts/eval_live.py` + `scripts/run-live-eval.sh BaselineCaptureIT` → commit baseline into
   `src/test/resources/eval/baseline.json` → **closes #1212**
2. `scripts/rag_lock_sweep.py` (built in Wave 0.2) → **closes #1217**
3. `nlti_live_verify.py --suite equivalence,persona` → **closes #1213, #1214**
4. Apply V34, set a session to `PROCESSING_RETURN` via
   `POST /v1/nlt/sessions/{id}/workflow-state`, `--suite workflow` → **closes #1215**
5. `--suite write-gate` (needs decision C) → **closes #1218**

### Wave 2 — tier config + redeploy (~1 day incl. soak)

Pull tier models on alpha Ollama, set `MCP_MODEL_SIMPLE`/`MCP_MODEL_COMPLEX` in
`/opt/durion/alpha/.env`, `scripts/redeploy-backend-tag.sh <tag> pos-mcp-server`, run
`--suite router` over a representative query set, diff quality vs the Wave-1 baseline →
**closes #1216** (or records a rollback via `MCP_MODEL_TIERING_ENABLED=false`).

### Wave 3 — #1219, calendar-bound (~3–5 days elapsed, ~3h hands-on)

Set `MCP_TUNING_MODE=shadow`, let proposals accumulate; meanwhile verify the admin flow +
dashboard (#1368) against real traffic; then exercise the gated `live` promotion (it checks
`mcp.tuning.eval-result-path` for a fresh `thresholds.passed=true` file — that's the Wave-1 eval
output, hence the #1212 dependency); return to shadow. → **closes #1219, final gate.**

### Wave 4 — one PR

Fill all 8 metrics tables in `implementation_checklist.md`, flip HOLD→Pass with
evidence/approver/date, close #1212–#1219, #1367, #1368.

## Open decisions

Answers needed before the corresponding wave item can complete (see chat history for the full
list — repeated here as the checklist):

- **A.** ~~Alpha access~~ **RESOLVED 2026-08-18**: the session drives the alpha host directly via
  AWS SSM (`aws ssm send-command`; instance id in the operations memory / SSM console, not
  pinned here); all live runs executed this way.
- **B.** ~~Tier models for #1216~~ **RESOLVED 2026-08-19**: `MCP_MODEL_SIMPLE=gpt-oss:20b`,
  `MCP_MODEL_COMPLEX` unset (default executor `gpt-oss:120b` is already the complex-class model).
  Both tiers on the ollama.com cloud backend — chat does NOT run on alpha's local Ollama (that
  container serves embeddings only), so the plan's "pulled on alpha Ollama" framing was wrong; a
  local simple model would have measured CPU inference, not tiering. All three models
  (router `qwen3.5:397b`, both tiers) verified serving HTTP 200 on the rotated key. Set in
  `/opt/durion/alpha/.env`, live in the container.
- **C.** ~~#1218 write target~~ **RESOLVED 2026-08-19 (revised same day)**: seed-then-cancel of a
  probe appointment via the discovered tool `shop-manager_cancelappointment`
  (`DELETE /v1/appointments/{appointmentId}/cancel`), actor = the DISPATCHER persona.
  - **First choice, `prompts_deletesystemprompt`, proved structurally impossible on alpha**: the
    gateway's api-docs aggregation (swagger-config, 23 services) does not include pos-mcp-server,
    so its operations are never discovered and no `prompts_*` tool exists for a write plan to bind
    (observed live: 403 from `requireToolPermission`; 389 discovered tools, none from
    pos-mcp-server). Whether pos-mcp-server *should* be aggregated is an open product question,
    deliberately not decided here.
  - **Why cancelappointment**: discovered live with `appointments:cancel` attached; the DISPATCHER
    and LOCATION_MANAGER personas hold `appointments:create` + `appointments:cancel` with no grant
    changes; the write crosses a real service boundary (mcp-server → gateway → shop-manager) under
    a NON-admin actor; shop-manager is not a Kafka-enabled domain. Residue: one inert CANCELLED
    probe appointment on a far-future slot (cancel is a status write, not a row delete).
  - **Rejected alternatives**: `security-service_deleterole` — pos-security-service cannot emit
    `x-required-permissions` (no pos-security-common dependency), so its discovered ops carry zero
    grants and would need a manual tool-permission grant, an extra registry mutation;
    `price_deletepromotioneligibilityrule` — at decision time only the unassigned ADMIN role held
    `pricing:promotion:manage` (a LOCATION_MANAGER grant is now in flight, making this the
    documented fallback if appointment seeding fails validation; note its seed is two-step —
    offer, then rule — and the harness `seed` block currently supports one request).
  - Dirtying: acceptable only as self-seeded data — the harness `seed` block creates the probe
    record under `--allow-writes` and the gated write consumes it. Config:
    `scripts/fixtures/nlti-write-target.example.json` (authoritative).
- **D.** #1215 — seed `PROCESSING_RETURN` (which tools?) or except it in the sign-off; does
  `routing.workflowState` + the `NLTI_SESSION_WORKFLOW_STATE_SET` audit event satisfy "workflow
  transitions in telemetry," or is a dedicated transition event wanted?
- **E.** ~~#1219 soak~~ **RESOLVED 2026-08-19**: 3 nights shadow (02:00 UTC cron), then ONE gated
  `live` promotion exercised on alpha (fresh `eval_live.py` baseline into the `/opt/eval` mount
  first), then immediate revert to shadow. Rationale: exercises the full #1219 admin flow rather
  than accepting shadow-only evidence. (Original text: shadow soak length; do we exercise `live`
  tuning promotion on alpha — it writes
  `mcp_tool.priority`)?
- **F.** #1217 — **PARTLY RESOLVED**: no doc failed the mechanical sweep (39/39 locked); the
  offline `RetrievalLockTest` IS a permanent CI test. Residue: the `security.guide` public-doc
  policy call (fix content vs record exception) — blocks Gate 5 Pass; and whether
  `rag_lock_sweep.py` itself joins CI (optional).
- **G.** Sign-off approver for the eight gate blocks.
- **H.** ~~Telemetry harvest~~ **RESOLVED**: Loki/LogQL is the harvest path; every suite joins
  telemetry by correlation id since PR #1382 (fallback joins render as UNVERIFIED-ATTRIBUTION).
