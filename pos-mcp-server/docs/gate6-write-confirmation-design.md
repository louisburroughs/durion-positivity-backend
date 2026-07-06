# Gate 6 — Write-Action Confirmation Gate (design)

> **Status:** DESIGN. Highest-risk gate: enables gated writes via preview → explicit confirmation →
> exact persisted execution. Verification is live (full flow + DB) — runbook §B.9. Sits on the
> **NLTI-session path** (`/v1/nlt/requests`, `NltiRequest`/`NltiSession`), NOT the session-less
> `/v1/mcp/chat`.

## Grounded building blocks (already present)
- `NltiRequest{ id, correlationId, sessionId, status (NltiRequestStatus), promptHash, createdAt }`.
- `NltiRequestStatus = ACCEPTED | COMPLETE | ERROR` (extend, below).
- `NltiIntentType = QUERY | ACTION | UNKNOWN`; `NltiRiskLevel = LOW | MEDIUM | HIGH`.
- `NltiAuditEventType = REQUEST | INTENT | PLAN | CONFIRMATION | EXECUTION_STEP | EXECUTION_COMPLETE | EXECUTION_FAILED` (the full write audit chain).
- `AuditLedgerService`; `NltiController POST /requests` (`@PreAuthorize NLTI_REQUEST_SUBMIT`).
- Gate 4 `NltiRouter` (ACTION → route to the write gate); Gate 2C `WorkflowState` on the session;
  Gate 3 `RequestScopedUserContext` (per-request permissions); Gate 1 `PromptLayer.WRITE_GATE`.

## G6.1 — State model
Extend `NltiRequestStatus`: add `PENDING_CONFIRMATION`, `CONFIRMED`, `EXECUTING`, `CANCELLED`,
`EXPIRED` (keep `ACCEPTED`/`COMPLETE`/`ERROR`). This is the **conversation lifecycle** state
(distinct from `WorkflowState`, the operational tool-gating state — Gate 2C).

New `NltiWritePlan` entity (persisted), linked to `NltiRequest`/`sessionId`:
```
id, request_id, session_id, idempotency_key (unique),
target_tool, args_json, arg_provenance_json,
risk_level, source_entity_versions_json, summary_text,
status, expires_at, created_at, executed_at
```
Flyway migration (pg + h2) creates `nlti_write_plan`. At most one plan per session in
`PENDING_CONFIRMATION` (enforced by a partial unique index or service guard).

## G6.2 — Plan creation (ACTION ⇒ PLAN, not execution)
Router (Gate 4) classifies `intentType = ACTION` → orchestration produces a **plan**, not a call:
1. Resolve target tool + fully-grounded args + a human-readable summary.
2. Emit `NltiAuditEventType.PLAN`; persist `NltiWritePlan` (`PENDING_CONFIRMATION`, `expires_at = now + mcp.nlti.write.plan-ttl`).
3. **Do not invoke the write tool.** The model must not execute writes directly (G6.8 prompt layer +
   the write tools are exposed only via a plan-producing path, never as auto-executing tools).

## G6.3 — Confirmation + exact execution
New endpoint `POST /v1/nlt/requests/{id}/confirm` (`@PreAuthorize` a confirm permission). On confirm:
1. **Permission re-check** for the caller against the plan's tool (defense in depth — also checked at
   plan time). Reuse `RequestScopedUserContext` / permission gating.
2. **Expiry check** — past `expires_at` → `EXPIRED`, require re-plan (no execution).
3. **Idempotency** — a key that already executed is terminal; re-confirm returns the original result,
   never a second write.
4. **Stale-data (risk ≥ MEDIUM)** — re-read source entity versions; if changed since plan → force
   **re-preview** (cancel + new plan), surface the change.
5. Execute the **exact persisted args** (`args_json`) — **never re-parse the user's text** (the
   documented gate-mismatch failure mode; see `docs/runbooks/confirmation-gate-mismatch.md`).
6. Emit `CONFIRMATION` → `EXECUTION_STEP` → `EXECUTION_COMPLETE` / `EXECUTION_FAILED`; set status
   `EXECUTING` → `COMPLETE`/`ERROR`.

## G6.4 — Argument provenance
Each plan arg tagged: `USER_TEXT | RETRIEVED_DOC | PRIOR_TOOL_RESULT | USER_CONTEXT | INFERRED_DEFAULT | SYSTEM_CONFIG` (stored in `arg_provenance_json`).
- Every write-plan arg carries a provenance marker.
- `INFERRED_DEFAULT` args are **disclosed in the preview** ("defaulting priority to normal — change?").
- High-risk (`HIGH`) inferred defaults are **rejected** or require explicit user selection.

## G6.5 — Conversation flow rules
- At most one `PENDING_CONFIRMATION` plan per session.
- A new ACTION while one is pending **cancels** the prior plan (→ `CANCELLED`) and creates a fresh preview.
- Material argument change ("actually, tomorrow instead") cancels + replaces the pending plan.
- Missing required argument → `NEEDS_CLARIFICATION` (ask), never a guessed value.

## G6.6 / G6.7 — Expiry, idempotency, concurrency
- `mcp.nlti.write.plan-ttl` (default 10m). Expired confirmation does not execute.
- Idempotency key executes once; re-send = original result.
- Capture entity version/ETag at plan time where the source API exposes it; re-read before execute for
  risk ≥ MEDIUM; changed → re-preview.

## G6.8 — WRITE-GATE prompt layer
Gate 1's `RolePromptResolver.assemble` already supports a `WRITE_GATE` layer; wire it so the layer is
included **only when a write-capable tool is in the candidate set**. Content: never execute a mutation
directly; always present a preview and require explicit confirmation; echo every argument you will
send; disclose inferred defaults.

## G6.9 — Downstream validation not bypassed
Confirmation authorizes the proposed API call; it does **not** bypass service-layer validation,
permission checks, approval workflows, accounting/inventory controls, or concurrency checks. The
downstream service remains the authority and may still reject — surface that rejection accurately.

## G6.10 — Telemetry
`NltiRequestTelemetry.Write { isWrite, confirmationOutcome, planArgsProvenance }` already exists;
populate it (confirmation outcome: CONFIRMED / CANCELLED / EXPIRED; provenance counts).

## Drift guards (Write lock)
Model cannot call write tools directly · confirmation uses persisted args, not re-parse · preview
omits no important arg · inferred defaults visible · permission checked twice · downstream validation
not bypassed · no ambiguous coexisting pending plans · audit chain complete · rollback to read-only
available.

## Verification (live — runbook §B.9; the 30+ write-safety fixtures are the exit criterion)
No mutation without confirmation · plan args == executed args · expired confirmation does not execute ·
idempotent re-confirm does not double-execute · material change cancels + re-previews · missing arg →
clarification · inferred defaults disclosed · high-risk inferred rejected · lower-permission caller
cannot execute a higher-permission plan · changed entity version forces re-preview · downstream
rejection surfaced accurately.

## Implementation order
G6.1 (statuses + `NltiWritePlan` + migration) → G6.2 (plan creation, suppress direct execution) →
G6.4 (provenance) → G6.8 (WRITE-GATE layer) → G6.3 (confirm endpoint + exact execution + dual perm) →
G6.6/G6.7 (expiry/idempotency/stale-data) → G6.10 (telemetry) → live §B.9.

## Rollback
Flip the NL interface to **read-only** by suppressing write-capable tools — the safe degradation path.
