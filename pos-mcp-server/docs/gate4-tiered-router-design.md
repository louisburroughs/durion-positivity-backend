# Gate 4 — Tiered Model Router (implementation-ready design)

> **Status:** DESIGN. Route each request to the cheapest model tier that preserves quality + safety.
> Verification (routing %, latency, quality) is live — see runbook §B.7.

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
