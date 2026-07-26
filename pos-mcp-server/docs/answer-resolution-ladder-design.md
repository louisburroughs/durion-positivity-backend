# Answer Resolution Ladder — implementation-ready design

> **Status:** DESIGN. Replace "returned nothing / leaked reasoning" with a deterministic
> fall-through: real answer → composed answer → deep link → honest hand-off. Scoped to
> `pos-mcp-server` orchestration plus a shared count convention across domain services.

## Why

Observed failure (`How many workorders are currently open?`): the agent emitted no tool call,
returned blank `content`, and `ChatResponseText` surfaced the model's raw planning monologue as the
"answer" (`ChatResponseText.java:41`). Two root causes:

1. **No answering capability** — there is no status-filtered count path; `workorder_getallworkorders`
   is unbounded and `workorder_listwip` requires `locationId` and covers only WIP, not "open."
2. **No graceful degradation** — when the agent cannot answer, it degrades to leaked reasoning
   instead of something useful.

The ladder addresses (2) structurally and adds the capability layer for (1). It is a **policy**, not a
single feature: each rung is attempted in order; a miss falls through to the next.

```
Rung 1  Answer tool            real count/list/detail                        ← R1
Rung 2  Composed answer        resolve missing params, then answer           ← R2
Rung 3  Deep link              point at the correct screen + filters         ← R3
Rung 4  Honest hand-off        "can't answer, here is where / who"           ← R4
```

Rungs 1–2 are *correctness*; rungs 3–4 are *graceful degradation*. A count always beats a link; a link
always beats leaked reasoning.

---

## Components

### R1 — Count convention + `CountResponse` DTO (rung 1)

A uniform, discoverable count endpoint per countable resource. Because tools are OpenAPI-discovered
(`OpenApiToolProvider`), a consistent path + operation id makes them auto-register as `*_count*` tools
with no MCP-side wiring.

**DTO** — new shared type in `pos-shared-dtos` (`com.positivity.shared.dto`), reused by every domain:

```java
package com.positivity.shared.dto;

/** Aggregate count result. {@code total} is the grand total; {@code groups} is the optional
 *  per-key breakdown (e.g. status → count). Both are server-computed via COUNT queries;
 *  callers never page the full collection to count it. */
public record CountResponse(long total, List<CountGroup> groups) {
    public record CountGroup(String key, long count) {}

    public static CountResponse of(long total) {
        return new CountResponse(total, List.of());
    }
}
```

**Endpoint convention** (each domain service):

```
GET /{resource}/count?<domain filters>        → 200 CountResponse
```

- Backed by a repository COUNT (`countByStatusIn(...)`), **not** `findAll().size()`. The building block
  already exists for workorder: `WorkorderRepository.findByStatusIn(...)` returns a `Page` whose
  `getTotalElements()` is a COUNT (`WorkorderRepository.java:51`); add a dedicated
  `long countByStatusIn(Collection<WorkorderStatus>)` to avoid fetching a page at all.
- `@EmitEvent(id = "{DOMAIN}_COUNT", apiVersion = "1")`, threshold preset `fastRead`.
- `@PreAuthorize` with the resource's existing `view` permission (e.g. `workorder:workorder:view`).
- Annotate the discovered tool `readOnlyHint = true`, `idempotentHint = true`.

**First-wave endpoints** (repos already carry COUNT-shaped queries — verified in workorder, invoice,
accounting):

| Domain | Endpoint | Filter → answer |
|---|---|---|
| workorder | `GET /v1/workorders/count?openOnly=true` | open = not `COMPLETED`/`CANCELLED` |
| workorder | `GET /v1/workorders/count?status=WORK_IN_PROGRESS` | per-status |
| people/hr | `GET /v1/people/count?status=ACTIVE` | active employees |
| invoice | `GET /v1/invoices/count?status=UNPAID` | open invoices |
| accounting | `GET /v1/accounting/journal-entries/count?period={p}` | entries in period |

**"Open" is defined once, at the source of truth** — add to `WorkorderStatus`:

```java
public static Set<WorkorderStatus> getOpenStatuses() {          // non-terminal
    return java.util.EnumSet.complementOf(EnumSet.of(COMPLETED, CANCELLED));
}
```

so the set stays correct if a status is added later (mirrors the existing
`getInProgressSubStatuses()`, `WorkorderStatus.java:46`).

### R2 — Prerequisite edges + composition (rung 2)

Many count/list tools need a parameter the user did not supply (`listWip` needs `locationId`). Today
that dependency is implicit, so the model guesses or stalls. Model it explicitly and let the ladder
resolve one hop before giving up.

**Edge model** — metadata attached to discovered operations (no new datastore; a table beside the
existing tool registry):

```sql
CREATE TABLE mcp_tool_prerequisite (
    tool_name        text NOT NULL,   -- e.g. 'workorder_listwip'
    required_param    text NOT NULL,   -- e.g. 'locationId'
    producing_tool    text NOT NULL,   -- e.g. 'location_getcurrentuserprimarylocation'
    producing_field   text NOT NULL,   -- field on the producer's result to bind
    PRIMARY KEY (tool_name, required_param)
);
```

**Resolver** (orchestration):

```java
public interface PrerequisiteResolver {
    /** Params the tool needs that are absent from the request context, each with the tool that
     *  can produce them. Empty when the tool is directly callable. */
    List<MissingParam> missingFor(String toolName, RequestContext ctx);

    record MissingParam(String param, String producingTool, String producingField) {}
}
```

Composition is **bounded to one hop** in v1 (resolve producers that are themselves directly callable);
deeper chains fall through to rung 3. This is the point where a graph engine *would* help — deferred:
one-hop edges in Postgres cover the current needs; revisit only if multi-hop composition becomes core.

### R3 — Screen registry + deep-link resolver (rung 3)

When no tool (composed or not) can answer, return a link to the screen where a human can. Resolution is
**semantic**, mirroring how tools are embedded (`ToolEmbeddingInitializer`, pgvector store) — so it
generalizes to unseen phrasings instead of needing a question→URL table.

```sql
CREATE TABLE mcp_screen_registry (
    id             uuid PRIMARY KEY,
    screen_key      text NOT NULL UNIQUE,      -- 'workorders.list'
    title           text NOT NULL,             -- 'Work Orders'
    description     text NOT NULL,             -- embedded; 'open/closed work orders, filter by status/location'
    domain          text NOT NULL,             -- rag scope, aligns with RouterClassification.domain
    url_template    text NOT NULL,             -- '/workorders?status={status}&location={locationId}'
    required_perm   text,                      -- gate a link the caller can't open
    embedding       vector(768)                -- nomic-embed-text, matches tool embeddings
);
```

```java
public interface ScreenLinkResolver {
    /** Best screen for an unanswered request, or empty if nothing clears the similarity floor.
     *  Fills url_template placeholders from resolved context params (status, locationId, …). */
    Optional<ScreenLink> resolve(String userMessage, String domain, RequestContext ctx);

    record ScreenLink(String title, String url, String screenKey) {}
}
```

- Similarity floor (config `mcp.screen.min-score`, default 0.6) — below it, no link; fall to rung 4.
- Permission-gated: never surface a link to a screen the caller lacks `required_perm` for.
- **Frontend contract dependency:** URLs must be stable, linkable routes that accept filter params.
  Reconcile `url_template`s against `docs/frontend-audit-route-reconciliation.md`; a screen with no
  real route is not registered.

### R4 — Honest hand-off (rung 4)

Nothing matched. Return a truthful "I can't answer this" carried in the existing guided-error channel
rather than blank content or leaked reasoning.

- Reuse `ApiError.guided(...)` (`com.positivity.shared.error.ApiError`) — populate `nextAction`
  (what the user can do) and `supportAction` (who to ask). No new envelope.
- Never echo the model's `thinking` channel to the user in this state; the ladder owns the response.

### RL — Ladder orchestrator + fallback trigger points

The ladder wraps the existing chat path in `SessionAgentManager.chat` (`SessionAgentManager.java:230`).

```java
public interface AnswerResolutionLadder {
    LadderResult resolve(CurrentUserContext user, String message, AgentTurn turn);

    /** turn = the outcome of the normal agent().chat() call: model content, tool calls made,
     *  and tool-selection confidence — so the ladder decides on structural signals, not on the
     *  model self-reporting "I don't know." */
    record AgentTurn(String content, List<String> toolsInvoked, double topToolScore, boolean blankContent) {}

    record LadderResult(String text, Rung rung, @Nullable String screenUrl) {}
    enum Rung { ANSWER_TOOL, COMPOSED, DEEP_LINK, HANDOFF }
}
```

**Trigger points — the miss is detected structurally, never from model self-report:**

| Signal | Source | Meaning |
|---|---|---|
| `blankContent` after tool phase | `ChatResponseText.extract` returns fallback / recovers from `thinking` | model produced no real answer → **do not surface `thinking`; enter ladder** |
| `toolsInvoked.isEmpty()` and message is a data question | router `intentType == QUERY` (`NltiRouter`) with no tool call | model answered from prior knowledge or stalled → verify via ladder |
| `topToolScore < mcp.tool.min-score` | `ToolSelectionEngine` / `OpenApiToolProvider.resolveToolCallbacks` | no confidently-relevant tool was offered → rungs 2–3 |
| tool call returned 4xx "missing required param" | tool execution | → rung 2 (resolve the param), then retry once |

The cleanest single hook is `ChatResponseText`: today it silently recovers the `thinking` channel
(the exact bug). Change that recovery to instead hand control to the ladder, which decides rung 2/3/4.
That one change removes the leak even before rungs 1–3 land (it degrades straight to rung 4).

---

## Data & config summary

- New shared DTO: `CountResponse` (`pos-shared-dtos`).
- New tables (pos-mcp-server schema, Flyway): `mcp_tool_prerequisite`, `mcp_screen_registry`.
- New config: `mcp.tool.min-score` (default 0.6), `mcp.screen.min-score` (0.6),
  `mcp.ladder.enabled` (feature flag, default true in `alpha`).
- Reused: pgvector store + `nomic-embed-text` (screen embeddings), `ApiError.guided`, `@EmitEvent`
  registries, per-resource `view` permissions.

## Telemetry

Extend the existing `nlti.request.telemetry` (`NltiRequestTelemetryFactory`) with a `resolution` block:
`{ rung: ANSWER_TOOL|COMPOSED|DEEP_LINK|HANDOFF, screenKey?, composedProducer? }`. This makes the
degradation rate observable — rising `DEEP_LINK`/`HANDOFF` share is the signal to add the next count
tool or screen entry.

## Drift guards / non-goals

- **Rung 1 beats rung 3 always.** A deep link is never returned when a count/list tool could answer —
  the ladder only falls through on a *structural* miss.
- **No question→answer table.** Pre-seeding enumerated natural-language questions is out of scope;
  canonical questions live only in the eval suite (`reference/evaluation.md` format). Capabilities are
  seeded, questions are not.
- **No graph DB in v1.** Prerequisite edges are one-hop rows in Postgres; a graph engine is deferred
  until multi-hop composition is load-bearing.
- **Never surface the `thinking` channel to end users.** The ladder owns the unanswered-state
  response.
- **Operational note (out of band):** set `OLLAMA_CHAT_THINK=false` for the reasoning chat model.
  With thinking enabled, the model routes planning into `thinking` and returns blank `content`; the
  ladder makes the miss safe, but disabling think removes the miss at the source.

## Rollout order

1. **R1 workorder count + `getOpenStatuses()` + `CountResponse`** — makes the original question
   answerable; smallest, highest-value slice.
2. **RL trigger in `ChatResponseText`** → rung 4 hand-off — stops the leak immediately.
3. **R3 screen registry + resolver** — turns misses into deep links (needs frontend route reconcile).
4. **R2 prerequisite edges** — composes `locationId`-style gaps (fixes `listwip`).
5. **R1 remaining domains** (people, invoice, accounting) — widen coverage.
6. Evals per rung (`reference/evaluation.md`).
