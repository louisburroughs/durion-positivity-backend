# A/R aging basis change invalidates the Q13 and Q5 records — 2026-09-01

Issue: #1604 · A/R aging re-based onto the due date; not-yet-due amounts now reported on both A/R and A/P
Run environment: sandbox checkout of `claude/issues-1604-1605-subagents-8m2kbh`. No live alpha or docker
stack, no Postgres/pgvector, no embedding model — and no JDK 25 on this host (`java -version` reports
OpenJDK 21.0.10, and the reactor's `enforce-java` rule requires 25), so not even the DB-free
`pos-mcp-server` unit suite was executed.
Commit under test: **none — nothing was executed.** This note describes the working tree of the branch
above. The behaviour change itself is in `pos-accounting` (`FinancialReportingServiceImpl`), which is
being edited concurrently by another agent on the same branch, so the line citations below are pinned to
method names as well as line numbers.

## Observed

**Nothing was observed, because nothing was run.** No gate question — Q5, Q13, or any other — has been
executed in this sandbox, before or after the change. This is the same state
`docs/gate-runs/wave-2/README.md:9-19` records for Wave 2: the gate protocol needs a live stack and a
running model, neither of which is reachable here, and "anything that looks like a Q-number result
elsewhere in this PR is not real."

No metrics table appears in this section on purpose. There are no before/after bucket totals, no token
costs, and no pass/fail rows to report, and inventing them is precisely the failure mode this directory
exists to prevent. What follows is a determination about **meaning**, derived from reading the code and
the ground-truth specification — not from a measurement.

## Question

Q13 is recorded as a Wave 1 **full pass** and Q5 as a Wave 1 **partial**
(`docs/analytics-capability-plan.md:187` and `:188`; these were `:173`/`:174` before this change added
lines to §3). Both answers are built on `getAgedReceivables`. Do those recorded outcomes still describe
the system after the aging basis changed, and can a future run be compared against them?

## Determination: the Q13 and Q5 records are invalidated, not re-baselined

**1. The A/R aging basis is now the due date, not the invoice date.**
`FinancialReportingServiceImpl.receivableAgingDate` (`pos-accounting/src/main/java/com/positivity/accounting/internal/service/FinancialReportingServiceImpl.java:1278-1281`)
returns `invoice.getDueDate()`, falling back to `receivableDocumentDate` (`:1291-1301`,
`invoice_created_at` → `finalized_at` → `updated_at`) only when the due date is null. `ext_invoice.due_date`
is a nullable `date` column (`pos-accounting/src/main/resources/db/migration/V22__ext_invoice_due_date.sql:5`)
— null on drafts and on replica rows built from events predating that migration — so the fallback is
permanent, not transitional. Any invoice whose due date differs from its invoice date changes bucket.

**2. The "did not exist yet" filter moved from the aging date to the document date.**
`FinancialReportingServiceImpl:656-661` now excludes an invoice when `receivableDocumentDate(...)`
is after `asOfDate`. Previously the same test was applied to the aging date, which — once the aging date
becomes the due date — would have meant "not yet due" rather than "not raised yet". The filter keeps its
original intent only because it was re-based.

**3. Not-yet-due balances are now reported, in `current`.** `daysPastDue` may be negative
(`:662-667`), and `AgingBuckets.add` puts anything `<= 30` in `current` (`:1376-1378`). That is what
`AgedReceivablesRow`'s schema already promised — "Outstanding 0-30 days past due (includes not-yet-due)"
(`pos-accounting/src/main/java/com/positivity/accounting/internal/dto/AgedReceivablesRow.java:56`). Before
this change such rows were dropped from the report entirely, so `current`, `totalOutstanding`, and every
grand total move upward on any dataset containing not-yet-due invoices.

**4. A/P totals move as well, and the issue title does not say so.** `payableAgingDate` already used the
due date, so claim 1 is a no-op there — but claim 3 is not. `FinancialReportingServiceImpl:736-746`
applies the same document-date existence filter and the same negative-`daysPastDue` retention to vendor
bills, so not-yet-due bills that were previously dropped from aged payables now appear in `current`.
Aged **payables** figures are therefore also not comparable across this change.

**5. The Q13 ground truth was the specification of the expected answer, and it has been rewritten.**
`src/test/resources/eval/analytics-gate/ground-truth/q13-ar-pareto.sql` declares itself "the specification
of the expected figures" (`:9-11`). Its aging expression now leads with `i.due_date` (`:55-62`), its
existence filter is on the document date (`:105-108`), and its header caveats state the new rule
(`:19-27`). The numbers the old script produced are not the numbers the new one produces on the same
data, so the recorded Q13 pass cannot be re-checked against today's specification.

**6. Two further gate fixtures depend on this behaviour and issue #1604 does not name them.**
`q10-rising-sales-rising-past-due` (`src/test/resources/eval/tool-selection/analytics-gate.json:89-90`)
and `q14-ar-balance-and-dso-by-month` (`:117-118`) both drive the past-due half of their answers from
`getAgedReceivables`. Their tool-*selection* assertions are unaffected — selection does not depend on
bucket arithmetic — but any answer-correctness expectation attached to them is. Both are recorded as
**withdrawn** at `docs/analytics-capability-plan.md:189-190` for an unrelated reason (no point-in-time
balances), so nothing regresses today; the point is that they must be re-specified against the new rule
whenever they are revived, not silently inherited.

**7. Q5 and Q13 have NOT been re-run, here or anywhere.** There is no post-change observation of either.
Their Wave 1 outcomes stand as a record of what happened then, against the old aging rule; they are not
evidence about the current system and must not be used as a comparison point for a future run.

**8. Fixture seed data written to the old instruction is now wrong.**
`src/test/resources/eval/analytics-gate/README.md:100-116` previously told fixture authors to age by
`invoice_created_at` and seed dates accordingly. Under the new rule the two designed 60+-days-past-due
customers need real `due_date` values; a null `due_date` silently reverts an invoice to invoice-date
aging. Any seed built to the old instruction lands its designed customers in different buckets.

## Consequence: re-specify first, then re-run, then compare only within the new basis

1. **Do not compare any future Q13/Q5 run against the Wave 1 records.** Treat the Wave 1 line items in
   `docs/analytics-capability-plan.md:187-188` as historical, closed by this change.
2. **Fix the fixture seed before running anything.** Give the two designed past-due customers explicit
   `due_date` values 61+ days before the gate's `asOfDate`, per the rewritten
   `src/test/resources/eval/analytics-gate/README.md` item 3. A run against the old seed measures the
   seed, not the system.
3. **Re-run Q13 and Q5 on a live stack** (alpha or a docker-profile stack with fixture data and a running
   embedding model — see `docs/gate-runs/wave-2/README.md` for what that requires) and record the
   observed answers here, in a new dated note, as the **first** observation on the new basis.
4. **Re-check the A/P side too.** Aged-payables figures quoted anywhere from before this change —
   including in chat transcripts used as evidence — are stale for the reason in claim 4.
5. **Re-specify Q10 and Q14 before reviving them.** Whatever unblocks them (point-in-time balances, plan
   §4/Wave 3) must be specified against due-date aging, not against the withdrawn Wave 1 assumptions.
6. **Keep this note when the re-run lands.** The value of the record is that a reader can see that the
   measurement basis moved on 2026-09-01 and why a number from before that date is not comparable.

## What this note does not claim

It does not claim the fix is correct against real data — no data was queried. It does not claim the
rewritten `q13-ar-pareto.sql` reproduces the service's output; that script was desk-checked clause by
clause against `FinancialReportingServiceImpl`, never executed (there is no seeded pos-accounting database
in this sandbox). It does not claim any gate question passes or fails today.
