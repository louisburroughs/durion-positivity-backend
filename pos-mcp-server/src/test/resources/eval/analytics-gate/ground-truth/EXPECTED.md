# Analytics gate — expected answers (TRACKB seed on alpha, EVAL_AS_OF 2026-09-01)

The expected values below are the **live measured output** of this directory's `qNN-*.sql`
suite against the alpha stack's databases (run 2026-09-02 via `run_ground_truth.sh`; exit 0,
all 26 sections, zero SQL errors — script-is-spec on the shared environment). Alongside each
measured value, the **seed contribution** derived analytically from `../seed/DATASET.md`
is kept, so the designed invariants stay checkable as seed-relative deltas wherever
co-tenant data moved the absolute shape. Every seed-contribution figure in the live run
matched the DATASET.md derivation exactly — no seed bug was found.

## Co-tenancy policy (2026-09-02, user decision)

Alpha's pre-existing co-tenant data (the `01a0…`/`0196…` UUIDv7 rows in `invoices` /
`ext_invoice` / the payment books, plus co-tenant workorders and labor entries) is
**ACCEPTED, not cleaned**. Consequences:

- Expected values below **include pre-existing alpha data by design**. Gate comparisons
  stay valid because the chat path reads the same tables the ground-truth SQL reads.
- Values are stable **only while no other writer churns these tables**. After any known
  write, re-run `run_ground_truth.sh` and refresh this file. Treat a drifted expected value
  as **environment churn first, seed regression second** — the `TRACKB-` marker scoping
  makes the seed's own contribution re-derivable at any time (filter on `TRACKB-%` numbers
  / the cast UUIDs and compare to the "seed contribution" figures here).
- The co-tenant book as measured in this run, for reference in every delta below:
  **37 co-tenant customer invoices of 286.17 each (total 10,588.29)**, FINALIZED
  2026-08-23/24 by itest runs, all **unpaid** (no payment applications), plus co-tenant
  workorders (133 of them open/non-terminal), co-tenant labor entries (9.20 h attributed to
  technician James Rivera + two zero-hour technician ids in August), and ~40 co-tenant
  WO-linked invoices with NULL `ext_workorder` anchors. Cross-foots used throughout:
  37 × 286.17 = 10,588.29; 16 × 286.17 = 4,578.72; 21 × 286.17 = 6,009.57.

Classification per question: **ABSOLUTE** (measured value equals the seed design — no
co-tenant contribution to any asked figure), **CO-TENANT-RELATIVE** (measured value
includes co-tenant rows; seed contribution stated as a delta), **BLOCKED** (no truth
computable — unchanged from the pre-live version).

Customers: C1 Bluerock Freight, C2 Harbor Tool & Die, C3 Alice Prescott, C4 Marcus Webb,
C5 Dana Whitfield, C6 Peter Okafor. Technicians: Sam Ellison, Nadia Torres, Alex Kim.
Vendors: V1 Evergreen, V2 Cascade, V3 Summit. UUIDs in DATASET.md's cast table.

## Q1 — top technicians by labor revenue, August 2026 — ABSOLUTE (answer)

Measured (row set carries three co-tenant technician rows with zero labor revenue and zero
completed WOs — they cannot enter the asked ranking or the avg-hours-per-completed-WO):

| Technician | completed WOs | billed hours | labor revenue | avg hrs/WO |
|---|---|---|---|---|
| Nadia Torres | 2 | 4.00 | **1500.00** | 2.00 |
| Alex Kim | 3 | 5.00 | 950.00 | 1.67 |
| Sam Ellison | 1 | 4.00 | 600.00 | 4.00 |
| James Rivera (co-tenant) | 0 | 9.20 | 0 | — |
| 01960010-…0005 (co-tenant) | 0 | 0.00 | 0 | — |
| d981cd20-… (co-tenant) | 0 | 0.00 | 0 | — |

Top by labor revenue: **Nadia**. Caveat for graders: a "top by billed hours" reading of the
raw rows would name co-tenant James Rivera (9.20 h, zero completions) — the question asks
labor revenue, so the answer is unaffected, but an answer ranking by hours is wrong twice.

## Q2 — technician 3-month comparison (Jun/Jul/Aug 2026) — ABSOLUTE (answer)

Measured (same three zero-revenue co-tenant rows appear in 2026-08 only):

| Month | Nadia (WO/hrs/rev) | Alex | Sam |
|---|---|---|---|
| 2026-06 | 3 / 8.00 / 1100.00 | 2 / 3.00 / 250.00 | — |
| 2026-07 | 3 / 8.00 / 2100.00 | 1 / 2.00 / 250.00 | 1 / 4.00 / 600.00 |
| 2026-08 | 2 / 4.00 / 1500.00 | 3 / 5.00 / 950.00 | 1 / 4.00 / 600.00 |

## Q3 — most reopens ≤ 7 days, quarter 2026-07-01..2026-09-01 — ABSOLUTE

Measured exactly as designed: **Sam Ellison, 2 reopens** (C1 Jul monthly, 3d; C1 Aug
monthly, 5d), Alex 1 (C3 Jul monthly, 4d), Nadia 0 — her 25d reopen is the designed decoy.
No co-tenant reopen pairs exist.

## Q4 — avg WO-creation→invoice lag by month, Mar–Aug 2026 — ABSOLUTE (averages)

Measured: 2026-03 **2.00d** (6 pairs) · 04 **3.00d** (7) · 05 **4.00d** (7) ·
06 **5.00d** (5) · 07 **6.00d** (5) · 08 **7.00d** (5). The designed 2.00→7.00 drift and
pair counts hold absolutely. `excluded_null_anchor`: June 1 (co-tenant), August **41** =
the 2 designed deposit-pair invoices + 39 co-tenant invoices with NULL replica anchors —
harmless, the exclusion IS the endpoint's own #1592 semantics (see script DIVERGENCE), but
the excluded-count column itself is environment-dependent.

## Q5 — open WOs for customers > 60 days past due — ABSOLUTE (answer)

Section 1 measured clean: exactly two 60+ customers — **C1** (2000.00 in 90+, total
4500.00) and **C2** (1500.00 in 61–90, total 2000.00). Section 2 measured 137 open
workorders (4 TRACKB + **133 co-tenant**, none belonging to C1/C2), so the composed answer
is unchanged and absolute:

| WO | Customer | Status | Created |
|---|---|---|---|
| TRACKB-WO-OPEN-C1-ASSIGNED | C1 | ASSIGNED | 2026-08-18 |
| TRACKB-WO-OPEN-C1-WIP | C1 | WORK_IN_PROGRESS | 2026-08-24 |
| TRACKB-WO-OPEN-C2-PARTS | C2 | AWAITING_PARTS | 2026-08-20 |

TRACKB-WO-OPEN-C3-APPROVED (C3) remains the designed decoy; the 133 co-tenant open WOs are
additional noise the model's customer filter must also drop. Fixed by #1652 (no longer a
divergence): C2's "current" now reads 500.00, the settlement invoice's true economic
balance (was 1000.00 before InvoiceBalanceCalculator subtracted deposit-credit draw-downs).

## Q6 — revenue / parts / labor / margin by customer — **BLOCKED** (unchanged)

E13 deferred to Wave 3 by D2; true parts COST lives in pos-inventory, which the seed
deliberately does not cover. No expected figures are specified — deriving a margin from
sell prices would be a fabrication. Revisit when E13 + its cost source ship (extend seed +
script + this section together, plan §7).

## Q7 — top customers, 12 mo vs prior 12 mo — CO-TENANT-RELATIVE (top-6 absolute)

Measured 49 rows = the 12 designed rows + **37 co-tenant rows of 286.17 / 1 invoice each,
all in the last-12mo window** (none in prior-12mo). The six designed customers' YoY
numbers hold exactly and, at ≥ 2300.00 each, outrank every co-tenant row — the top-6
ranking is absolute:

| Customer | 2025-09-01..2026-08-31 | 2024-09-01..2025-08-31 |
|---|---|---|
| C1 | **16500.00** (14 inv) | 12000.00 (12) |
| C2 | **10300.00** (13) | 6000.00 (12) |
| C4 | 8100.00 (9) | **10800.00** (12) |
| C3 | 6000.00 (13) | 4800.00 (12) |
| C5 | 3900.00 (12) | 3600.00 (12) |
| C6 | 2300.00 (11) | 2400.00 (12) |

Ranking last-12mo: C1, C2, C4, C3, C5, C6, then 37 co-tenant ties at 286.17.
Prior-12mo: C1, C4, C2, C3, C5, C6 only. (Deposit-take 500.00 excluded per D8/#1623.)

## Q8 — no purchase 90 days, > $10k prior year — ABSOLUTE

Measured exactly one row: **C4 Marcus Webb** — prior-year revenue 10800.00, last invoice
2026-05-30, 94 days before as-of. No co-tenant customer reaches 10k in the prior-year
window (their invoices are all August 2026), and active C1 (12000.00 prior year) is
correctly absent.

## Q9 — top 20 customers: revenue, count, avg, balance, days-to-pay — CO-TENANT-RELATIVE

Measured top-20 by 12-month revenue = the six designed customers (ranks 1–6, exact) plus
**14 co-tenant rows at 286.17 / 1 invoice** (ties, ordered by customer id) filling ranks
7–20. The designed rows, all measured exactly:

| Customer | revenue | count | avg value | outstanding | avg days-to-pay |
|---|---|---|---|---|---|
| C1 | 16500.00 | 14 | 1178.5714 | 4500.00 | 20.02 |
| C2 | 10300.00 | 13 | 792.3077 | 2000.00 | 22.10 |
| C4 | 8100.00 | 9 | 900.0000 | 900.00 | 15.02 |
| C3 | 6000.00 | 13 | 461.5385 | 1200.00 | 10.02 |
| C5 | 3900.00 | 12 | 325.0000 | 600.00 | 40.02 |
| C6 | 2300.00 | 11 | 209.0909 | 300.00 | 75.02 |

Co-tenant ranks 7–20: revenue 286.17, 1 invoice, outstanding 286.17 (all unpaid), no
days-to-pay (never reached zero balance). Days-to-pay stays the specified E10-derived
figure (first zero-balance application − finalized_at; the 0.02 is the designed
14:30→15:00 clock offset; ±0.5 %). Fixed by #1652: C2's outstanding now carries the
settlement invoice at its true 500.00 open (was 1000.00 before the deposit-credit
subtraction), dropping C2's outstanding column from 2500.00 to 2000.00.

## Q10 — rising sales AND rising past-due, 3 months — CO-TENANT-RELATIVE (answer C2); trend half BLOCKED

Monthly E1 revenue measured (designed rows exact; **37 co-tenant rows of 286.17 appear in
2026-08 only** — a one-month blip, not a rising trend): C2 **800 → 1000 → 2500** (the
designed riser); C1 1000 → 3500 → 1000; C3 400 → 400 → 1600; C5 300 → 300 → 600;
C6 200 → 0 → 300.

Past-due trend: the TRUE point-in-time trend is **BLOCKED** (W3.1 prerequisite — no
historical balance reconstruction). The aging-ENDPOINT figures measured per month-end
as-of (current balances, moving boundaries — script section 2), per designed customer
past-due: C2 0 → 1500 → 1500; C1 2000 → 2000 → 2000; C4 0 → 0 → 900. The 37 co-tenant
rows enter only the 2026-08-31 as-of (their document dates are 2026-08-23/24) and have no
Jun/Jul presence, hence no trend. On these numbers the designed answer is **C2** (rising
sales AND past-due 0→1500→1500); an honest answer must caveat the current-basis balances
(criterion 4). C1 is the decoy (flat/flat).

## Q11 — weekly invoiced vs settled, 12 weeks (Mon–Sun, ending 2026-08-30) — CO-TENANT-RELATIVE (last two weeks)

Measured (expected values). Co-tenant invoicing (2026-08-23/24) lands in the last two
weeks' `invoiced` ONLY; collected/nonCash/settled are clean in every week (co-tenant
invoices have no applications):

| Week starting | invoiced | collected | nonCash | settled | collect % | settle % |
|---|---|---|---|---|---|---|
| 2026-06-08 | 0 | 700.00 | 0 | 700.00 | — | — |
| 2026-06-15 | 200.00 | 1000.00 | 0 | 1000.00 | 500.00 | 500.00 |
| 2026-06-22 | 0 | 800.00 | 0 | 800.00 | — | — |
| 2026-06-29 | 2700.00 | 200.00 | 0 | 200.00 | 7.41 | 7.41 |
| 2026-07-06 | 0 | 700.00 | 0 | 700.00 | — | — |
| 2026-07-13 | 0 | 0 | 0 | 0 | — | — |
| 2026-07-20 | 2500.00 | 2000.00 | 0 | 2000.00 | 80.00 | 80.00 |
| 2026-07-27 | 1400.00 | 200.00 | 0 | 200.00 | 14.29 | 14.29 |
| 2026-08-03 | 0 | 100.00 | 0 | 100.00 | — | — |
| 2026-08-10 | 4000.00 | 1100.00 | 500.00 | 1600.00 | 27.50 | 40.00 |
| 2026-08-17 | **5178.72** | 2500.00 | 0 | 2500.00 | 48.27 | 48.27 |
| 2026-08-24 | **6009.57** | 200.00 | 0 | 200.00 | 3.33 | 3.33 |

Seed contribution in the two moved weeks: wk 08-17 invoiced 600.00 (+ 16 × 286.17 =
4578.72 co-tenant); wk 08-24 invoiced 0 (+ 21 × 286.17 = 6009.57 co-tenant). Seed-only
rates for those weeks would be 416.67 and — (null). Rates are NULL (—) when invoiced = 0.
Week 2026-08-03 carries the reversal netting (deposit-take application 500 − C3 refund
reversal 400 = 100); week 2026-08-10 has C3 re-pay 400 + C3 Aug monthly 400 + C5 Jul 300
= 1100 applied, plus the 500 deposit draw-down in nonCash.

## Q12 — payment-lag cohorts, invoices finalized 2026-03-01..2026-08-31 — CO-TENANT-RELATIVE (unpaid cohort)

Measured (expected values):

| Cohort | invoices | amount |
|---|---|---|
| ≤30 | 20 | 14000.00 |
| 31–60 | 5 | 1500.00 |
| 61–90 | 4 | 800.00 |
| unpaid | **45** | **22088.29** |

The three paid cohorts are ABSOLUTE (co-tenant invoices are all unpaid). unpaid = the 8
designed open invoices (11500.00: C1 2000+2500, C2 1500 + settlement 2500, C3 1200,
C4 900, C5 600, C6 300) + 37 co-tenant × 286.17 (10588.29). ≤30 includes the deposit-take
document (D8 not extended to E3 — script DIVERGENCE note).

## Q13 — A/R Pareto + past-due share, as of 2026-09-01 — CO-TENANT-RELATIVE

Measured book: **43 customers, grand total 20088.29** = designed 9500.00 + 37 co-tenant
open invoices × 286.17. (Fixed by #1652: the designed total was 10000.00 before
InvoiceBalanceCalculator subtracted deposit-credit draw-downs; C2's settlement invoice
drops 500.00 from "current".) The six designed customers hold ranks 1–6 with their
designed buckets exactly; every co-tenant row is 286.17, current-only, 1.42 % share:

| # | Customer | total | current | 31–60 | 61–90 | 90+ | past-due % of cust. | share | cum. |
|---|---|---|---|---|---|---|---|---|---|
| 1 | C1 | 4500.00 | 2500.00 | 0 | 0 | 2000.00 | 44.44 | 22.40 | 22.40 |
| 2 | C2 | 2000.00 | 500.00 | 0 | 1500.00 | 0 | 75.00 | 9.96 | 32.36 |
| 3 | C3 | 1200.00 | 1200.00 | 0 | 0 | 0 | 0.00 | 5.97 | 38.33 |
| 4 | C4 | 900.00 | 0 | 900.00 | 0 | 0 | 100.00 | 4.48 | 42.81 |
| 5 | C5 | 600.00 | 600.00 | 0 | 0 | 0 | 0.00 | 2.99 | 45.80 |
| 6 | C6 | 300.00 | 300.00 | 0 | 0 | 0 | 0.00 | 1.49 | 47.29 |
| 7–43 | co-tenant (×37) | 286.17 each | 286.17 | 0 | 0 | 0 | 0.00 | 1.42 each | +1.42/row |

**Pareto-80 set = ranks 1–29** (measured `in_pareto_80` = t through rank 29, cumulative
80.06 %; rank 30 = f at 81.48 %): the six designed customers + 23 co-tenant rows — the
boundary rank is unchanged by #1652 even though the percentages shifted (recomputed by
hand: cumulative through rank 28 is 78.63 % of the new 20088.29 grand total, still short
of 80 %; rank 29 adds the 23rd co-tenant row to reach 80.06 %).
Seed-relative invariants that still hold: C1 contributes 4500.00 with 2000.00 in 90+;
exactly two customers are 60+ past due (C1, C2); C1's and C4's past-due shares (44.44 %,
100.00 % for C4) are co-tenant-independent. C2's past-due share is now **75.00 %** (was
60.00 % before #1652 — same 1500.00 past-due amount over a smaller 2000.00 total). The
designed "C1 = 45 % of A/R, boundary at row 3" shape is broken by the co-tenant book
(C1 = 22.40 %). C5's 600 is not yet due (due 2026-09-05, in current per #1604). Fixed by
#1652 (no longer a divergence): C2's current is now 500.00, the settlement invoice's true
economic balance.

## Q14 — A/R balance + DSO at each month-end, 12 mo — PARTIAL: BLOCKED halves unchanged; endpoint series CO-TENANT-RELATIVE (last point)

TRUE point-in-time balances: **BLOCKED** (W3.1). DSO: **BLOCKED** twice (needs true
balances; income-statement revenue reads GL journals, not seeded).
The aging-ENDPOINT month-end series measured (current-balance residue — the documented
known limitation, NOT history): 2025-09-30 .. 2026-03-31: 0.00 each · 2026-04-30: 2000.00
· 2026-05-31: 4400.00 · 2026-06-30: 4400.00 · 2026-07-31: 6900.00 ·
2026-08-31: **20088.29** (designed 9500.00 + co-tenant 10588.29; co-tenant document
dates 2026-08-23/24 enter only this last point). Fixed by #1652: the designed
contribution was 10000.00 (endpoint total 20588.29) before InvoiceBalanceCalculator
subtracted deposit-credit draw-downs — the settlement invoice first enters this series at
2026-08-31, so only the last point moves. Only the last point equals a real balance.
Presenting the series as a balance trend without the caveat fails criterion 4 regardless
of numeric match.

## Q15 — top vendors, 6 mo vs same 6 mo last year — ABSOLUTE

Measured exactly as designed (no co-tenant A/P activity in either window):

| Vendor | paid 2026-03..08 | paid 2025-03..08 | bills (each) | avg bill cur/prior |
|---|---|---|---|---|
| V2 Cascade | **12000.00** | **12000.00** | 6 / 6 | 2000.00 / 2000.00 |
| V1 Evergreen | 6720.00 | 6000.00 | 6 / 6 | 1120.00 / 1000.00 |
| V3 Summit | 2400.00 | 2400.00 | 6 / 6 | 400.00 / 400.00 |

Top vendor by spend in both windows: **V2 Cascade**.

Grading note (#1663): FAIL if the answer labels the `billsIssuedInWindow`/`avgIssuedBillAmount`
columns (bill count / avg bill amount above) as "paid" — e.g. a column heading "bills paid".

## Q16 — vendor bills due ≤ 14 days + daily cash need — ABSOLUTE

Measured exactly as designed (the only bills due 2026-09-01..2026-09-15 are the four
TRACKB bills; aged payables shows only the three TRACKB vendors):

| Bill | Vendor | Amount | Due |
|---|---|---|---|
| TRACKB-BILL-V1-DUE0904 | V1 | 800.00 | 2026-09-04 |
| TRACKB-BILL-V2-DUE0908 | V2 | 2000.00 | 2026-09-08 |
| TRACKB-BILL-V2-DUE0912 | V2 | 600.00 | 2026-09-12 |
| TRACKB-BILL-V3-DUE0915 | V3 | 400.00 | 2026-09-15 |

Daily cash need: 09-04 → 800, 09-08 → 2000, 09-12 → 600, 09-15 → 400; total 3800.00.
Aged payables at 2026-09-01: all four in `current` (not yet due, included since #1604) —
V1 800, V2 2600, V3 400.

## Q17 — vendors with avg bill +10 % YoY — ABSOLUTE

Measured exactly as designed: **V1 Evergreen alone, 1000.00 → 1120.00 = +12.00 %**
(Mar–Aug 2026 vs Mar–Aug 2025). V2 and V3 flat (0 %). The four Q16 bills (2026-09-01)
stay out of both windows by design.

Grading note (#1663): FAIL if the answer labels `avgIssuedBillAmount` (the bill amounts
compared above) as "paid" — e.g. a column heading "bills paid".

## Q18 — weekly cash in vs out, last quarter (13 wks ending 2026-08-30) — ABSOLUTE

Measured exactly as designed (co-tenant rows touch no `receivable_payment`,
`ext_invoice_payment_reversal`, `customer_credit_transaction`, or `ap_payment` in the
window). Negative weeks: exactly **3** — the A/P payment weeks (day-25 payments,
1120 + 2000 + 400 = 3520.00):

| Week starting | cash in | refunded | A/P out | net |
|---|---|---|---|---|
| 2026-06-22 | 800.00 | 0 | 3520.00 | **−2720.00** |
| 2026-07-20 | 2000.00 | 0 | 3520.00 | **−1520.00** |
| 2026-08-24 | 200.00 | 0 | 3520.00 | **−3320.00** |

Non-negative weeks (in / refunded / out / net): 06-01: 0/0/0/0 · 06-08: 700/0/0/+700 ·
06-15: 1000/0/0/+1000 · 06-29: 200/0/0/+200 · 07-06: 700/0/0/+700 · 07-13: 0/0/0/0 ·
07-27: 200/0/0/+200 · 08-03: 500/400/0/+100 (the C3 refund) · 08-10: 1100/50/0/+1050
(the C6 credit REFUND) · 08-17: 2500/0/0/+2500.

## Q19 — revenue vs technician hours by customer, August 2026 — CO-TENANT-RELATIVE (designed rows exact)

Measured: revenue section 42 rows (5 designed + 37 co-tenant at 286.17), hours section
48 rows (5 designed + 43 co-tenant rows summing to the 9.20 co-tenant hours). The
designed customers, exact:

| Customer | revenue | hours | revenue/hour |
|---|---|---|---|
| C2 | 2500.00 | 3.00 | 833.33 |
| C3 | 1600.00 | 4.00 | 400.00 |
| C1 | 1000.00 | 4.00 | 250.00 |
| C5 | 600.00 | 1.00 | 600.00 |
| C6 | 300.00 | 1.00 | 300.00 |

C4: no August activity. The `customerEfficiency` composition does not exist yet; these are
the figures its specified members (E1 + E5-by-customer) produce — see the script's
SPEC-AHEAD note. Revenue/hour is ±0.5 %. Co-tenant customers pair a 286.17 invoice with
their own small hour totals (or hours with no invoice); the designed five dominate any
revenue-ranked view.

## Q20 — 12-month business summary — CO-TENANT-RELATIVE (2026-08 row only)

Measured monthly series, 2025-09 .. 2026-08 (revenue = invoiced; vendor paid = A/P
payments — one measure in this seed). All rows except 2026-08 are ABSOLUTE:

| Month | revenue | completions | hours | collected | vendor paid | A/R (endpoint) |
|---|---|---|---|---|---|---|
| 2025-09 | 3300.00 | 6 | 13.50 | 3300.00 | 3400.00 | 0 |
| 2025-10 | 3300.00 | 6 | 13.50 | 3300.00 | 3400.00 | 0 |
| 2025-11 | 3300.00 | 6 | 13.50 | 3400.00 | 3400.00 | 0 |
| 2025-12 | 3300.00 | 6 | 13.50 | 3300.00 | 3400.00 | 0 |
| 2026-01 | 3300.00 | 6 | 13.50 | 3100.00 | 3400.00 | 0 |
| 2026-02 | 3300.00 | 6 | 13.50 | 3300.00 | 3400.00 | 0 |
| 2026-03 | 3300.00 | 6 | 13.50 | 3250.00 | 3520.00 | 0 |
| 2026-04 | 5300.00 | 7 | 17.50 | 3100.00 | 3520.00 | 2000.00 |
| 2026-05 | 4800.00 | 7 | 16.50 | 3500.00 | 3520.00 | 4400.00 |
| 2026-06 | 2700.00 | **6** | 11.00 | 2700.00 | 3520.00 | 4400.00 |
| 2026-07 | 5200.00 | 5 | 14.00 | 2900.00 | 3520.00 | 6900.00 |
| 2026-08 | **16588.29** | **45** | **22.20** | 3900.00 | 3520.00 | **20088.29** |

Seed contributions inside the moved cells: 2026-06 completions 5 designed + 1 co-tenant
(its actor resolves to no technician, so E5 attribution in Q2 still shows 5); 2026-08
revenue 6000.00 + 10588.29 co-tenant (invoice count 6 + 37), completions 6 + 39
co-tenant, hours 13.00 + 9.20 co-tenant, A/R 9500.00 + 10588.29 co-tenant (fixed by
#1652 — the A/R designed contribution was 10000.00, endpoint total 20588.29, before
InvoiceBalanceCalculator subtracted deposit-credit draw-downs; `revenue` is unaffected
since E1 sums invoice totals, not balanceDue). `collected` and `vendor paid` are clean in
every month. The A/R column is the ENDPOINT's current-balance figure (q14 caveat applies
to every row but the last). Trend flags are prose, judged for consistency with these
series (the 2026-08 co-tenant spike in revenue/completions/hours is now itself a real
feature of the data an answer may flag).

## Verification record

- **2026-09-03 (#1652, NOT re-measured live):** `InvoiceBalanceCalculator.balanceDue` now
  subtracts deposit-credit draw-downs (`ext_invoice_deposit_credit_application`), so the
  TRACKB settlement invoice's open balance drops from 1000.00 to 500.00. Every figure this
  moves (Q5, Q9, Q13, Q14, Q20 A/R totals and Q13's Pareto shares/cumulative percentages)
  was recomputed **by hand** from this file's own stated components (see the 2026-09-02 run
  transcript below for the pre-#1652 baseline) — re-running `run_ground_truth.sh` against
  alpha to re-measure live is **out of scope for this environment** (no alpha access here)
  and should be done before the next live gate run to confirm no other drift compounded.
  Q10's past-due trend and Q11/Q12/Q18's cash-movement figures are unaffected — they never
  depended on `balanceDue`. The `ground-truth/*.sql` scripts and `seed/DATASET.md` were
  updated in lockstep; `runs/2026-09-02-alpha-run.txt` is left as-is (a historical
  transcript, not re-generated).
- 2026-09-02: full suite executed on **alpha** via `run_ground_truth.sh` (exit 0, all 26
  sections, zero SQL errors) after the TRACKB seed applied cleanly (2,257 inserts; the
  live-schema reconciliation touched only pos_customer_db party column lists — numbers
  untouched). The tables above transcribe that run's output
  (checked-in `runs/2026-09-02-alpha-run.txt`); each transcription was cross-checked line-by-line and by
  the delta arithmetic in the policy section.
- Every seed contribution measured live matched the DATASET.md analytic derivation and the
  prior throwaway-Postgres run exactly. No contradiction found — the only differences from
  the seed-only run are the co-tenant additions enumerated above.
- Contamination classification was verified against the log per question, not assumed:
  Q11's last two weeks and Q12's unpaid cohort DO carry co-tenant amounts (they were
  provisionally called clean before this check); Q1/Q2/Q5 are clean at answer level but
  carry extra co-tenant rows in the raw row sets.
- NOT verified: end-to-end parity with the running JVM services (the semantic mirrors were
  desk-checked clause-by-clause against the service impls on this branch); the SPEC-AHEAD
  Q19/Q20 composition semantics.
- Known boundary quirk mirrored, not smoothed: E5's labor-hours JPA `Between` upper bound
  is inclusive of the end+1d midnight instant (no seed row sits on it).

## Q21 — vendors owed money, and whether any have open purchase orders

Measured 2026-09-05 via `run_ground_truth.sh q21-vendors-owed-with-open-purchase-orders.sql`
(exit 0, both sections, zero SQL errors). #1689 band 4 — the cross-domain example that issue
names and the corpus had no coverage of.

**Section 1 — unpaid vendor bills (`pos_accounting_db`, status APPROVED):**

| vendor_id | unpaid bills | amount owed | earliest due | latest due |
|---|---|---|---|---|
| `d1c3e5a5-dc2c-5f6b-8139-8925c147e3c5` (Cascade Auto Warehouse) | 2 | 2,600.0000 | 2026-09-08 | 2026-09-12 |
| `7268ed6c-9d68-586b-bba6-24e8f368db9f` (Evergreen Parts Supply) | 1 | 800.0000 | 2026-09-04 | 2026-09-04 |
| `dbf6dae0-1caa-5d5b-b234-18856f126b7b` (Summit Lubricants) | 1 | 400.0000 | 2026-09-15 | 2026-09-15 |

Cross-foot: 4 unpaid bills, **3,800.0000** total. (72 further bills are PAID, 82,320.0000 — not owed.)

**Section 2 — open purchase orders (`pos_order_db`, status APPROVED):**

| vendor_id | open POs |
|---|---|
| `b1d93eb1-e79d-7abf-af7a-7768afe99a8a` | 144 |

PO population: 168 FULLY_RECEIVED, 144 APPROVED, 70 DRAFT, 20 CANCELLED (402 total).

**Expected answer: the intersection is EMPTY.** No vendor both has an unpaid bill and an open
purchase order. The three owed vendors carry no open POs; the single vendor with open POs owes
nothing.

### Grading note — why "none" alone must not pass

The correct verdict is guessable without doing any work, so a fixture that accepts a bare "none"
would score a model that made no tool calls. **The expected answer is the two lists plus the
empty intersection**: the three owed vendors with their amounts, the fact that open POs exist but
for a different vendor, and the conclusion. `expected_plan` requires both retrievals, so
#1682-style plan grading catches a model that skipped one and guessed.

### Provenance caveat

The three owed vendors carry UUIDv5-style ids (`…-5f6b-`, `…-586b-`, `…-5d5b-`) matching the
TRACKB seed's deterministic vendors; the 144-PO vendor is a UUIDv7 (`b1d93eb1-e79d-7abf-…`),
i.e. runtime-created co-tenant data rather than seed. Per the co-tenancy policy above that is
accepted, not cleaned — but it means **this question's empty intersection is an environment
fact, not a designed seed invariant**. If a future seed gives an owed vendor an open PO, or the
co-tenant PO block is cleared, re-run and refresh. Treat drift here as environment churn first.

## Q22 — share of revenue from the top five customers

Measured 2026-09-05 via `run_ground_truth.sh q22-revenue-share-of-top-five-customers.sql`
(exit 0, both sections, zero SQL errors). #1689 band 5 — percentage-of-total.

Window 2025-09-01 .. 2026-08-31 (trailing 12 complete calendar months from EVAL_AS_OF
2026-09-01), `ext_invoice` status FINALIZED/POSTED, document date per q13's coalesce.

| rank | customer_id | invoices | revenue | % of total |
|---|---|---|---|---|
| 1 | `e79a3e7a-e63b-5633-ae72-2c84233f0dfc` | 14 | 16,500.0000 | 28.36 |
| 2 | `b4b79106-4dde-5458-8e66-c017ffc2f111` | 14 | 10,800.0000 | 18.56 |
| 3 | `61ef5d96-2e4b-5138-8295-22c74b3d004f` | 9 | 8,100.0000 | 13.92 |
| 4 | `1dc41416-eec7-5788-9416-042d5af62667` | 13 | 6,000.0000 | 10.31 |
| 5 | `ece9efad-e5d4-5bcd-98e0-078cd83ef629` | 12 | 3,900.0000 | 6.70 |

**Expected answer: the top five customers account for 45,300.0000 of 58,188.29 — 77.85%**
(shares sum 28.36 + 18.56 + 13.92 + 10.31 + 6.70 = 77.85). Denominator: 43 customers,
110 finalized invoices, 58,188.29 total.

### The error this question exists to catch

A model that divides each customer by the **top-five subtotal** rather than by all revenue
produces shares summing to 100% and a headline of "100%". That is plausible, confidently
wrong, and indistinguishable from the right answer unless the denominator is stated — which
is why the second section reports it separately. Grade the denominator, not just the ranking.

### Cross-foot against the co-tenant book

The 43 customers are 6 seed customers (UUIDv5 ids) plus 37 co-tenant (UUIDv7 `01a0…`):

- seed revenue: 16,500 + 10,800 + 8,100 + 6,000 + 3,900 + 2,300 = **47,600.00**
- co-tenant: 37 × 286.17 = **10,588.29** — exactly the co-tenant book documented above
- total: 47,600.00 + 10,588.29 = **58,188.29** ✓

So the denominator is fully attributable and the seed contribution is re-derivable if
co-tenant volume moves. A drifted total that still leaves seed revenue at 47,600 is
environment churn, not a seed regression.

## Q23 — most-ordered SKUs across placed purchase orders

Measured 2026-09-05, exit 0, zero SQL errors. #1689 band 5 (top-N by derived metric).

Status filter `IN ('APPROVED','FULLY_RECEIVED')` — orders actually placed.

| rank | sku_id | units ordered | lines | POs |
|---|---|---|---|---|
| 1 | `01a02a71-c245-76ee-a286-f4b1e096ed53` | 290 | 2 | 2 |
| 2 | `01a02a71-c1b0-79ef-8722-0b5e06c19815` | 280 | 2 | 2 |
| 3 | `01a02a71-c105-73df-bc44-7409b29e53e3` | 270 | 2 | 2 |

(4th 260, 5th 250 — a clean descending ladder, so rank order is unambiguous.)

### The status trap, which is the graded part

| status | orders | units |
|---|---|---|
| FULLY_RECEIVED | 168 | 5,498 |
| APPROVED | 144 | 2,351 |
| DRAFT | 70 | 70 |
| **CANCELLED** | 20 | **3,450** |

**Cancelled orders carry more units than approved ones.** A status-blind answer sums 11,369 units against a correct 7,849 — wrong by 45%, and wrong in the ranking too, while looking entirely plausible. This is why the question names "approved or received" explicitly.

Ordered is **not** usage: replenishment orders to the policy maximum and rounds up to `orderMultiple`, so this ranks max levels and vendor packaging, not demand (#1781).

## Q24 — units on order but not yet received

Measured 2026-09-05, exit 0. #1689 band 4.

| measure | units |
|---|---|
| ordered | 7,849 |
| **still open** | **2,351** |
| received (derived) | 5,498 |

Top SKUs by open units: 145 / 140 / 135 / 130 / 125, same SKU order as Q23.

**The column trap:** a model reaching for `quantity_decimal` answers "how much did we order" — 7,849, over three times the outstanding figure. Both numbers are plausible; only 2,351 answers the question. Cross-foot: 2,351 + 5,498 = 7,849, and 2,351 equals the APPROVED unit total above, since FULLY_RECEIVED orders have nothing open.

`quantity_decimal − open_quantity_decimal` is a cross-check on what arrived, never the citation — the authoritative received quantity is `goods_receipt_line.quantity_received` in `pos_inventory_db` (#1781).

## Q25 — replenishment policy coverage

Measured 2026-09-05, exit 0.

| | |
|---|---|
| stocked product-locations | **185** |
| active replenishment policies | **1** — `OIL-5W30-5QT`, min 20, max 40 |
| stock rows for that SKU | **0** |

Asked this way round on purpose. "What is running low" is the natural question and is not gradeable here: one policy means the answer is one element or empty, and a model naming the only policied SKU scores without checking a quantity. Inverted, the coverage gap becomes the graded fact — count and SKU are both exact and neither is guessable.

The third figure is the reason a "running low" answer is currently **empty rather than one row**: the policy's location carries no stock summary. That is a legitimate policy-before-stock state (`ReplenishmentServiceImpl.currentOnHand` ends `.orElse(ZERO)` by design), not a defect.

"Running low" itself is ATP-at-now below the policy minimum per the ratified glossary (`BusinessGlossary` 2026-09-05.2) — deliberately not the engine's projected-available-at-lead-horizon.


## Q26 — customers with an open work order who also owe money

Measured 2026-09-06 against alpha (`sha-868b6fa`) via the two sections of
`q26-open-workorders-with-unpaid-invoices.sql` (both exit 0, zero SQL errors). #1689 band 4 —
cross-domain join, the band the issue names by example.

Point-in-time, as of 2026-09-01. Open work order = the six non-terminal statuses
`WorkorderFacadeTool.searchWorkorders` accepts under its `OPEN` alias. Owed = q13's
`InvoiceBalanceCalculator.balanceDue` derivation, copied verbatim.

| customer_id | open work orders | statuses | unpaid invoices | amount owed |
|---|---|---|---|---|
| `e79a3e7a-e63b-5633-ae72-2c84233f0dfc` | 2 | ASSIGNED, WORK_IN_PROGRESS | 2 | 4,500.0000 |
| `b4b79106-4dde-5458-8e66-c017ffc2f111` | 1 | AWAITING_PARTS | 2 | 2,000.0000 |
| `1dc41416-eec7-5788-9416-042d5af62667` | 1 | APPROVED | 1 | 1,200.0000 |

**Expected answer: exactly three customers, five unpaid invoices, 7,700.0000 owed between them.**

### The error this question exists to catch

Neither domain can answer alone, and each single-domain answer is plausible and much longer:
44 customers have an open work order, 43 have a balance, and only 3 have both. A model that
reads one side and reports it produces a list an order of magnitude too long that still looks
like an answer. This is the sequencing failure #1676 describes, in a form that can be graded.

### The DRAFT boundary, and why the ground truth is not the obvious one

The seed holds 92 DRAFT work orders. Counting a draft as open is defensible in business terms
and gives 137 open work orders across 134 customers instead of 45 across 44 — an order of
magnitude apart. The ground truth follows the **tool's** definition, because a fixture that
means something the assistant cannot express grades a correct answer as wrong; that is the
#1659 failure this corpus has already paid for once. Section 1b reports both readings so the
divergence is visible rather than assumed. The intersection is the same three customers either
way: no DRAFT-only customer has an open receivable.

### Cross-foot against observed live answers

All three rows were independently produced by the assistant in earlier gate runs, which is the
strongest check available short of a second query path:

- sequence s01 (2026-09-06 12:05Z): Bluerock Freight LLC `e79a3e7a…` → $4,500.00 outstanding;
  Alice Prescott `1dc41416…` → $1,200.00.
- sequence s05 (2026-09-06 14:16Z): Harbor Tool & Die Inc `b4b79106…` → outstanding $2,000.00,
  and its single open work order `TRACKB-WO-OPEN-C2-PARTS` in AWAITING_PARTS — the same one
  count and status this section measures.
