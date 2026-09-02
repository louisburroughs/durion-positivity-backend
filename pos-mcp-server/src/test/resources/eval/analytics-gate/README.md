# Analytics gate — fixture dataset & ground truth

Serves `pos-mcp-server/docs/analytics-capability-plan.md` §2 (test-gate methodology), §2.3
(fixture dataset) and §6 (the twenty-question gate matrix).

The tool-selection half of the gate lives in `../tool-selection/analytics-gate.json` and
`../tool-selection-pending/analytics-gate-pending.json` and needs no database. **This**
directory is the *answer* half: the seeded business dataset a question is asked against, and one
ground-truth SQL script per question that computes the expected answer directly against the
seeded Postgres. Per plan §2.1 criterion 1, the script *is* the specification of the expected
answer — a chat response passes only when its figures match the script's output (exact for
counts and currency, ±0.5 % for derived ratios).

## Status (2026-09-02)

- Seed: WRITTEN and APPLIED to alpha (2,257 rows, 5 databases) — `seed/`, marker-scoped and
  idempotent; regenerate with `python3 seed/generate_seed.py`, apply with `seed/apply_seed.sh`.
- Ground truth: ALL 20 scripts written (`ground-truth/q01..q20.sql`), executed live on alpha
  2026-09-02 (26/26 sections clean, `ground-truth/runs/2026-09-02-alpha-run.log`).
- `ground-truth/EXPECTED.md` is the gate reference sheet, re-derived from that live run under the
  recorded co-tenancy policy. Fixtures and ground truth change together — a change to one without
  the other is a review blocker.

## Change rule (plan §7, "fixture drift")

Ground-truth SQL and the fixture dataset move together. A fixture change without a matching
ground-truth change — or a ground-truth change without a fixture change — is a review blocker.
The same applies to the tool-selection fixtures: shipping a Wave 2/3 endpoint means promoting its
question out of `tool-selection-pending/` in the same PR.

## Dataset shape (plan §2.3)

Deterministic seed spanning **25 months** — 24 for the year-over-year questions, +1 for
month-boundary safety — sized so every ground truth is hand-checkable.

| Dimension | Count | Why |
|---|---|---|
| Locations | 1 | keeps every aggregate single-location; no cross-location roll-up in the gate |
| Technicians | 3 | Q1/Q2/Q3/Q19 rank and compare technicians; 3 is the smallest set with an unambiguous ranking |
| Customers | 6 (2 commercial, 4 individual) | Q13's Pareto needs a skewed but hand-checkable distribution |
| Vendors | 3 | Q15/Q17 rank vendors and compare year over year |
| Work orders | ~120 | known creation / completion / reopen timestamps |
| Invoices | ~150 | line-level labor/parts split, payment applications at controlled lags |

Designed-in facts the questions depend on:

- **Reopens (Q3).** At least two work orders reopened **within 7 days** of completion for one
  specific technician, so the "most reopened" answer is unambiguous.
- **Invoicing lag (Q4).** A known month-over-month drift in the work-order-creation → invoice
  interval across the 6-month window, large enough to be visible above rounding.
- **Payment-lag cohorts (Q12).** Payment applications at controlled lags populating **all four**
  cohorts: paid ≤30 days, 31–60, 61–90, and still unpaid.
- **Past due (Q5, Q13).** Exactly **2 customers** carrying a 60+-days-past-due balance.
- **A/R Pareto (Q13).** One customer holds **~45 %** of total A/R, so the 80 %-cumulative cutoff
  falls at a specific, hand-checkable row and is not sensitive to a rounding tie.
- **A/P due window (Q16).** Vendor bills with due dates inside the **next 14 days** of the eval
  "as-of" date, spread over several distinct days so the daily cash-need buckets are non-trivial.
- **Vendor inflation (Q17).** One vendor whose average bill amount is **+12 % year over year** —
  above the question's 10 % threshold, below a level that makes the comparison trivial.
- **Revenue trend (Q10).** At least one customer with rising invoiced revenue *and* a rising
  past-due balance across the last three months, and at least one decoy with only one of the two.

## Table ownership

Each service owns its own schema; there are no cross-service foreign keys, and a replica table
(`ext_*`) is written from events, never joined across a database boundary. A ground-truth script
must therefore run against **one** module's schema, or be split into per-module scripts whose
results are combined by the script's author — never a cross-schema join.

| Table | Owning module | Notes |
|---|---|---|
| `workorder`, `workorder_service`, `work_order_state_transitions` | pos-workorder | WO lifecycle; the transition table backs Q3's reopen detection (plan D4) |
| `invoices`, `invoice_items`, `invoice_adjustments`, `receipts` | pos-invoice | authoritative invoice + line-level labor/parts split |
| `ext_invoice` | pos-accounting | event-fed replica of `invoices`; the **A/R aging reads this**, not pos-invoice |
| `payment_application`, `payment_application_reversal` | pos-accounting | cash applied to an invoice, and reversals |
| `credit_memo`, `customer_credit_transaction` | pos-accounting | the other two balance reducers (`InvoiceBalanceCalculator`) |
| `vendor_bill` | pos-accounting | A/P aging and Q15/Q16/Q17 vendor spend |
| `person_party`, `commercial_party`, `party_alias` | pos-customer | customer identity and display names |
| pos-people employee tables (`work_session`, time-entry tables) | pos-people | technician identity and clocked hours |
| `ext_vehicle`, `ext_location`, `ext_workorder`, `ext_people_employee`, … | consuming module | replicas; useful for a *display name*, never as the source of a monetary fact |

Ownership consequence for seeding: an invoice must be seeded into **both** pos-invoice
(`invoices`) and pos-accounting (`ext_invoice`) — either by letting the event flow do it, or by
seeding both sides with identical ids and totals. A seed that populates only `invoices` produces
an empty A/R aging report and a silently wrong Q13.

## Known plan-vs-code conflicts to account for when seeding

1. **`customerName` is always null.** `FinancialReportingServiceImpl.generateAgedReceivables`
   builds each row with `.customerName(null)` ("no directory lookup in this slice"), even though
   the DTO, the OpenAPI schema, and the `getAgedReceivables` `@Tool` description all promise a
   name. Ground truth must key on `customerId`; any expected answer naming customers has to
   resolve names out of band (pos-customer), and the gate's "correct answer" criterion should not
   require the model to produce names until the lookup ships.
2. **Historical as-of dates are not point-in-time balances.** The same method uses each invoice's
   **current** balance (all payment applications / reversals / credit memos to date) against a
   *historical* aging date. Plan §3 W1.2 and the tool description both claim a past `asOfDate`
   "reconstructs the point-in-time A/R balance"; it does not. Q10's and Q14's trend halves will
   therefore be arithmetically wrong on any dataset where payments land after the earlier as-of
   dates — which is exactly what the controlled payment lags create. Either the seed keeps
   post-as-of payments out of the trend window, or the gate records this as a known-fail until a
   true point-in-time replay ships.
3. **Aging date is the due date (changed by #1604 — re-check existing seed data).** Receivable
   aging uses `ext_invoice.due_date`, falling back to the document date
   (`invoice_created_at` → `finalized_at` → `updated_at`) only when `due_date` is null. "60 days
   past due" in Q5/Q13 therefore means 60 days past the due date, the same measure A/P already
   used. Two consequences for seeding:
   - The two designed 60+-days-past-due customers must carry **real `due_date` values** on their
     invoices, set 61+ days before the gate's `asOfDate`. Leaving `due_date` null makes the
     invoice age by its invoice date instead, which is a different (usually earlier-aging) figure.
   - Not-yet-due invoices are no longer dropped: they land in `current`. A seed can now include
     future-due invoices deliberately, and they will show up in `current` and in
     `totalOutstanding`. What is still excluded is an invoice whose **document** date is after
     `asOfDate` — it did not exist yet.

   **Seed data written to the previous instruction is now wrong.** Any fixture built to age by
   `invoice_created_at` (null `due_date`s, or due dates chosen to be ignored) will produce
   different buckets under the corrected rule and must be revisited before it is used as ground
   truth. See `docs/gate-runs/2026-09-01-ar-aging-basis-change.md`.
