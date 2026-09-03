# Wave 2 gate close-out plan (#1660, #1663, #1675, #1676; #1661 closed)

> **Purpose:** prioritised execution plan for the open `pos-mcp-server` issues left by the
> 2026-09-03 chat-path gate runs. Companion to `docs/gate-runs/wave-2/` (run records) and
> `analytics-capability-plan.md` §6 (the gate matrix and call budgets), which stay the sources of
> truth for scores and budgets. This file tracks *what* is being changed, *why* that lever, and in
> *what order*.
>
> **Created:** 2026-09-03. **Tracks:** #1660 (ask 3), #1663, #1675, #1676. **Closed while
> planning:** #1661 (everything it asked for landed in #1664/#1670/#1672/#1673).

## Execution status (2026-09-03)

| Wave | Status | Record |
|---|---|---|
| A1 #1675 | **DONE** | `0b61d73b` — `DateWindowResolver` + `DateWindowFacadeTool.resolveDateWindow` (V43), `DATE_WINDOW` layer cut to classification + protocol; resolver counted as zero in §6 |
| A2 #1660 | **DONE (code)** | `ff702f54` — `InvoiceFacadeTool.getInvoicingLag` (E4, V44), `pos.tools.http.*` connect/read timeouts on the facade `RestClient`, q04 fixture promoted. Reading the recorded q04 cause needs the alpha run (Wave C) |
| A3 #1663 | **DONE** | `d420f0cc` — `billsIssuedInWindow` / `avgIssuedBillAmount` renamed at the source, `pos-accounting/openapi.yaml` regenerated, grader rule on q15/q17 |
| B #1676 | **DONE (code)** | `e698c1ff` — multi-status `status` on `GET /v1/workorders/search` (`openapi.yaml` regenerated), facade `status=OPEN` alias, per-value loop rule in `TOOL_USE`, `expected_plan` on q04/q05/q09/q15/q17. Tool-call observability is a documented gap: neither the chat response nor any admin endpoint exposes `mcp_tool_invocation_log`, so the runner's plan check is wired but reports n/a until a source exists |
| C | **PENDING** | one alpha gate re-run against the Track B seed with the four items deployed; read the q04 cause row; close #1660/#1675/#1676 on the run document |

Follow-ups outside this repository: regenerate the Angular SDK (`durion-positivity-sdk-angular`)
from the updated `pos-accounting` and `pos-workorder` specs; the durion workexec contract guide row
for `searchWorkorders` is updated on the same branch name in `durion`.

## Disposition

| Issue | Verdict | Priority | Why |
|---|---|---|---|
| #1661 | **CLOSED** | — | Window semantics pinned (#1670, #1672), questions versioned with a `window` field and q09 given an explicit window (#1671/#1673). Nothing it asked for is outstanding; model-side accuracy on multi-period spans is #1675. |
| #1675 | OPEN — plan | **P1** | Blocks three of twelve gate questions (q09, q12, q15) across three prompt iterations. Deterministic function; belongs in code. |
| #1660 | OPEN — re-scoped | **P1** | Asks 1–2 landed (#1665). Ask 3 is not a diagnosis task any more: q04's serving endpoint (E4) has **no facade tool**, so the model can only reach `searchInvoices`, which cannot answer q04 at all. |
| #1676 | OPEN — plan | **P2** | One question (q05), and intermittent: it **passed** on the committed 2026-09-03 run and failed on the two later ones. Cheap prompt lever plus a backend filter that removes the six-status loop. |
| #1663 | OPEN — plan | **P3** | Presentation defect on a correct figure. Field rename is in policy (pre-production, no shims); small and independent. |

## Findings that change the plan

1. **E4 was never promoted to a facade.** `GET /invoices/analytics/invoicing-lag` exists
   (`pos-invoice` `InvoiceAnalyticsController:152`, `invoice:analytics:view`), but
   `InvoiceFacadeTool` exposes only `getInvoice`, `searchInvoices`, `searchInvoiceLines` and
   `getRevenueByCustomer`. V42 promoted E1/E5/E8; E4 was left behind, and the q04 tool-selection
   fixture still sits in `tool-selection-pending/` marked "Blocked on W2 E4". The plan matrix
   requires q04 to pass via a ≤ 6-call loop over E4 — impossible today. This, not a transient
   fault, is why q04 spent 71 s in `searchInvoices`: a free-text search fans out to pos-customer
   and pos-workorder for id resolution, returns one page of 25 invoices with no work-order
   creation timestamps, and cannot produce a by-month lag whatever it returns.
2. **Facade HTTP calls carry no timeout.** `McpServerConfiguration.loadBalancedRestClientBuilder`
   builds a bare `RestClient.builder()` and `ToolRestClientSupport` adds only a timing
   interceptor. `pos-invoice`'s own outbound clients bound connect/read (`RestClientConfig`);
   the MCP facades do not. A stalled downstream therefore holds the whole chat turn until
   something else gives up, and the recorded cause (post-#1665) will be whatever that something
   is, not a named timeout.
3. **q05 is intermittent, not deterministic.** `docs/gate-runs/wave-2/2026-09-03-chat-path-gate-rerun.md:39`
   scores q05 **PASS** on `sha-af7f508` ("exactly the three open WOs … drops the C3 decoy and 133
   co-tenant open WOs"). The two failures #1676 cites are the later runs on `sha-25282ad` and
   `sha-14717ce`, both of which carry the ~20-line `DATE_WINDOW` layer added since. One pass and
   two fails is not proof of a prompt-length regression, but it is consistent with one, and
   #1675's plan shrinks that layer anyway.
4. **The fully-filtered q05 plan does not fit the round cap.** Server-side status filtering costs
   6 open statuses × N past-due customers; for the seed (2 customers) that is 12 `searchWorkorders`
   calls, and `BoundedToolCallingManager.MAX_TOOL_TURNS` is 8 sequential rounds. The passing run
   filtered by `customerId` only (5 rows for C1, well under the 25-row page) and dropped
   non-open statuses in context. The model's hesitation is partly a correct reading of its own
   tool description; the description should stop describing a plan that cannot fit.

## Sequencing

| Wave | Items | Depends on |
|---|---|---|
| A (parallel, independent PRs) | A1 #1675 resolver · A2 #1660 E4 facade + facade timeouts · A3 #1663 rename | — |
| B | #1676 prompt rule + multi-status search filter | A1 (so one prompt PR does not race another on `SystemPromptDefaults`) |
| C | one alpha gate re-run scoring all twelve; read `mcp_tool_invocation_log` for q04's recorded cause; close #1660/#1675/#1676 on evidence | A, B deployed |

---

## A1 — #1675: compute date windows in code

**Lever.** A deterministic resolver the model *calls*, so the dates in every analytics argument
are resolver output and the answer's window disclosure is a quote, not arithmetic. The model
keeps the one job it has shown it does reliably: classifying the shape from the wording.

**Why a tool rather than a per-facade argument.** Adding a relative-window argument to every
date-taking facade method (twelve `YYYY-MM-DD` parameters across `AccountingFacadeTool`,
`InvoiceFacadeTool`, `WorkorderFacadeTool`) is a wider change and forces each facade to carry the
same rules. One resolver tool touches no existing signature, is logged like any other invocation
(so a wrong classification is visible in `mcp_tool_invocation_log`), and can be replaced by the
per-argument form later if the extra round proves costly. That per-argument form stays the
documented fallback.

**Changes.**

- `internal/orchestration/tools/DateWindowResolver` — pure `java.time`, no Spring:
  `resolve(LocalDate today, Shape shape, Unit unit, int count, Comparison comparison)` →
  `ResolvedWindow(start, end, shape, statement, prior)`.
  - `Shape`: `ROLLING`, `CURRENT_TO_DATE`, `PRIOR_COMPLETE`, `CALENDAR_SPAN`.
  - `Unit`: `DAY`, `WEEK` (ISO, Mon–Sun), `MONTH`, `QUARTER`, `YEAR`. `DAY` with any shape but
    `ROLLING` is rejected (a day range has no calendar form — existing rule).
  - `Comparison`: `NONE`, `PRIOR_PERIOD` (same shape and length, offset one span),
    `YEAR_EARLIER` (same span one year earlier — "the same six months last year").
  - Rules ported verbatim from `SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT`: calendar spans end
    with the last complete period; current-to-date keeps the partial period; the January
    inversion case falls back to the partial period and says so in `statement`; start is never
    after end.
- `DateWindowFacadeTool.resolveDateWindow(shape, unit, count, comparison)` — `@Tool`, uses the
  shared `Clock`; returns JSON with `startDate`, `endDate`, `shape`, `statement` (e.g. "calendar
  span: 2026-03-01 to 2026-08-31 — six whole months ending with August 2026, the last complete
  month") and, when asked, `comparison.startDate/endDate/statement`.
  - `facade-contract.yaml`: `verb: NONE` entry (the manifest already allows no-HTTP tools).
  - V43 migration: `mcp_tool` row + permission group in the *authenticated* tier (every caller
    who can ask a dated question may resolve a date), description written for tool selection,
    embedding nulled for re-embed (V39/V42 precedent). `FacadeToolPermissionSeedTest` and
    `FacadeContractManifestTest.manifestCoversExactlyTheToolSurface` must stay green.
- `DATE_WINDOW_LAYER_TEXT` rewritten to classification + protocol only: keep the preposition
  rule, the mixed-comparison precedence, the independent-conditions rule, "apply defaults instead
  of asking", "explicit dates override"; add "call `resolveDateWindow` before any tool that takes
  a date, copy `startDate`/`endDate` verbatim, quote `statement` in the answer, never compute a
  date yourself". Delete every bullet that performs arithmetic (the worked illustration, the
  count-back rule, the inversion rule) — those now live in the resolver.
  `SharedOrchestrationSupport.formatUserContext` keeps injecting today's date; the resolver uses
  the same `Clock`, so the two cannot disagree.
- `ReportingPeriods` stays as-is for `period`-taking tools (`YYYY-MM` / `YYYY`); the resolver's
  `statement` tells the model which `period` to pass when a tool takes one.

**Tests.**

- `DateWindowResolverTest`: table-driven over every `window.resolved_range` in
  `eval/analytics-gate/QUESTIONS.json` for the gate's as-of date (the file is the oracle #1671
  made it), plus edge cases: 1 January "this year" and "in the last six months"; 29 February;
  week boundaries; `DAY` with a calendar shape rejected; `count` ≤ 0 rejected; comparison offsets
  for month, quarter and year.
- `RolePromptAssemblyTest` / `PersonaPrecedenceLayerTest`: layer still unconditional, still
  carries the precedence line, no longer carries a concrete date or an arithmetic rule.
- `DateWindowFacadeToolTest`: JSON shape, `Clock`-driven, statement wording pinned.

**Budget note.** The resolver is one extra tool round with no HTTP cost. Against
`MAX_TOOL_TURNS = 8` the tightest gate question is q04 (6 × E4 + resolver = 7 rounds if the model
issues them sequentially). Either count the resolver as zero in §6 (recommended — it is not a
downstream call) or raise the cap to 10; decide in the PR, record in the plan.

**Acceptance.** q09, q12, q15 resolved windows match `QUESTIONS.json` on the next gate run, with
the window stated in each answer; q01/q03 unchanged; every dated analytics call in
`mcp_tool_invocation_log` is preceded by a `resolveDateWindow` call in the same turn.

## A2 — #1660 (ask 3, re-scoped): make q04 answerable and make failures name themselves

**Changes.**

1. `InvoiceFacadeTool.getInvoicingLag(startDate, endDate)` → E4
   (`pos.invoice.invoicing-lag-uri-template`, default
   `/invoice/v1/invoices/analytics/invoicing-lag?startDate={startDate}&endDate={endDate}`).
   Description states the response shape (`rows[].avgDaysWoCreationToInvoice`, `rows[].count`,
   single window) and that a by-month answer loops one call per month. V43 description refresh
   for the `InvoiceFacadeTool` row (V42 pattern; class row already carries
   `invoice:analytics:view` since V42 for `getRevenueByCustomer`). `facade-contract.yaml` entry;
   `InvoiceFacadeToolTest` case; promote the q04 fixture from
   `tool-selection-pending/analytics-gate-pending.json` into `tool-selection/analytics-gate.json`.
2. Bounded timeouts on `loadBalancedRestClientBuilder`: `pos.tools.http.connect-timeout`
   (default 2 s) and `pos.tools.http.read-timeout` (default 30 s, comfortably above the 6.6–13.5 s
   the gate measured for whole turns) via the request factory. A hung call then records
   `SocketTimeoutException: Read timed out` through #1665's root-cause capture instead of an
   anonymous wait.
3. On the next alpha run, read the recorded cause for the 71 s failure:
   `SELECT tool_name, error_type, elapsed_ms, created_at FROM mcp_tool_invocation_log WHERE success = false ORDER BY created_at DESC`.
   Record it in the run document whatever it is; after (1) the model should no longer take that
   path for q04, so the row is closure evidence, not a fix input.

**Acceptance.** q04 answered from E4 in ≤ 7 calls with the six monthly averages in
`EXPECTED.md` Q4; every failed row in the invocation log carries a concrete cause type.

## B — #1676: execute the composition instead of offering a menu

**Changes.**

1. `TOOL_USE_LAYER_TEXT`, one rule (with `WriteGatePromptLayerTest`-style structural pin):
   "When a filter takes one value and you hold a list of values from a prior result, call the
   tool once per value — as the status rule already does — and combine the results. Do not offer
   the user a choice of partial answers when the complete plan fits within the call budget."
   (Lands after A1 so the two `SystemPromptDefaults` edits do not race.)
2. `GET /workorders/search`: accept a repeated/comma-separated `status` so one call per customer
   returns every open work order server-side. Full contract chain (controller annotations →
   regenerated `openapi.yaml` → Angular SDK). Update the `searchWorkorders` tool description to
   drop the "loops once per open status" plan and name the multi-status form; update the
   `facade-contract.yaml` template if the query shape changes.
3. Gate coverage for composition, in the runner: record the per-turn tool-call sequence from
   `mcp_tool_invocation_log` (tool name + argument digest) alongside each answer, and give q05 an
   expected minimum plan in `QUESTIONS.json` (`getAgedReceivables` × 1, `searchWorkorders` ≥ 1 per
   past-due customer). A turn that stops after the first call scores FAIL with reason
   "declined composition" rather than an unexplained wrong answer, so this regression class is
   named on the run document.

**Acceptance.** q05 passes on two consecutive runs (it has one pass already); the run document
shows the composed call sequence; a deflection is scored as such.

## A3 — #1663: rename the bill-side fields so the name carries the semantics

**Decision.** Rename at the source, no compatibility shim: `CLAUDE.md`'s pre-production policy
answers the version question the issue deferred. `billCount` → `billsIssuedInWindow`,
`avgBillAmount` → `avgIssuedBillAmount`.

**Changes.** `VendorSpendRow`, `VendorSpendReport`, `AccountingAnalyticsService[Impl]`,
`VendorBillRepository` javadoc, `AccountingAnalyticsController` OpenAPI text → regenerate
`pos-accounting/openapi.yaml` → Angular SDK (check for consumers; `durion` has none);
`AccountingFacadeTool.getVendorSpend` description (shorter: the name now does the work the
capitals were doing); `EXPECTED.md` Q15/Q17 and `eval/tool-response/seed.json` where they name
the field.

**Tests.** `AccountingFacadeToolTest` pins that the description names both fields and that the
word "paid" does not appear within the bill-side sentence; pos-accounting contract tests follow
the rename. A deterministic test of the *model's* column heading is not possible; instead the
gate grader notes for q15/q17 gain a rule — FAIL if a bill-side column heading says "paid" —
so the rendered wording is scored on every live run.

**Acceptance.** No rendered answer on the next gate run labels the bill-side figures as paid.

## Closure evidence (Wave C)

One alpha gate run against the Track B seed with A1, A2, A3 and B deployed; run document in
`docs/gate-runs/wave-2/`; expected movement 4/12 → ≥ 9/12 (q04, q05, q09, q12, q15 flip; q07,
q17 stay UNSCORABLE per #1671's ground-truth notes unless #1663's grader rule and the resolver
change that). Close each issue with the row from that document.
