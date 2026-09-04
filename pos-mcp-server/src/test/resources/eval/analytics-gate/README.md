# Analytics gate — fixture dataset & ground truth

Serves `pos-mcp-server/docs/analytics-capability-plan.md` §2 (test-gate methodology), §2.3
(fixture dataset) and §6 (the twenty-question gate matrix).

This directory holds the live, deploy-and-run acceptance gate — the one document that says what
the twenty questions are and what a correct answer contains. For a candidate fix, re-running this
whole gate live is the expensive step; `../offline-replay/` (see `../README.md`) is the
development-time counterpart, replaying the twelve `in_chat_path_gate` questions here against a
real model with canned tool responses, no alpha deploy required — see its own `suite_notes` for
how each fixture's canned data maps back to this file's designed/absolute figures.

The tool-selection half of the gate lives in `../tool-selection/analytics-gate.json` and
`../tool-selection-pending/analytics-gate-pending.json` and needs no database. **This**
directory is the _answer_ half: the questions themselves, the seeded business dataset they are
asked against, and one ground-truth SQL script per question that computes the expected answer
directly against the seeded Postgres. Per plan §2.1 criterion 1, the script _is_ the
specification of the expected answer — a chat response passes only when its figures match the
script's output (exact for counts and currency, ±0.5 % for derived ratios).

**Q15/Q17 labeling rule (#1663).** `getVendorSpend`'s `billsIssuedInWindow` and
`avgIssuedBillAmount` count/average bills _issued_ in the window regardless of payment status —
a different population from `paidAmount` (settled A/P cash). Q15 and Q17 score FAIL if the
rendered answer labels either bill-side figure as paid — e.g. a column heading "bills paid" —
even when the underlying numbers are correct, since that mislabels the population.

## The questions: `QUESTIONS.json` (#1671)

`QUESTIONS.json` is the **only** definition of the text the chat-path gate asks. One entry per
`## QN` section of `ground-truth/EXPECTED.md`, in bijection with it, so a question and the ground
truth that scores it are diffable together. Each entry carries:

| Field                                   | What it pins                                                                                                                                            |
| --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `fixture_id` / `expected_section`       | the `qNN` ↔ `## QN` pairing                                                                                                                             |
| `utterance`                             | the verbatim text sent to `POST /v1/mcp/chat`                                                                                                           |
| `ground_truth_sql`                      | the script that is the answer specification                                                                                                             |
| `in_chat_path_gate` / `excluded_reason` | which twelve of the twenty run, and why the other eight do not                                                                                          |
| `window`                                | the shape (`calendar` / `rolling` / `point-in-time` / `mixed`), the range the ground truth measures, and whether the question's own text resolves to it |
| `tool_selection_fixture_id`             | the counterpart in the tool-selection corpus                                                                                                            |
| `expected_plan` (optional)              | for a composition question, the minimum tool calls the correct answer needs — see below                                                                 |

### `expected_plan` and the "declined composition" verdict (#1676)

A composition question needs more than one facade call combined by the model — q05's aged
receivables followed by one `searchWorkorders` call per past-due customer is the motivating case.
That plan can fail by silent truncation: the model runs the first call, sees the shape of the rest
of the plan, and offers the user a menu of partial answers instead of just running it. Scored by
hand against `EXPECTED.md` alone, that reads as an ordinary wrong or incomplete answer — nothing
distinguishes "the model computed something wrong" from "the model declined to finish the plan it
correctly diagnosed." `expected_plan` names the plan so a grader (or, once observable, the runner
itself) can tell the two apart and record the real failure:

```json
"expected_plan": {
  "min_tool_calls": { "getAgedReceivables": 1, "searchWorkorders": 1 },
  "per": "one searchWorkorders call per past-due customer (#1676 status=OPEN, one call per customer id)",
  "declined_reason": "declined composition"
}
```

- `min_tool_calls` — a map of real facade `@Tool` method names (validated against
  `facade-contract.yaml` by `AnalyticsGateQuestionsTest`) to the minimum number of calls the
  composition needs. For a per-value loop (q05's per-customer `searchWorkorders`) this is the
  floor for one unit (one customer), not the full seed-dependent count, since the seed's past-due
  customer count can change; `per` states what the loop is over. For a `calendar`/`rolling`
  window, `AnalyticsGateQuestionsTest` also checks feasibility, not just the name: every named
  tool must declare a `startDate`+`endDate` pair or an `asOfDate` parameter, so a plan naming a
  period-only tool for a multi-month window (the #1677 defect that made q09/q15's claimed one/two
  calls unachievable) fails before the gate is ever run.
- `per` — one sentence naming what the loop or comparison is over, for a human grader reading the
  fixture without the plan document open.
- `declined_reason` — the verdict note text (`"declined composition"` in every case so far) a
  below-minimum observed count produces.

A question with nothing to compose (a single-call answer, or a fixed two-window comparison with no
per-value loop) simply omits the field; `AnalyticsGateQuestionsTest` does not require one.

**The verdict is not automated today.** `scripts/analytics_gate_run.py` would apply
`expected_plan` against the observed tool-call sequence per turn, but neither `POST /mcp/chat`
(`McpChatController.ChatResponse` carries only the answer text) nor any admin/observability
endpoint exposes `mcp_tool_invocation_log` over HTTP — there is no controller anywhere that reads
`ToolAuditRepository`/`ToolAuditService`, and building one is out of scope here. The run document's
"Tool calls" column therefore always reads `n/a (not exposed by the endpoint)`, and a question
carrying `expected_plan` gets a `plan_check` note explaining that the check did not run, instead of
a computed verdict. A human grader still applies `expected_plan` by hand when reading the answer:
if a composition question's answer covers fewer past-due customers (or windows, or months) than
`min_tool_calls` implies, record the verdict as **FAIL — declined composition**, not a bare FAIL,
so the failure class is visible on the run document without re-deriving it from the transcript.
The comparison logic already exists in the script (`check_expected_plan`) so that wiring in a real
tool-call source later is additive, not a rewrite.

Run it with `scripts/analytics_gate_run.py`, which reads this file and records its git blob sha in
the run record. **Do not ask a gate question from anywhere else.** Before #1671 the twelve
utterances lived only in an operator's `/tmp` file: a re-run could ask something different and move
the score with no code change, a regression could not be told apart from a reworded question, and
nothing recorded which twelve of the twenty were being asked. That is how gate q09 came to be asked
with a calendar-year window against a ground truth measuring twelve calendar months and scored
UNSCORABLE — a defect invisible until someone opened the fixture file by hand.

`AnalyticsGateQuestionsTest` enforces the invariants in ordinary CI: twenty questions in order, the
bijection with `EXPECTED.md`, an existing ground-truth script per entry, a resolvable window on
every question, a stated reason on every exclusion, and a `tool_selection_fixture_id` that names a
fixture that exists.

**`../tool-selection/analytics-gate.json` is not this file.** It scores which _tools_ are selected,
carries an actor with `permission_codes`, and includes control fixtures that are not analytics
questions at all (`q13-admin-user-account-active` — "Is this user account still active?"). Its ids
share the `qNN` numbering, which reads like the question list and is not; that overlap caused a
misreading during #1661. Both files now say so in their own `suite_notes`.

### Windows

Every entry states the window shape its ground truth measures and whether the question's text
resolves there under the #1661/#1670 rules in `SystemPromptDefaults` (rolling vs calendar by
preposition, calendar precedence on a mixed comparison). Where the two disagree, `window.notes`
says so — an unstated window is allowed but never silent, and the test fails a question that has
one without an explanation. Open today: **q17** names no window at all and inherits q15's
six-month windows because both are served by the same E8 call. q02, q07, q10, q18 and q19 carry
recorded gaps that are inert while those questions sit outside the gate.

## Status (2026-09-02)

- Seed: WRITTEN and APPLIED to alpha (2,257 rows, 5 databases) — `seed/`, marker-scoped and
  idempotent; regenerate with `python3 seed/generate_seed.py`, apply with `seed/apply_seed.sh`.
- Ground truth: ALL 20 scripts written (`ground-truth/q01..q20.sql`), executed live on alpha
  2026-09-02 (26/26 sections clean, `ground-truth/runs/2026-09-02-alpha-run.txt`).
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

| Dimension   | Count                          | Why                                                                                          |
| ----------- | ------------------------------ | -------------------------------------------------------------------------------------------- |
| Locations   | 1                              | keeps every aggregate single-location; no cross-location roll-up in the gate                 |
| Technicians | 3                              | Q1/Q2/Q3/Q19 rank and compare technicians; 3 is the smallest set with an unambiguous ranking |
| Customers   | 6 (2 commercial, 4 individual) | Q13's Pareto needs a skewed but hand-checkable distribution                                  |
| Vendors     | 3                              | Q15/Q17 rank vendors and compare year over year                                              |
| Work orders | ~120                           | known creation / completion / reopen timestamps                                              |
| Invoices    | ~150                           | line-level labor/parts split, payment applications at controlled lags                        |

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
- **Revenue trend (Q10).** At least one customer with rising invoiced revenue _and_ a rising
  past-due balance across the last three months, and at least one decoy with only one of the two.

## Table ownership

Each service owns its own schema; there are no cross-service foreign keys, and a replica table
(`ext_*`) is written from events, never joined across a database boundary. A ground-truth script
must therefore run against **one** module's schema, or be split into per-module scripts whose
results are combined by the script's author — never a cross-schema join.

| Table                                                                    | Owning module    | Notes                                                                          |
| ------------------------------------------------------------------------ | ---------------- | ------------------------------------------------------------------------------ |
| `workorder`, `workorder_service`, `work_order_state_transitions`         | pos-workorder    | WO lifecycle; the transition table backs Q3's reopen detection (plan D4)       |
| `invoices`, `invoice_items`, `invoice_adjustments`, `receipts`           | pos-invoice      | authoritative invoice + line-level labor/parts split                           |
| `ext_invoice`                                                            | pos-accounting   | event-fed replica of `invoices`; the **A/R aging reads this**, not pos-invoice |
| `payment_application`, `payment_application_reversal`                    | pos-accounting   | cash applied to an invoice, and reversals                                      |
| `credit_memo`, `customer_credit_transaction`                             | pos-accounting   | the other two balance reducers (`InvoiceBalanceCalculator`)                    |
| `vendor_bill`                                                            | pos-accounting   | A/P aging and Q15/Q16/Q17 vendor spend                                         |
| `person_party`, `commercial_party`, `party_alias`                        | pos-customer     | customer identity and display names                                            |
| pos-people employee tables (`work_session`, time-entry tables)           | pos-people       | technician identity and clocked hours                                          |
| `ext_vehicle`, `ext_location`, `ext_workorder`, `ext_people_employee`, … | consuming module | replicas; useful for a _display name_, never as the source of a monetary fact  |

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
   _historical_ aging date. Plan §3 W1.2 and the tool description both claim a past `asOfDate`
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

## How windows are graded

**The shape, not the endpoints** (#1709, option 3).

A relative window's endpoints are a derived consequence of its shape, unit, count and the run's own
date. Comparing them against dates baked from a fixed `eval_as_of` fails every run that does not
execute on that exact day — a three-day offset on 2026-09-04 made every range in this corpus
unmatchable, however correct the assistant was.

So each question carries `window.expected`:

```json
"expected": { "shape": "CALENDAR_SPAN", "unit": "MONTH", "count": 6, "comparison": "YEAR_EARLIER" }
```

and the grader reads the shape out of the answer itself. `DateWindowResolver` prefixes every window
statement with its shape label (`calendar span: …`, `rolling: …`, `prior complete: …`), and the
DATE_WINDOW contract requires the model to quote that statement — so the shape is observable with no
production change.

- `shape`, `unit`, `count` and `comparison` are all graded. A right shape with the wrong count is a
  FAIL: answering q12 on one calendar month where six were specified is the wrong window, and
  grading the label alone would call it correct.
- `also_accept` lets a question admit more than one correct shape. q04 buckets by month, so six
  `ABSOLUTE` resolutions satisfy it as well as one `CALENDAR_SPAN` — and **six** is checked, not
  "at least one": a single named month is the under-answer that actually occurred on 2026-09-04.
- `as_of_offset_days` handles the questions where an endpoint genuinely matters and no resolver
  shape describes it — point-in-time questions (q05, q13). Expressed as an offset from the run's own
  as-of date (option 2), so any run can satisfy it.
- `shape: null` with a `note` means deliberately ungraded. q08 is mixed — a rolling 90-day filter
  AND a prior-calendar-year condition — and no single shape describes it; expressing two windows per
  question is #1689 work. Eight non-gate questions are unannotated for the same reason.

An answer that quotes no statement grades **UNGRADED**, never PASS: the contract requires the quote,
and silence about the window is not evidence the window was right. A turn with no answer at all
(timeout, HTTP error) is likewise UNGRADED — a transport failure is not a window failure, and
keeping them separable is the point of grading per stage.

In replay mode a window FAIL **fails the run**. Answering the right question on the wrong six months
is a wrong answer, and a verdict that ignored it would report PASS for exactly the failures this
grading exists to catch.

### What this deliberately does not check

Endpoints for relative windows.

**q16 is unannotated entirely.** It asks for bills due in the *next* 14 days, and the resolver has no
forward shape — `ROLLING` is `start = today - N + 1, end = today`. A `ROLLING` expectation would
therefore pass an answer that used the wrong, backward window and leave a correct forward answer
UNGRADED. It needs either a forward shape in the resolver or an endpoint-based expectation; the
day-count edge is separately open in #1681.

**q07 and q17 are unannotated** because their own notes say the corpus cannot support an
expectation: q07's mixed comparison does not trigger the calendar precedence under the shipped
contract (both windows read rolling), and q17 names no window at all. Asserting a shape for either
would convert an acknowledged gap into ground truth that lies — the failure mode #1659 was.

## The run is one conversation, not twelve independent questions

`SessionAgentManager` keys chat memory on `(username, role)` and the runner sends every question
with the same bearer token and no session identifier, so **all questions in a run land on one
memory key**. With `mcp.agent.memory-max-messages: 100` nothing evicts them within a run: twelve
questions and twelve answers is 24 messages.

So q17 is answered with q01 through q15 in context. The corpus's framing — one utterance, one
expected answer, one window — assumes an independence the run does not have.

Consequences to keep in mind when reading any score:

- A question can be answered correctly because an earlier one established a window, an entity or a
  metric, and the record cannot distinguish that from answering it cold.
- **`--only q09` is not the same experiment as a full run**, because the preceding context differs.
  Single-question re-checks are a different measurement, not a reproduction.
- It is a candidate explanation for run-to-run inconsistency on identical builds — q15 resolved
  `CALENDAR_SPAN` once and `ROLLING` another time with no relevant code change between.

This is not a production defect: sharing memory across a user's turns is what a chat assistant
should do, and it is what `MULTI_TURN.json` exercises deliberately. The defect is that the gate
relied on independence without disclosing it.

Tracked in #1735, which proposes measuring the size of the effect first — run the gate twice with
the question order reversed — before deciding whether to isolate turns, evict between them, or
accept and account for it.

## Which actor to run as

**`admin.alpha`** — the `ITEST_USERNAME` / `ITEST_PASSWORD` pair in the itest credentials file.
Its token carries `ROLE_ADMIN`, `ROLE_SYSTEM_ADMINISTRATOR` and `ROLE_FACTOR_PASSWORD` (the last is
an authentication-factor marker every seeded actor holds; it says nothing about reach, and the
runner excludes it when checking the role, so `run.json` lists all three while the check matches
`ROLE_ADMIN`).

This is not a convenience. The corpus spans three permission domains — workorder labor (q01, q03,
q04), invoice revenue and A/R (q05, q07, q08, q09, q12, q13) and A/P vendor spend (q15, q16, q17) —
and no role-scoped seeded actor was found to cover all three.

**Evidence, and its limits.** The table below is a **three-question probe** run on alpha
`sha-3dd57ac` on 2026-09-04, asking exactly one question per domain — q01 (workorder labor), q13
(A/R aging), q15 (A/P vendor spend). It is not a full-corpus measurement and must not be read as
one:

| Actor | Role | q01 | q13 | q15 |
|---|---|---|---|---|
| `ITEST_CONTROLLER` | `ROLE_CONTROLLER` | answered | answered | deflected |
| `ITEST_ACCT` | `ROLE_ACCOUNT_MANAGER` | answered | answered | deflected |
| `ITEST_MANAGER` | `ROLE_LOCATION_MANAGER` | deflected | deflected | deflected |

A full-corpus run as `ITEST_CONTROLLER` on the same build scored **2 answered, 7 deflected, 3
asked** — so "answered q01" does *not* generalise to "answers workorder questions"; that actor also
deflected q03 and q04. The probe is enough to rule the role-scoped actors out, and not enough to
characterise them. `admin.alpha` has not been probed per-domain because it is the actor the corpus
is written for.

A caller without a domain's codes is offered a different tool set and answers, correctly, that the
platform exposes no such query. Those answers are well-formed, plausible, and **indistinguishable
from model failures** in the run record — two runs on 2026-09-04 scored 0/12 and 2/12 for exactly
this reason and neither record said so. That is why `analytics_gate_run.py` now decodes the token's
role, records it under `actor`, and refuses to start unless it matches. See #1706.

### When the check refuses

- `--expect-role ROLE_X` / `MCP_EXPECTED_ROLE=ROLE_X` (readable from `--env-file`) — check a
  different role, for a deliberate role-scoped probe like the one above.
- `--expect-role ''` — skip the check entirely.
- `--allow-role-mismatch` — run anyway. The record is stamped `void: true` with a `void_reason`,
  and `run.md` opens with a VOID banner above everything else. Use it for probes, never for a
  score you intend to quote.

`run.json` always carries `void` (false on a normal run), so a record without the key predates this
check rather than being a run that passed it.

### The cost of running as an administrator

The gate never exercises permission gating. Every question is asked by a caller that can reach
everything, so a regression that wrongly *widens* a role's tool set would not show up here. If a
future change gives a role-scoped actor full corpus coverage, prefer it. That is a corpus decision,
not a runner one.

