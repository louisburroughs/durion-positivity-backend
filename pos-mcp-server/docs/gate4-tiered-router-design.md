# Gate 4 — Tiered Model Router (implementation-ready design)

> **Status:** IMPLEMENTED (PR #1199, 2026-08-08 — #1192 CLOSED): router wired into both chat
> managers behind `mcp.model.tiering-enabled` (default true at the time; **now false** — see
> the dormancy note below), `TieredChatModelResolver`
> (`mcp.model.router/simple/complex`, blank = default model), tier-suffixed agent cache keys,
> shared T0 fast path for blocking + streaming, router/tier telemetry; 14 tiering tests.
> Live verification (routing %, latency, quality) still open — see runbook §B.7 and the Gate 4
> sign-off in `implementation_checklist.md`. Design retained below as the implementation record.
>
> **DORMANT since 2026-09-03 (#1683).** `mcp.model.tiering-enabled` now defaults to `false`.
> Nothing here was wrong; it was simply never wired to distinct models outside a one-off gate run.
> `mcp.model.simple` and `mcp.model.complex` both default to blank, and no `.env` or compose file
> set them, so `TieredChatModelResolver` returned the default executor for both — **T2-simple and
> T2-complex were the same `gpt-oss:120b` with the same options**. Every non-simple-chat turn was
> still paying a full `qwen3:4b` T1 classification (`NltiRouter.classify`, from
> `SessionAgentManager.routeTier`) to pick between two identical destinations. Decision B in
> `gate-closeout-plan-1212-1219.md` had resolved this to `MCP_MODEL_SIMPLE=gpt-oss:20b`, and the
> 2026-08-19 live run validated it, but that value never landed in repo config.
>
> Of the two ways out (wire a real T2-simple model, or stop paying for the no-op) this records the
> second, on purpose: the analytics gate (#1601) is being graded at n=1 right now, and most gate
> questions are single-domain reads that `TierSelector` routes to T2-simple. Introducing a second
> executor model mid-measurement would add a confound to every subsequent comparison — the same
> reason #1683 also took the executor to temperature 0.
>
> **To revive:** set `MCP_MODEL_SIMPLE` to a genuinely smaller pulled model *first*, then
> `MCP_MODEL_TIERING_ENABLED=true`. Enabling the flag on its own only restores the wasted call.
> While dormant, keep `NltiModelTierStarved` and `NltiRoutingMixShift` silenced — they assume
> tiering is on and the flag is not visible to LogQL (`docs/alerts/nlti-alerts.md`).

Route each request to the cheapest model tier that preserves quality + safety.

## Why
Measured: `qwen3.5:cloud` takes ~28s for a trivial completion (reasoning model). Using it for routing
or simple lookups is wasteful. Route on **complexity + risk**, not just cost.

## Tiers (confirmed direction from `nl-interface-design.md` §2.2)
| Tier | Job | Model (configurable) |
|---|---|---|
| T0 | Rule fast-path, no LLM | — (`SimpleChatRuleCatalog`, already exists) |
| T1 | Router/classifier (JSON, temp 0) | small: `qwen3:4b` / `llama3.2:3b` |
| T2-simple | single-tool lookup + format | `qwen3:8b` / `qwen2.5:7b-instruct` |
| T2-complex | multi-tool, writes, accounting/tax/admin/security | `qwen3:32b` / `gpt-oss:120b` |

## Components

### G4.1 — Router (`NltiRouter`)
Calls the small T1 model with a strict-JSON system prompt; returns:
```json
{ "intentType": "QUERY|ACTION|UNKNOWN", "riskLevel": "LOW|MEDIUM|HIGH",
  "domain": "<rag-scope>", "complexity": "single-lookup|multi-domain" }
```
- temperature 0 (deterministic); response validated against the enum set before use.
- **Invalid/unparseable output → safe default**: `{ACTION-unknown, HIGH, multi-domain}` so it routes to
  **T2-complex** (quality/safety-preserving), never silently to a small model.
- Reuse the existing intent model: `IntentParserService` / `NltiIntentType` / `NltiRiskLevel` already
  model intent + risk; the router is their LLM-backed producer (or wraps a deterministic pre-pass).

### G4.2 — Tier selection (pure function, unit-testable)
```
T2_COMPLEX  if  intentType == ACTION
            or  riskLevel >= MEDIUM
            or  complexity == multi-domain (>=2 expected tool calls)
            or  domain in {accounting, tax, admin, security}
else        T2_SIMPLE
```
T0 short-circuits before T1 when a `SimpleChatRule` matches (greeting/thanks/capability). This is the
gate that also reconciles the blocking-vs-streaming `simpleChat` divergence noted in Gate 2A — make T0
shared by both managers.

### G4.3 — Model wiring
Today there is one `ChatModel` bean. Introduce a tier→model resolver:
- Define `mcp.model.router`, `mcp.model.simple`, `mcp.model.complex` (name + base-url + timeout).
- `TieredChatModelResolver.modelFor(Tier)` returns the `ChatModel`/`StreamingChatModel` for the tier.
- Agent cache key gains the tier (`role::tier::toolCacheKey`) so a role can cache both a simple and a
  complex agent.
- **Keep `mcp.model.fallback` orthogonal:** it is primary→secondary *failover within a tier*, not tier
  routing. Telemetry distinguishes `fallback_used` from `tier`.

### G4.4 — Telemetry
`NltiRequestTelemetry.Routing` already carries `intentType / riskLevel / domain / complexity / tier`
and `Model.routerModel / tierModel / fallbackUsed`. Populate them in the router + executor path
(this is also where the Gate 1 prompt-layer and Gate 2C workflow-state telemetry get emitted — one
per-request telemetry pipeline serves all three).

## Drift guards (Gate 4 locks)
- Risky requests (ACTION / HIGH / accounting/tax/admin/security) **never** route to a small model.
- Router decisions always logged (telemetry).
- Router classification **never** overrides permission gating (it picks a model, not tools/permissions).
- Fallback (failover) is not tier-routing.
- No prompt changes bundled here without a separate eval (Prompt lock).

## Verification (live — runbook §B.7)
- ≥80% of `single-lookup` fixtures route to T2-simple.
- 100% of write / accounting / tax / admin / security fixtures route to T2-complex.
- Malformed router JSON → request still processed (safe default), logged.
- Answer-quality eval ≥ Gate 1 baseline; p95 within the soft SLO.
- `fallback_used` visible and distinct from tier in telemetry.

## Implementation order
G4.1 (router + validation + safe default) → G4.2 (tier function + tests) → G4.3 (tier→model + cache
key) → wire into both managers (+ shared T0) → G4.4 (telemetry) → live §B.7.
