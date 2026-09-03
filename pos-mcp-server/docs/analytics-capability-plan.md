# Analytics Capability Plan — Waves 1–3

Status: Wave 1 delivered; Waves 2-3 proposed
Date: 2026-08-30
Baseline: `main` @ 10b20358 — **0 of 20** audit questions answerable end-to-end
Last updated: 2026-08-30 (Wave 1 outcome; D1-D4 resolved)
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
- **Questions come from the versioned set, never from an ad-hoc list (#1671).** The verbatim text
  is `pos-mcp-server/src/test/resources/eval/analytics-gate/QUESTIONS.json`, one entry per `## QN`
  section of `analytics-gate/ground-truth/EXPECTED.md`; `scripts/analytics_gate_run.py` reads it
  and no other source. A question whose window the text does not resolve is a harness defect, not
  a model defect — it makes the answer unscorable however well the model performs.
- Each gate run is recorded: question id, pass/fail per criterion, tool trace, token cost, **and
  the git blob sha of the questions file the run used**. Without that sha a re-run can silently ask
  something different and move the score with no code change, and a regression cannot be told apart
  from a reworded question. Results table checked in under `docs/gate-runs/` per wave.

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
  as-of dates re-ages the buckets. **Correction (2026-08-30):** the shipped description originally
  claimed a past as-of date reconstructs the point-in-time balance. It does not —
  `FinancialReportingServiceImpl.generateAgedReceivables` documents this as a KNOWN LIMITATION:
  each invoice contributes its **current** open balance, and only the bucket boundaries move with
  `asOfDate`, and invoices raised after it are excluded entirely — so a past-dated total is
  neither today's figure nor that date's. Two further corrections from the same read:
  `customerName` is hardcoded `null` on receivables rows (payables rows do carry `vendorName`),
  and A/R ages from invoice creation while A/P ages from due date. Tool descriptions corrected;
  Q10/Q14 partials withdrawn below.

  **Resolved (2026-09-01, issue #1604) — A/R aging basis.** The defect recorded above (A/R ignoring
  `due_date` while A/P used it, so a not-yet-due invoice raised 45 days ago was reported as "31-60
  days past due") is fixed in `FinancialReportingServiceImpl`. Both halves of the aging report now
  apply one rule:
  - **Aging basis** = the document's due date, falling back to the document date when the due date
    is null (A/R document date `invoice_created_at` → `finalized_at` → `updated_at`; A/P `bill_date`).
    `ext_invoice.due_date` is nullable on drafts and on replica rows built from events predating
    `V22__ext_invoice_due_date.sql`, so the fallback is permanent, not transitional.
  - **Existence filter re-based.** "Raised after `asOfDate` → excluded" is now tested on the
    *document* date, not the aging date, so it means "not raised yet" rather than "not due yet".
  - **Not-yet-due amounts are included, in `current`.** `daysPastDue` may be negative and negatives
    satisfy the `<= 30` test — which is what `AgedReceivablesRow.current`'s schema always promised
    ("includes not-yet-due"). On **A/P** that is a real widening: `payableAgingDate` already used the
    due date, so the old guard dropped not-yet-due bills entirely. On **A/R** nothing is added — the
    old aging date *was* the document date, so the old and new guards are the same predicate; the A/R
    inclusion set, row count, `totalOutstanding` and grand totals are unchanged and only the bucket
    split moves. A not-yet-due invoice raised in the past was never dropped from A/R; it was
    mis-bucketed as `days31To60`, which is the defect #1604 reports.

  Bucket boundaries are unchanged (`current` ≤ 30, 31–60, 61–90, > 90). The prediction above held:
  the fix shifts every A/R bucket and invalidated the Q13 ground-truth SQL, which has been rewritten
  to the new rule; the recorded Wave 1 Q13/Q5 outcomes below predate it and are not comparable.
  A/P bucket totals moved too, because not-yet-due vendor bills are no longer dropped. Tool
  descriptions on both `getAgedReceivables` and `getAgedPayables` were corrected in the same change
  (the "the two reports are not the same measure" caveat is now false and removed). Full record:
  `docs/gate-runs/2026-09-01-ar-aging-basis-change.md`.
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

### Wave 1 outcome (2026-08-30)

Shipped in PR #1587 and deployed to alpha. W1.1–W1.4 all landed; `listUsers` was additionally
found to be GETting the gateway root and given a real path.

The exit-gate smoke test then paid for the whole wave: asking Q13 on alpha returned
`selected:["AdminFacadeTool"], candidateCount:1` while the router had correctly classified
`domain:accounting`. Cause: `account`/`accounts` were bare admin fast-path keywords, and that
path returns `AdminFacadeTool` **alone** — so every accounting question containing the word had
all other candidates suppressed, making the aging facades unreachable. Fixed in PR #1588.

Two lessons folded into the waves below:
1. **Run the Q-gates as soon as a wave deploys, not at the end.** One question exposed a defect
   that had silently made an entire domain unreachable through chat.
2. **A tool-selection fixture would have caught it in CI with no database.** Answer-correctness
   fixtures (§2.3) are expensive; selection fixtures are nearly free and catch a whole class of
   routing regressions. Both tracks are now in scope, selection first.

Three alpha deploy-config defects were also found and fixed live (port-less gateway URLs, and
`POS_SECURITY_BASE_URL` routed through the gateway so permission registration had never
succeeded). They predate this work but are recorded because they are not in any repo file and
will return if that `.env` is regenerated.

### Wave 1 exit gate

| Question | Requirement |
|---|---|
| Q13 (A/R Pareto + past-due share) | **Full pass.** 1 aging call; model sorts, accumulates to 80 %, computes past-due share per row. |
| Q5 (open WOs for 60+-day customers) | **Partial.** Aging identifies the customers; per-customer `searchWorkorders(customerId)` with in-context status filtering; ≤ 12 tool calls; answer flags that "open" was filtered client-side. |
| Q10 (rising sales + rising past-due) | **Withdrawn.** Rested on a past-due *trend* from historical aging calls, which the report cannot produce — balances are current, not point-in-time. Moves to Wave 3 behind real historical reconstruction. |
| Q14 (A/R balance + DSO monthly) | **Withdrawn**, same cause. A twelve-month A/R balance trend needs point-in-time balances no endpoint produces today. |
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
| E2 | **pos-accounting** | `GET /accounting/analytics/collections` | startDate, endDate | invoiced, collected, applicationReversals, collectionRatePct, refunded, netCashCollected, received, nonCashSettled, settled, settlementRatePct (single window; W2.5 delivery note) | Q11, Q18 (A/R side) — module set by D3 |
| E3 | pos-invoice | `GET /invoices/analytics/payment-lag-cohorts` | issuedFrom, issuedTo | cohort (≤30 / 31–60 / 61–90 / unpaid), invoiceCount, amount | Q12 |
| E4 | pos-invoice | `GET /invoices/analytics/invoicing-lag` | startDate, endDate | avgDaysWoCreationToInvoice, count (single window; model loops months) | Q4 |
| E5 | pos-workorder | `GET /workorders/analytics/technician-labor` | startDate, endDate | technicianId, name, completedWoCount, billedHours, laborRevenue | Q1, Q2, Q19 |
| E6 | pos-workorder | `GET /workorders/analytics/reopened` | startDate, endDate, withinDays | technicianId, woId, completedAt, reopenedAt | Q3 |
| E7 | pos-workorder | `GET /workorders/status-transitions` | woId or (from,to,startDate,endDate) | woId, fromStatus, toStatus, at, actorId | Q3 backing projection — **table already exists**, see D4 |
| E8 | **pos-accounting** | `GET /accounting/analytics/vendor-spend` | startDate, endDate | vendorId, name, paidAmount, billCount, avgBillAmount | Q15, Q17, Q18 (A/P side) |
| E9 | pos-accounting | `GET /accounting/vendor-bills` | dueFrom, dueTo, status, pageable | billId, vendorId, dueDate, amount, status | Q16, Q17 (today: only `/{billId}` exists — no list route at all) |
| E10 | **pos-accounting** | `GET /accounting/payment-applications` | appliedFrom, appliedTo, pageable | applicationId, paymentId, invoiceId, appliedAt, amount | Q9 (days-to-pay), Q11 audit — module set by D3 |
| E11 | pos-invoice | invoice search: add `status`, `issuedFrom`, `issuedTo`, `customerId` params | — | existing `Page<InvoiceSearchResult>` | retires most of G3 |
| E12 | pos-workorder | WO search: add `status`, `createdFrom`, `createdTo`, `technicianId` params | — | existing `Page<WorkorderSearchResult>` | Q5 full, retires G3 remainder |
| E13 | *deferred to Wave 3* | `…/analytics/customer-margin` | startDate, endDate | customerId, revenue, partsCost, laborCost, grossMargin | Q6 — **moved out of Wave 2 by D2** (5-domain problem: true parts cost lives in pos-inventory) |

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

### W2.4 Design decisions — **RESOLVED 2026-08-30**

All four were settled by reading the code. Three differ from what this plan originally assumed;
the E-table above has been updated accordingly.

- **D1 — technician labor revenue source (E5). Option A is impossible; use a narrow Option B.**
  `WorkorderLaborEntry` (pos-workorder) carries `technicianId` and `hoursWorked` but **no rate
  and no amount**, so rate × hours cannot be computed in-module. Labor revenue exists only
  invoice-side: `InvoiceItem.type` discriminates labor from parts, `InvoiceItem.lineTotal`
  carries the money, and `Invoice.workorderId` joins back to the work order. pos-workorder's
  existing `ExtInvoiceReplica` carries only subtotal/tax/total — no labor/parts split.
  **Decision:** extend that existing replica with `laborTotal`/`partsTotal` (event-fed, ADR-0044
  §6 precedent) rather than building a new replica. E5 then serves hours and revenue from one
  module. Smaller than the Option B originally sketched.
- **D2 — customer margin (E13) parts cost. Q6 moves to Wave 3.** The join is fine
  (`Invoice.workorderId` + `InvoiceItem.type`), but **true parts cost is in neither module**:
  `WorkorderPart.unitPrice` is the *sell* price, and cost lives in pos-inventory (`avgCost`,
  `unitCostSnapshot`, `costAtTimeOfAdjustment`). Margin is therefore a five-domain problem, not
  the three-domain one assumed. Per the escape hatch in the original W2 exit gate, E13/Q6 is
  formally a **Wave 3** deliverable; sourcing cost from pos-inventory needs its own decision
  (event-fed cost replica vs. a costing endpoint) before it is specified.
- **D3 — cash-application ownership (E2/E10): pos-accounting.** It owns `ReceivablePayment`,
  `PaymentApplication`, `PaymentApplicationReversal`, `PaymentAppliedEvent` on the A/R side and
  `APPayment`/`APPaymentAllocation` on the A/P side. **E2 and E10 move from pos-invoice to
  pos-accounting**, which also means Q18's two sides come from one module.

  **Premise corrected 2026-09-01 (issue #1605) — the conclusion stands, the original reason does
  not.** This decision originally read "pos-invoice holds only `PaymentIntent` and `Receipt` —
  pre-settlement artifacts." That sentence is **false and is withdrawn**: pos-invoice also owns
  `DepositCredit`/`DepositCreditApplication` (a draw-down applied against a named invoice — a real
  settlement event) and `RefundRecord` (cash out). Both affect whether an invoice is settled.

  The ownership answer is unchanged, on a corrected rationale: **analytics ownership follows the
  ledger, not the artifact.** `collected` measures A/R relief, and every input to A/R relief is
  accounting-owned — `ReceivablePayment`, `PaymentApplication`, `PaymentApplicationReversal`,
  `CustomerCredit`/`CustomerCreditTransaction`, plus the `ext_invoice` replica that supplies
  `invoiced`. pos-invoice's deposit credits and refunds are cash and liability artifacts; the GL
  entries they cause are posted in accounting. Relocating E2 to pos-invoice would force it to
  replicate `PaymentApplication` and `PaymentApplicationReversal` — replicating the ledger.

  The missed artifacts turn out to contribute **zero** to `collected` by decision rather than by
  oversight (D5), so the corrected premise does not move the numerator. Full semantics, including
  the transport for the fields that are still to come, are recorded in **ADR-0057** (analytics
  money-measure semantics and ownership); D5–D7 below are its plan-side summary.
- **D4 — status-transition storage (E7): already exists.** `WorkorderStateTransition`
  (table `work_order_state_transitions`) persists `fromStatus`, `toStatus`, `transitionedAt`,
  `transitionedBy`, `reason`, `metadata`. `WorkorderStateTransitionRepository` currently exposes
  only per-workorder finders (`findByWorkorder_Id`, `…OrderByTransitionedAtDesc`).
  **E7 collapses to a date-range query method plus the endpoint** — no new table, no backfill.
  Cheapest item in the wave, and it unblocks Q3 outright.

### W2.5 Money-measure semantics — **RESOLVED 2026-09-01 (issue #1605)**

D5–D8 settle what E2's figures mean. D5–D7 were raised by #1605 against D3's premise and decided by
the invoicing, accounting and architecture owners; the durable record is **ADR-0057**
(`durion/docs/adr/0057-analytics-money-measure-semantics-and-ownership.adr.md`), which every later
analytics endpoint must obey.

- **D5 — deposit and customer credit draw-downs (E2): excluded from `collected`, by name and
  permanently.** Counting a draw-down as a collection double-counts cash. A deposit-take order is
  itself invoiced for the deposit amount (`pos-order/.../SalesOrderServiceImpl.java:667-673` sets
  `depositAmount(order.getGrandTotal())` on that same order's invoice request), so the deposit cash
  already entered `collected` through the ordinary invoice → `PaymentSettledV1` → `ReceivablePayment`
  → `PaymentApplication` path, in the take window. The GL agrees: applying a customer credit posts
  `Dr 2300 Customer Credit Liability / Cr 1200 A/R` and touches no cash account. E2 already excludes
  accounting's own `CustomerCreditApplication` draw-downs, so this is the consistent answer, not a
  new exclusion. **Decision:** `collected` is cash only. The exclusion is stated in the endpoint
  description rather than left implicit, because the ratio is genuinely understated in any window
  where deposit-funded invoices finalize. Non-cash settlement gets its own later field
  (`nonCashSettled`/`settled`/`settlementRatePct`), fed from the deposit **and** customer-credit
  sources together — never one alone, because a half-fed field looks complete (#1621).
- **D6 — refunds (E2): measured separately, never netted inside `collected`.** Refund shapes are
  heterogeneous — invoice-linked, standalone with no invoice (#926), and credit-balance refunds — so
  folding one scalar into an A/R-relief numerator would assert a debit-credit mapping no ADR
  authorizes. It would also double-subtract: the commonest shape, a refunded invoice payment,
  produces **both** a `RefundRecord` in pos-invoice **and** a `PaymentApplicationReversal` in
  accounting. **Decision:** refunds sit outside `collected` in their own field (#1620), attributed to the
  refund-completion window, counting `reversalType="REFUND"` only. `"VOID"` is a released
  authorization that never captured and must never appear in E2.
- **D7 — application reversals (E2): movement basis. The shipped behaviour was a defect.**
  `collected` summed every `PaymentApplication` in the window with no reversal handling, so it
  overstated cash by every reversal ever recorded. **Decision:** `collected` = Σ applied amounts
  dated in the window − Σ application-reversal amounts dated in the window. A January payment
  reversed in March reduces March; **January is never restated.** Chosen over E10's
  "exclude reversed applications" because exclusion retroactively rewrites a closed period — against
  `PERIOD_CLOSED`/`PERIOD_HARD_LOCKED` and ADR-0047's correction-by-reversal model — and is not
  additive, so twelve monthly buckets would not sum to the annual figure once W3.1 adds
  `groupBy=week|month`. **E10 keeps its `includeReversed=false` default:** a list answers a
  point-in-time question, E2 measures movement. That divergence is deliberate and documented at both
  endpoints so it is not later "unified" in the wrong direction.

- **D8 — deposit-take invoices (E1/E2): excluded from `invoiced` and revenue, by
  `depositSourceType`. RESOLVED 2026-09-01 (issue #1623).** The Accounting domain owner ruled that
  the deposit-take document — the invoice a deposit-take order renders for the down-payment itself —
  is a **liability event** (advance payment / contract liability under ASC 606), not a revenue
  event: deposit receipt and settlement are two distinct economic events, and only the settlement
  recognizes revenue, gross, for the full workorder amount. So any measure summing invoice totals
  as a revenue proxy (E2 `invoiced`, E1 revenue-by-customer, E13 customer margin when it lands)
  must **exclude** invoices whose `depositSourceType` is non-null — never net the deposit out of
  the settlement invoice, which would understate its gross total and break traceability to the
  workorder price. Implementation: pos-invoice stamps `invoices.deposit_source_type` /
  `deposit_source_id` at from-order creation (backfilled from `deposit_credit.order_id`), the
  marker rides `invoice.invoice.updated` additively within schema v1, pos-accounting replicates it
  to `ext_invoice.deposit_source_type`, and E1/E2 filter on it. **Replica caveat:** pos-invoice has
  no facts/replay endpoint, so `ext_invoice` rows replicated before the enrichment stay unmarked
  until their invoice next emits; historical windows containing pre-existing deposit-take invoices
  remain inflated until a replay mechanism (or one-shot re-publish, which `ReplicaVersionGuard`'s
  equal-version-applies rule was built for) lands. Deliberately not extended to E3 payment-lag
  cohorts: the ruling covers revenue-shaped measures, and whether a deposit-take document belongs
  in payment-behaviour cohorts is a separate question — file a follow-up before touching it.

Follow-ups filed from this decision: **#1620** refunds (`refunded`/`netCashCollected`), **#1621**
non-cash settlement (`nonCashSettled`/`settled`/`settlementRatePct`, both sources together),
**#1622** `received` (true cash receipts, which resolves the A/R-relief-vs-cash base mismatch), and
**#1623** — whether `invoiced` double-counts deposit-take orders — **resolved as D8 above**.

**Delivered 2026-09-01:** #1620, #1621 and #1622 shipped together. E2 now returns
`invoiced` / `collected` / `applicationReversals` / `collectionRatePct` plus `refunded`,
`netCashCollected`, `received`, `nonCashSettled`, `settled` and `settlementRatePct`, fed by two new
ADR-0044 R3 replicas (`ext_invoice_payment_reversal` from `payment.payment.reversed`
REFUND facts, `ext_invoice_deposit_credit_application` from the new
`payment.deposit-credit.applied` fact) and accounting's own `receivable_payment` /
`customer_credit_transaction` subledgers. Q11's ground truth is
`eval/analytics-gate/ground-truth/q11-weekly-invoiced-vs-collected.sql`
(invoiced / settled / settlementRatePct), Q18's is
`ground-truth/q18-weekly-cash-in-vs-out.sql` (received vs refunded + A/P paid) — both written from
these definitions, not the pre-#1605 premise. Deposits remain GL-invisible: per the accounting
ruling on #1621, a deposit draw-down would post to a distinct liability account (2310-style, never
2300), and whether accounting posts deposit GL at all is deliberately out of #1621's scope —
`nonCashSettled` is an analytics figure over the replicas, not a GL-derived one. Also delivered
alongside: **#1629** — deposit-take invoices carry zero tax at the source and
`generateTaxLiability` excludes marked historical rows.

### Wave 2 exit gate

Full pass required: **Q1, Q3, Q4, Q5, Q7, Q8, Q9, Q12, Q15, Q16, Q17.**
(Q4 passes via a ≤ 6-call loop over single-window E4; Q5 upgrades from partial via E12.)

Moved out of this gate by the D-decisions, recorded here rather than silently absorbed:
- **Q6 → Wave 3** (D2: parts cost is in pos-inventory; five-domain problem).
- **Q11, Q18 → Wave 3.** Both need weekly buckets. Looping a single-window E2 twelve times
  (Q11) or twenty-six times (Q18) exceeds the call budgets in §6, so they are only honestly
  passable once `groupBy=week` lands in W3.1. E2 still ships in Wave 2 — it is the endpoint
  W3.1 periodizes.

Under-permission run: caller lacking `invoice:analytics:view` must get zero analytics tools and
an honest "not authorized" degradation on Q7.

## 5. Wave 3 — periodization and composition

Goal: collapse N-period questions into one call, and multi-domain rollups into one composed
tool. This is what retires G5 — bucketing moves to SQL where it belongs.

### W3.1 `groupBy` on Wave 2 aggregates

- Add `groupBy=month|week` to E1, E2, E4, E5, E8 (and E13 if shipped). Response becomes one
  row per (period × dimension). Twelve months of collections = 1 call, 12 rows.
- Aging endpoints: **first make historical as-of dates mean what they say.** Today the report ages
  *current* balances against a past date; a real trend needs point-in-time reconstruction (replaying
  payment applications, reversals and credit memos up to `asOfDate`). That is a pos-accounting
  change and a hard prerequisite for Q10 and Q14 — not a batching problem. Once balances are
  genuinely historical, add a batch form (`asOfDates=[...]` or `monthEnds=start,end`) so twelve
  snapshots are one call.
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

Full pass required: **Q2, Q6, Q10, Q11, Q14, Q18, Q19, Q20** (Q6/Q11/Q18 moved here by the
resolved D-decisions and the call-budget analysis; see the Wave 2 exit gate). Q10 and Q14
additionally depend on the point-in-time balance reconstruction in W3.1 — without it neither
question is answerable at any wave.
Cumulative regression: **all 20 questions pass in one recorded gate run**, both transports,
plus the under-permissioned degradation run. That run's results table is the closing artifact
of this plan.

## 6. Gate matrix — the twenty questions

Budget = max tool calls for criterion 3. GT = ground-truth SQL script id. The wording below is
**abbreviated**; the verbatim text asked, the window each question fixes, and which twelve are in
the chat-path gate live in `pos-mcp-server/src/test/resources/eval/analytics-gate/QUESTIONS.json`
(#1671). `eval/tool-selection/analytics-gate.json` shares this `qNN` numbering but scores tool
selection, not answers — it is not the question list.

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
