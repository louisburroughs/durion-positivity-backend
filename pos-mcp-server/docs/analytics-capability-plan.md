# Analytics Capability Plan — Waves 1–3

Status: proposed
Date: 2026-08-30
Baseline: `main` @ 10b20358 — **0 of 20** audit questions answerable end-to-end
Companion audit: https://claude.ai/code/artifact/0b35718c-496d-400a-959b-e494a75ae2e1
Related: `docs/tool-selection-architecture.md`, `docs/permission-based-tool-selection-spec.md`

## 1. Purpose

Twenty natural-language business questions (Section 6) were scored against the pos-mcp-server
tool surface and the backend endpoints behind it. None pass today. The failures are structural,
not prompt- or model-quality issues, and reduce to six gaps:

| Gap | Summary | Evidence |
|---|---|---|
| G1 | No aggregate anywhere in the platform is dimensioned by customer, employee, technician, or vendor — only GL-account-shaped reports exist | `pos-accounting` `FinancialReportingController.java:69–560` |
| G2 | All 16 facade tools are `getX(uuid)` or free-text `search(q)`; none accept a date range, group-by, sort, or limit | `internal/orchestration/tools/` |
| G3 | Tool descriptions promise filters the backing API lacks (e.g. `searchInvoices` claims status/date/amount filtering; controller accepts only `q`) | `InvoiceFacadeTool.java:41`, `pos-invoice` `InvoiceSearchController.java:77` |
| G4 | Candidate tool set is embedded once per turn and capped at 8; the agent cannot discover a tool mid-reasoning | `OpenApiToolProvider.java:206–222`, `application-alpha.yml:222` |
| G5 | No compute primitive — every sum, ratio, and date bucket happens in-context over raw JSON | no aggregation tool exists |
| G6 | Hard row caps truncate silently (`getInvoicesByCustomer` = newest 200 line items, no truncation signal) | `InvoiceFacadeTool.java:50–55` |

This plan retires those gaps in three dependency-ordered waves. **Wave 1 is pos-mcp-server
only. Waves 2 and 3 are predominantly backend work; no amount of MCP-side tuning substitutes
for them.** The twenty questions are the acceptance gates: a wave exits only when its assigned
questions pass the evaluation protocol in Section 2.

## 2. Test-gate methodology

The questions Q1–Q20 replace ad-hoc smoke checks as the acceptance mechanism for this work.

### 2.1 Pass criteria (all four required)

A question **passes** when a single chat request through `POST /v1/mcp/chat` (and again through
`/v1/mcp/chat/stream` — transport parity is required):

1. **Correct answer.** The response's figures match ground truth computed by SQL directly
   against the fixture data (Section 2.3). Tolerance: exact for counts and currency, ±0.5 % for
   derived ratios (DSO, margins, revenue-per-hour) to absorb rounding.
2. **Honest tool trace.** The `mcp_tool_invocation` log (#1422 recorder) shows only real
   parameters — no fabricated filter arguments that the backing endpoint ignores (the G3
   failure mode). Verified by comparing recorded tool inputs against each endpoint's actual
   parameter list.
3. **Bounded cost.** Tool-call count and total context stay within the per-question budget in
   the Section 6 matrix. A "correct" answer produced by paging an entire year of invoices into
   context is a fail.
4. **No silent truncation.** If any tool response was capped, the answer must acknowledge the
   limitation rather than present a truncated figure as complete.

**Partial pass** (used only for Wave 1 exit): the criteria above applied to the reduced scope
named in the matrix (e.g. Q10 "past-due trend only").

### 2.2 Evaluation runs

- Environment: alpha stack (or local `docker` profile with the same fixture load), evaluated
  via the existing durion-eval harness pattern — minted token per role, scripted chat calls,
  trace pull from the invocation log.
- Caller: a `ROLE_SHOP_MANAGER`-equivalent principal whose perm_bits grant all read permissions
  the questions touch. A second run with a deliberately under-permissioned caller must show the
  relevant tools gated out and the answer degrading honestly (no cross-permission leakage).
- Each gate run is recorded: question id, pass/fail per criterion, tool trace, token cost.
  Results table checked in under `docs/gate-runs/` per wave.

### 2.3 Fixture dataset

Deterministic seed spanning **25 months** (YoY questions need 24; +1 for month-boundary
safety), sized so every ground truth is hand-checkable:

- 3 technicians, 6 customers (2 commercial, 4 individual), 3 vendors, 1 location.
- ~120 work orders with known creation/completion/reopen timestamps (Q3 needs ≥2 reopens
  inside 7 days for one specific technician; Q4 needs a known monthly drift in WO→invoice lag).
- ~150 invoices with line-level labor/parts split, known payment applications at controlled
  lags (populate all four Q12 cohorts), and a designed 60+-days-past-due balance for exactly
  2 customers (Q5, Q13 Pareto shape: one customer holds ~45 % of A/R).
- Vendor bills with due dates inside the next 14 days of the eval "as-of" date (Q16) and a
  vendor whose average bill is +12 % YoY (Q17).
- Ground truth: one SQL script per question, checked in beside the fixtures, run against the
  seeded Postgres. The script *is* the specification of the expected answer.

Fixture load follows the `phase0-fixtures-and-telemetry.md` conventions.

## 3. Wave 1 — stop lying, ship what exists (pos-mcp-server only)

Goal: make the existing surface honest and put the platform's one real aggregate in front of
the agent. No backend changes.

### W1.1 Facade description honesty audit (G3)

- Audit all 16 facade tools: diff every `@Tool`/`@ToolParam` description against the backing
  controller's actual signature. Produce the diff table in the PR description.
- Known fixes: `searchInvoices` (claims status/customer/date/amount; reality: free-text `q`
  only), `searchWorkorders` (claims customer/status/vehicle criteria; reality: free-text `q` —
  the structured `customerId`/`vehicleId` params exist on the controller but the facade doesn't
  pass them; either expose them as tool params or stop implying them).
- Rewritten descriptions must state what the parameter *cannot* do ("free-text match on
  customer name or workorder id; no date or status filtering") — negative capability statements
  are what stop the model fabricating filters.

### W1.2 Aging facades (G1 partial, G4)

- Add to `AccountingFacadeTool`: `getAgedReceivables(asOfDate)` and `getAgedPayables(asOfDate)`
  calling `GET /v1/accounting/reports/financial/aged-receivables|aged-payables?asOfDate=`.
- Descriptions must spell out the row shape (`customerId/vendorId, name, current, days31To60,
  days61To90, days90Plus, totalOutstanding`) and the key trick: calling with *historical*
  as-of dates reconstructs point-in-time balances (this is what makes Q10/Q14 trends possible).
- Seed `mcp_tool` rows + `mcp_tool_permission` mappings (migration; follow the V37 rederivation
  pattern, permission from the endpoint's `x-required-permissions`).

### W1.3 Truncation signalling (G6)

- `getInvoicesByCustomer`: wrap the response in an envelope carrying `truncated: boolean` and
  `coveredFrom`/`coveredTo` (the date span of the 200 newest lines actually scanned). Update
  the description: "bounded to the newest 200 line items — check `truncated` before treating
  totals as complete."
- Sweep the other facades for undocumented caps (paginated search defaults included) and apply
  the same envelope or an explicit "first page only" description.

### W1.4 Tool-window relief (G4, first half)

- Raise `mcp.agent.candidate-tool-limit` from 8 → 16 on alpha; measure selection quality via
  the existing `nlti.request.telemetry` events before/after.
- Spike (timeboxed, separate PR): re-resolve `OpenApiToolProvider.resolveToolCallbacks` per
  tool-calling round instead of once per turn, so an agent's intermediate reasoning can pull
  fresh tools. Ship only if the leakage-prevention contract (request-scoped context,
  fail-closed) demonstrably holds per round. If deferred, record why; Wave 3 compositions
  reduce the pressure on this.

### Wave 1 exit gate

| Question | Requirement |
|---|---|
| Q13 (A/R Pareto + past-due share) | **Full pass.** 1 aging call; model sorts, accumulates to 80 %, computes past-due share per row. |
| Q5 (open WOs for 60+-day customers) | **Partial.** Aging identifies the customers; per-customer `searchWorkorders(customerId)` with in-context status filtering; ≤ 12 tool calls; answer flags that "open" was filtered client-side. |
| Q10 (rising sales + rising past-due) | **Partial.** Past-due trend via 3 historical aging calls passes; answer states the sales-trend half is not yet answerable. |
| Q14 (A/R balance + DSO monthly) | **Partial.** Balance trend via 12 historical aging calls; DSO deferred; context stays within budget. |
| Regression | All previously working facade behaviors unchanged (existing facade tests + ArchitectureTest). |

## 4. Wave 2 — dimensioned single-window aggregates (backend)

Goal: give the platform aggregates in the shape the questions ask for. The template is the
aged-receivables row: **one row per counterparty, a date window, pre-summed columns.** Every
Wave 2 endpoint takes `startDate`/`endDate` (or `asOfDate`) and returns a bounded list; the
model may loop it over a handful of periods (≤ 6 calls). Twelve-plus-period questions wait for
Wave 3's `groupBy`.

### W2.1 New endpoints

| # | Module | Route (v1) | Params | Row shape | Unblocks |
|---|---|---|---|---|---|
| E1 | pos-invoice | `GET /invoices/analytics/revenue-by-customer` | startDate, endDate, limit | customerId, name, revenue, invoiceCount, avgInvoiceValue, lastInvoiceDate | Q7, Q8, Q9, Q10 |
| E2 | pos-invoice | `GET /invoices/analytics/collections` | startDate, endDate | invoiced, collected, collectionRatePct (single window) | Q11, Q18 (A/R side) |
| E3 | pos-invoice | `GET /invoices/analytics/payment-lag-cohorts` | issuedFrom, issuedTo | cohort (≤30 / 31–60 / 61–90 / unpaid), invoiceCount, amount | Q12 |
| E4 | pos-invoice | `GET /invoices/analytics/invoicing-lag` | startDate, endDate | avgDaysWoCreationToInvoice, count (single window; model loops months) | Q4 |
| E5 | pos-workorder | `GET /workorders/analytics/technician-labor` | startDate, endDate | technicianId, name, completedWoCount, billedHours, laborRevenue | Q1, Q2, Q19 |
| E6 | pos-workorder | `GET /workorders/analytics/reopened` | startDate, endDate, withinDays | technicianId, woId, completedAt, reopenedAt | Q3 |
| E7 | pos-workorder | `GET /workorders/status-transitions` | woId or (from,to,startDate,endDate) | woId, fromStatus, toStatus, at, actorId | Q3 backing projection |
| E8 | pos-accounting | `GET /accounting/analytics/vendor-spend` | startDate, endDate | vendorId, name, paidAmount, billCount, avgBillAmount | Q15, Q17, Q18 (A/P side) |
| E9 | pos-accounting | `GET /accounting/vendor-bills` | dueFrom, dueTo, status, pageable | billId, vendorId, dueDate, amount, status | Q16, Q17 (today: only `/{billId}` exists — no list route at all) |
| E10 | pos-accounting | `GET /accounting/payment-applications` | appliedFrom, appliedTo, pageable | applicationId, paymentId, invoiceId, appliedAt, amount | Q9 (days-to-pay), Q11 audit |
| E11 | pos-invoice | invoice search: add `status`, `issuedFrom`, `issuedTo`, `customerId` params | — | existing `Page<InvoiceSearchResult>` | retires most of G3 |
| E12 | pos-workorder | WO search: add `status`, `createdFrom`, `createdTo`, `technicianId` params | — | existing `Page<WorkorderSearchResult>` | Q5 full, retires G3 remainder |
| E13 | pos-workorder | `GET /workorders/analytics/customer-margin` | startDate, endDate | customerId, revenue, partsCost, laborCost, grossMargin | Q6 (see D2) |

### W2.2 Cross-cutting requirements (every endpoint)

- `@EmitEvent` with a new event id per endpoint, registered in the module's `{Module}EventTypes`
  (`search` or `fastRead` preset) — CLAUDE.md is non-negotiable here.
- Permission per endpoint, `domain:analytics:view` pattern (e.g. `invoice:analytics:view`,
  `workorder:analytics:view`, `accounting:analytics:view`), added to the module's
  `{Module}PermissionRegistry` and to `x-required-permissions` in its `OpenApiConfig` — the
  MCP permission gate derives from that extension.
- Result caps are explicit: `limit` param with documented default; when applied, the response
  says so (Wave 1's truncation contract, applied from birth).
- Regenerate OpenAPI (`generate-openapi`) so MCP discovery ingests the operations; verify each
  appears in `mcp_tool` after `ToolRegistrationServiceImpl` bootstrap, with the correct
  permission mapping (fail-closed check: an unmapped op must not be callable).
- ArchUnit: full module `ArchitectureTest` + `pos-archunit` `ArchitectureTests` after any new
  entity/package.

### W2.3 MCP-side wiring

- Promote the highest-traffic aggregates to facades (E1, E5, E8 at minimum) with
  period-literate descriptions reusing `ReportingPeriods`; leave the rest to OpenAPI discovery
  and confirm via gate runs that they win selection slots for their questions. If selection
  telemetry shows misses, promote more.

### W2.4 Design decisions

- **D1 — technician labor revenue source (E5).** Option A: compute inside pos-workorder from
  its own billed labor entries (rate × hours) — single-module, ships now, may diverge from
  invoiced amounts under discounts. Option B: event-fed replica of invoice labor lines
  (precedent: the job-time-totals replica, ADR-0044 §6 / #875, see
  `PeopleReportsServiceImpl.java:382`). **Recommendation: A for Wave 2 with the variance
  documented in the tool description; B as a follow-up ADR if variance matters.**
- **D2 — customer margin (E13) parts cost.** Parts cost lives on WO parts-usage lines;
  revenue on invoices. If invoice lines carry `workorderId` (verify first), pos-workorder can
  own the margin projection via its own data + a bounded lookup; otherwise this becomes an
  event-fed replica and may slip to Wave 3. Do the verification before committing the route.
- **D3 — cash-application ownership (E2/E10).** pos-invoice `PaymentController` vs
  pos-accounting `InvoicePaymentController`/`PaymentApplicationController` both touch
  payments. Establish which module is authoritative for *customer cash received* before
  building E2; the answer decides whether Q18's two sides come from one module or two.
- **D4 — status-transition storage (E7).** If a WO status history table already exists, expose
  it; if only current-state exists, add an append-only transition table written on every
  lifecycle change (backfill not required for the gate — fixtures are seeded fresh).

### Wave 2 exit gate

Full pass required: **Q1, Q3, Q4, Q5, Q6, Q7, Q8, Q9, Q11, Q12, Q15, Q16, Q17, Q18.**
(Q4/Q11 pass via ≤ 6-call loops over single-window endpoints; Q5 upgrades from partial via E12;
Q6 contingent on D2 — if D2 forces the replica path, Q6 formally moves to the Wave 3 gate and
that move is recorded in the gate-run notes, not silently absorbed.)

Under-permission run: caller lacking `invoice:analytics:view` must get zero analytics tools and
an honest "not authorized" degradation on Q7.

## 5. Wave 3 — periodization and composition

Goal: collapse N-period questions into one call, and multi-domain rollups into one composed
tool. This is what retires G5 — bucketing moves to SQL where it belongs.

### W3.1 `groupBy` on Wave 2 aggregates

- Add `groupBy=month|week` to E1, E2, E4, E5, E8 (and E13 if shipped). Response becomes one
  row per (period × dimension). Twelve months of collections = 1 call, 12 rows.
- Aging endpoints: add a batch form `asOfDates=[...]` (or `monthEnds=start,end`) so Q14's
  twelve point-in-time snapshots are one call.
- Facade/tool descriptions updated with an explicit steering line: "for more than 3 periods,
  use groupBy — do not loop this tool." Gate criterion 3 (bounded cost) enforces it.

### W3.2 Compositions

- Extend `ToolComposition` (existing pattern: `revenueReport`, `customerHistory`,
  `financialSummary`) with:
  - `technicianPerformance(period[, months])` → E5 grouped + completed-WO detail (Q2).
  - `customerEfficiency(startDate, endDate)` → E1 ⨝ E5-by-customer → revenue per technician
    hour (Q19).
  - `businessSummary(months)` → parallel fan-out: E1 totals, WO completions, E5 hours, E2
    collections, aging batch, E8 vendor spend, A/P payments — one shaped object, per month,
    seven metrics (Q20). Degraded-member semantics follow the existing composition contract
    (`.require(...)` on the members that make the answer meaningless when absent).
- Trend *detection* stays in the model: it receives ≤ ~84 pre-aggregated datapoints for Q20
  and flags anomalies in prose. No trend engine in Java.

### W3.3 Tool-window close-out

- With compositions in place, re-measure G4: if `businessSummary` + aging batch fit routine
  analytical intents inside the candidate window, close the per-round re-resolution spike from
  W1.4 either way (ship or formally reject with telemetry evidence).

### Wave 3 exit gate

Full pass required: **Q2, Q10, Q14, Q19, Q20** (+ Q6 if deferred from Wave 2).
Cumulative regression: **all 20 questions pass in one recorded gate run**, both transports,
plus the under-permissioned degradation run. That run's results table is the closing artifact
of this plan.

## 6. Gate matrix — the twenty questions

Budget = max tool calls for criterion 3. GT = ground-truth SQL script id.

| Q | Question (abbrev.) | Wave | Budget | Pass sketch |
|---|---|---|---|---|
| 1 | Top technicians by labor revenue + avg billed hrs/completed WO, last month | 2 | 2 | E5 one window; model ranks + divides |
| 2 | Technician completed WOs / hours / revenue, 3-month comparison | 3 | 2 | `technicianPerformance` composition (early pass allowed in W2 via 3 × E5) |
| 3 | Most WOs reopened ≤ 7 days of completion, this quarter | 2 | 2 | E6; model counts per technician |
| 4 | Avg WO-creation→invoice time by month, 6 months | 2 | 7 | 6 × E4 (1 call in W3 via groupBy) |
| 5 | Open WOs for customers > 60 days past due | 2 (partial W1) | 4 | aging + E12 (`status=OPEN`, customer filter) |
| 6 | Revenue / parts / labor / margin by customer, last month | 2 (D2 risk → 3) | 2 | E13 one window |
| 7 | Top customers 12 mo vs prior 12 mo | 2 | 3 | 2 × E1 windows; model compares |
| 8 | No purchase 90 days but > $10k prior year | 2 | 3 | E1 prior-year window + `lastInvoiceDate`; model filters |
| 9 | Top 20 customers: revenue, count, avg, balance, days-to-pay | 2 | 4 | E1 + aging + E10-derived lag |
| 10 | Rising sales and rising past-due, 3 months | 2 (partial W1) | 7 | 3 × E1 + 3 × aging; W3: 2 calls |
| 11 | Weekly invoiced vs collected, 12 weeks, rate | 2→3 | 13→2 | W2: 12 × E2 loop (at budget edge); W3: E2 `groupBy=week`, 1 call |
| 12 | Payment-lag cohorts, 6 months | 2 | 2 | E3 one window |
| 13 | Customers = 80 % of A/R + past-due share | **1** | 2 | aging facade; model Pareto |
| 14 | A/R balance + DSO at each month-end, 12 mo | 3 (partial W1) | 3 | aging batch + income statements; DSO in model |
| 15 | Top vendors 6 mo vs same 6 mo last year | 2 | 3 | 2 × E8 windows |
| 16 | Vendor bills due ≤ 14 days, daily cash need | 2 | 2 | E9 by due window; model buckets by day |
| 17 | Vendors with avg bill +10 % YoY | 2 | 3 | 2 × E8 (`avgBillAmount`); model compares |
| 18 | Weekly cash in vs out, last quarter, negative weeks | 2→3 | 27→3 | W2 loops are over budget → formally a Wave 3 gate (E2 + A/P weekly groupBy) |
| 19 | Revenue vs technician hours by customer, revenue/hour | 3 | 2 | `customerEfficiency` composition |
| 20 | 12-month business summary, 7 metrics, trend flags | 3 | 2 | `businessSummary` composition; trends in prose |

## 7. Risks

- **Selection misses (G4 residue).** New discovered ops may lose embedding slots to old
  facades. Mitigation: W2.3 facade promotion + telemetry-driven adjustment; every gate run
  records the selected-tool set.
- **D2/D3 ownership calls.** Wrong module choice for margin/cash-application forces rework
  across the service boundary. Both are verify-first decisions with an explicit ADR if a
  replica is needed.
- **Fixture drift.** Ground-truth SQL and fixtures must move together; treat a fixture change
  without a GT change (or vice versa) as a review blocker.
- **Description rot recurrence (G3).** After W1, add a lightweight test that asserts every
  facade `@Tool` description's named parameters exist on the backing route's parameter list
  (string-level check against the OpenAPI spec is sufficient to catch the class).
