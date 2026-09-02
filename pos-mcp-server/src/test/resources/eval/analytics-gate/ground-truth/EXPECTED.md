# Analytics gate — expected answers (TRACKB seed, EVAL_AS_OF 2026-09-01)

Per-question expected figures, derived **analytically from `../seed/DATASET.md`** (the design)
and cross-checked against the seeded rows by executing every `qNN-*.sql` in this directory on
a throwaway Postgres 16 loaded with `../seed/sql/*.sql` (2026-09-02). Where the two could have
disagreed they did not; no DATASET.md number failed reconciliation. Each script's header names
the endpoint semantics it mirrors and any `DIVERGENCE` between those semantics and the
plain-English question — the gate checks the answer against the ENDPOINT number, and criterion
4 requires the answer to carry the divergence caveat where one is flagged.

Customers: C1 Bluerock Freight, C2 Harbor Tool & Die, C3 Alice Prescott, C4 Marcus Webb,
C5 Dana Whitfield, C6 Peter Okafor. Technicians: Sam Ellison, Nadia Torres, Alex Kim.
Vendors: V1 Evergreen, V2 Cascade, V3 Summit. UUIDs in DATASET.md's cast table.

## Q1 — top technicians by labor revenue, August 2026 — READY

| Technician | completed WOs | billed hours | labor revenue | avg hrs/WO |
|---|---|---|---|---|
| Nadia Torres | 2 | 4.00 | **1500.00** | 2.00 |
| Alex Kim | 3 | 5.00 | 950.00 | 1.67 |
| Sam Ellison | 1 | 4.00 | 600.00 | 4.00 |

Top by labor revenue: **Nadia**. (Top by hours would be Alex; top avg hrs/WO Sam.)

## Q2 — technician 3-month comparison (Jun/Jul/Aug 2026) — READY

| Month | Nadia (WO/hrs/rev) | Alex | Sam |
|---|---|---|---|
| 2026-06 | 3 / 8.00 / 1100.00 | 2 / 3.00 / 250.00 | — |
| 2026-07 | 3 / 8.00 / 2100.00 | 1 / 2.00 / 250.00 | 1 / 4.00 / 600.00 |
| 2026-08 | 2 / 4.00 / 1500.00 | 3 / 5.00 / 950.00 | 1 / 4.00 / 600.00 |

## Q3 — most reopens ≤ 7 days, quarter 2026-07-01..2026-09-01 — READY

**Sam Ellison, 2 reopens** (C1's Jul monthly WO, 3d gap; C1's Aug monthly WO, 5d gap).
Alex 1 (C3 Jul monthly, 4d). Nadia 0 — her C1 WO reopened at 25d is the designed decoy
outside the 7-day rule.

## Q4 — avg WO-creation→invoice lag by month, Mar–Aug 2026 — READY

2026-03: **2.00d** (6) · 04: **3.00d** (7) · 05: **4.00d** (7) · 06: **5.00d** (5) ·
07: **6.00d** (5) · 08: **7.00d** (5). All months through 2026-02 are 2.00d.
DIVERGENCE: August has 7 WO-linked invoices but the two deposit-pair invoices carry a NULL
replica anchor and are excluded (#1592), leaving 5.

## Q5 — open WOs for customers > 60 days past due — READY

60+ customers (due-date basis, as of 2026-09-01): **C1** (2000.00 in 90+) and **C2**
(1500.00 in 61–90). C4 is past due only 49d — excluded by design. Their open workorders:

| WO | Customer | Status | Created |
|---|---|---|---|
| TRACKB-WO-OPEN-C1-ASSIGNED | C1 | ASSIGNED | 2026-08-18 |
| TRACKB-WO-OPEN-C1-WIP | C1 | WORK_IN_PROGRESS | 2026-08-24 |
| TRACKB-WO-OPEN-C2-PARTS | C2 | AWAITING_PARTS | 2026-08-20 |

TRACKB-WO-OPEN-C3-APPROVED (C3) is the decoy — open, but C3 is not 60+ past due; it must
NOT appear in the answer.

## Q6 — revenue / parts / labor / margin by customer — **BLOCKED**

E13 deferred to Wave 3 by D2; true parts COST lives in pos-inventory, which the seed
deliberately does not cover. No expected figures are specified — deriving a margin from sell
prices would be a fabrication. Revisit when E13 + its cost source ship (extend seed + script
+ this section together, plan §7).

## Q7 — top customers, 12 mo vs prior 12 mo — READY

| Customer | 2025-09-01..2026-08-31 | 2024-09-01..2025-08-31 |
|---|---|---|
| C1 | **16500.00** (14 inv) | 12000.00 (12) |
| C2 | **10300.00** (13) | 6000.00 (12) |
| C4 | 8100.00 (9) | **10800.00** (12) |
| C3 | 6000.00 (13) | 4800.00 (12) |
| C5 | 3900.00 (12) | 3600.00 (12) |
| C6 | 2300.00 (11) | 2400.00 (12) |

Ranking last-12mo: C1, C2, C4, C3, C5, C6. Prior-12mo: C1, C4, C2, C3, C5, C6.
(Deposit-take 500.00 excluded from C2's August per D8/#1623.)

## Q8 — no purchase 90 days, > $10k prior year — READY

**C4 Marcus Webb** alone: prior-year (2024-09-01..2025-08-31) revenue 10800.00 > 10000;
last invoice 2026-05-30 = 94 days before as-of (> 90). No other customer exceeds 10k in that
window except C1 (12000.00), whose last invoice is 2026-08-01 (31 days — active).

## Q9 — top 20 customers: revenue, count, avg, balance, days-to-pay — READY

Window 2025-09-01..2026-08-31; balance as of 2026-09-01; only 6 customers exist.

| Customer | revenue | count | avg value | outstanding | avg days-to-pay |
|---|---|---|---|---|---|
| C1 | 16500.00 | 14 | 1178.5714 | 4500.00 | 20.02 |
| C2 | 10300.00 | 13 | 792.3077 | 2500.00 | 22.10 |
| C4 | 8100.00 | 9 | 900.0000 | 900.00 | 15.02 |
| C3 | 6000.00 | 13 | 461.5385 | 1200.00 | 10.02 |
| C5 | 3900.00 | 12 | 325.0000 | 600.00 | 40.02 |
| C6 | 2300.00 | 11 | 209.0909 | 300.00 | 75.02 |

Days-to-pay is the specified E10-derived figure (first zero-balance application −
finalized_at, averaged over window-finalized invoices that reached zero; the 0.02 is the
designed 14:30→15:00 clock offset). ±0.5 % tolerance. DIVERGENCE: C2's outstanding carries
the settlement invoice at 1000.00 open though economically 500.00.

## Q10 — rising sales AND rising past-due, 3 months — PARTIAL (trend half BLOCKED)

Monthly E1 revenue (Jun/Jul/Aug): C2 **800 → 1000 → 2500** (the designed riser).
C1 1000 → 3500 → 1000, C3 400 → 400 → 1600, C5 300 → 300 → 600, C6 200 → 0 → 300.

Past-due trend: the TRUE point-in-time trend is **BLOCKED** (W3.1 prerequisite — no
historical balance reconstruction). What the aging ENDPOINT returns when looped over
month-end as-ofs (current balances, moving boundaries — script section 2) is, per customer
past-due: C2 0 → 1500 → 1500; C1 2000 → 2000 → 2000; C4 0 → 0 → 900.
On endpoint numbers the designed answer is **C2** (rising sales 800→1000→2500 AND past-due
0→1500→1500); an honest answer must caveat that the balances are current-basis, not
historical (criterion 4). C1 is the decoy (flat sales trend, flat past-due); C3/C5 rise in
sales with zero past-due.

## Q11 — weekly invoiced vs settled, 12 weeks (Mon–Sun, ending 2026-08-30) — READY

| Week starting | invoiced | collected | nonCash | settled | settlement % |
|---|---|---|---|---|---|
| 2026-06-08 | 0 | 700.00 | 0 | 700.00 | — |
| 2026-06-15 | 200.00 | 1000.00 | 0 | 1000.00 | 500.00 |
| 2026-06-22 | 0 | 800.00 | 0 | 800.00 | — |
| 2026-06-29 | 2700.00 | 200.00 | 0 | 200.00 | 7.41 |
| 2026-07-06 | 0 | 700.00 | 0 | 700.00 | — |
| 2026-07-13 | 0 | 0 | 0 | 0 | — |
| 2026-07-20 | 2500.00 | 2000.00 | 0 | 2000.00 | 80.00 |
| 2026-07-27 | 1400.00 | 200.00 | 0 | 200.00 | 14.29 |
| 2026-08-03 | 0 | 100.00 | 0 | 100.00 | — |
| 2026-08-10 | 4000.00 | 1100.00 | 500.00 | 1600.00 | 40.00 |
| 2026-08-17 | 600.00 | 2500.00 | 0 | 2500.00 | 416.67 |
| 2026-08-24 | 0 | 200.00 | 0 | 200.00 | — |

Monthly cross-foot: Jun invoiced 2700 / Jul 5200 / Aug 6000 and Aug collected 3900,
nonCash 500 — matches DATASET.md's E2 headline table. Rates are NULL (—) when invoiced = 0.
Week 2026-08-03 carries the reversal netting (deposit-take application 500 − C3 refund
reversal 400 = 100); week 2026-08-10 has the C3 re-pay 400 + C3 Aug monthly 400 + C5 Jul
300 = 1100 applied, plus the 500 deposit draw-down in nonCash.

## Q12 — payment-lag cohorts, invoices finalized 2026-03-01..2026-08-31 — READY

| Cohort | invoices | amount |
|---|---|---|
| ≤30 | 20 | 14000.00 |
| 31–60 | 5 | 1500.00 |
| 61–90 | 4 | 800.00 |
| unpaid | 8 | 11500.00 |

≤30 includes the deposit-take document (D8 not extended to E3 — script DIVERGENCE note);
unpaid = the 8 designed open invoices (C1 2000+2500, C2 1500 + settlement 2500, C3 1200,
C4 900, C5 600, C6 300).

## Q13 — A/R Pareto + past-due share, as of 2026-09-01 — READY

| # | Customer | total | current | 31–60 | 61–90 | 90+ | past-due % of cust. | share | cum. |
|---|---|---|---|---|---|---|---|---|---|
| 1 | C1 | 4500.00 | 2500.00 | 0 | 0 | 2000.00 | 44.44 | 45.00 | 45.00 |
| 2 | C2 | 2500.00 | 1000.00 | 0 | 1500.00 | 0 | 60.00 | 25.00 | 70.00 |
| 3 | C3 | 1200.00 | 1200.00 | 0 | 0 | 0 | 0.00 | 12.00 | 82.00 |
| 4 | C4 | 900.00 | 0 | 900.00 | 0 | 0 | 100.00 | 9.00 | 91.00 |
| 5 | C5 | 600.00 | 600.00 | 0 | 0 | 0 | 0.00 | 6.00 | 97.00 |
| 6 | C6 | 300.00 | 300.00 | 0 | 0 | 0 | 0.00 | 3.00 | 100.00 |

Grand total 10000.00. Pareto-80 set = {C1, C2, C3} (boundary row C3 at 82 %). C5's 600 is
not yet due (due 2026-09-05) and sits in current per #1604. DIVERGENCE: C2's 1000.00
"current" is the settlement invoice; economically 500.00 (deposit draw-down invisible).

## Q14 — A/R balance + DSO at each month-end, 12 mo — PARTIAL (mostly BLOCKED)

TRUE point-in-time balances: **BLOCKED** (W3.1). DSO: **BLOCKED** twice (needs true balances,
and income-statement revenue reads GL journals, which the seed does not populate).
What the aging ENDPOINT returns per month-end asOfDate (current-balance residue — the
documented known limitation, NOT history):
2025-09-30 .. 2026-03-31: 0.00 each · 2026-04-30: 2000.00 · 2026-05-31: 4400.00 ·
2026-06-30: 4400.00 · 2026-07-31: 6900.00 · 2026-08-31: 10000.00.
Only the last point equals a real balance. An answer presenting the series as a balance
trend without that caveat fails criterion 4 regardless of numeric match.

## Q15 — top vendors, 6 mo vs same 6 mo last year — READY

| Vendor | paid 2026-03..08 | paid 2025-03..08 | bills (each) | avg bill cur/prior |
|---|---|---|---|---|
| V2 Cascade | **12000.00** | **12000.00** | 6 / 6 | 2000.00 / 2000.00 |
| V1 Evergreen | 6720.00 | 6000.00 | 6 / 6 | 1120.00 / 1000.00 |
| V3 Summit | 2400.00 | 2400.00 | 6 / 6 | 400.00 / 400.00 |

Top vendor by spend in both windows: **V2 Cascade**.

## Q16 — vendor bills due ≤ 14 days + daily cash need — READY

| Bill | Vendor | Amount | Due |
|---|---|---|---|
| TRACKB-BILL-V1-DUE0904 | V1 | 800.00 | 2026-09-04 |
| TRACKB-BILL-V2-DUE0908 | V2 | 2000.00 | 2026-09-08 |
| TRACKB-BILL-V2-DUE0912 | V2 | 600.00 | 2026-09-12 |
| TRACKB-BILL-V3-DUE0915 | V3 | 400.00 | 2026-09-15 |

Daily cash need: 09-04 → 800, 09-08 → 2000, 09-12 → 600, 09-15 → 400; total 3800.00.
Aged payables at 2026-09-01: all four in `current` (not yet due, included since #1604) —
V1 800, V2 2600, V3 400.

## Q17 — vendors with avg bill +10 % YoY — READY

**V1 Evergreen alone: 1000.00 → 1120.00 = +12.00 %** (Mar–Aug 2026 vs Mar–Aug 2025).
V2 and V3 are flat (0 %). The four Q16 bills are dated 2026-09-01 and stay out of both
windows by design.

## Q18 — weekly cash in vs out, last quarter (13 wks ending 2026-08-30) — READY

Negative weeks: exactly **3** — the A/P payment weeks (payments land day 25 monthly,
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

## Q19 — revenue vs technician hours by customer, August 2026 — READY (SPEC-AHEAD)

| Customer | revenue | hours | revenue/hour |
|---|---|---|---|
| C2 | 2500.00 | 3.00 | 833.33 |
| C3 | 1600.00 | 4.00 | 400.00 |
| C1 | 1000.00 | 4.00 | 250.00 |
| C5 | 600.00 | 1.00 | 600.00 |
| C6 | 300.00 | 1.00 | 300.00 |

C4: no August activity. The `customerEfficiency` composition does not exist yet; these are
the figures its specified members (E1 + E5-by-customer) produce — see the script's
SPEC-AHEAD note. Revenue/hour is ±0.5 %.

## Q20 — 12-month business summary — READY (SPEC-AHEAD; aging member divergent)

Monthly, 2025-09 .. 2026-08 (revenue = invoiced in this seed; vendor paid = A/P payments):

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
| 2026-06 | 2700.00 | 5 | 11.00 | 2700.00 | 3520.00 | 4400.00 |
| 2026-07 | 5200.00 | 5 | 14.00 | 2900.00 | 3520.00 | 6900.00 |
| 2026-08 | 6000.00 | 6 | 13.00 | 3900.00 | 3520.00 | 10000.00 |

Seven metrics: revenue, completions, hours, collected (E2), invoiced (= revenue here),
vendor spend / A/P payments (one measure in this seed), A/R aging batch. The A/R column is
the ENDPOINT's current-balance figure (q14 caveat applies to every row but the last).
Trend flags are prose, judged for consistency with these series (e.g. V1's vendor-paid step
3400→3520 at 2026-03; revenue jump in 2026-04; collected < invoiced from 2026-04 on as the
open A/R builds).

## Verification record

- Every script executed 2026-09-02 on a local throwaway Postgres 16 (real Flyway column
  types for every column read; `../seed/sql/*.sql` applied verbatim) via
  `run_ground_truth.sh` with `ALPHA_ENV_FILE`/`POSTGRES_CONTAINER` overrides — all sections
  ran clean and produced exactly the figures above.
- NOT verified on the alpha stack or against the live service implementations end-to-end
  (no JVM run); the semantic mirrors were desk-checked clause-by-clause against
  InvoiceAnalyticsServiceImpl, AccountingAnalyticsServiceImpl,
  FinancialReportingServiceImpl + InvoiceBalanceCalculator, VendorBillServiceImpl,
  PaymentApplicationQueryServiceImpl and WorkorderAnalyticsServiceImpl as of this branch.
- Known boundary quirk mirrored, not smoothed: E5's labor-hours JPA `Between` upper bound is
  inclusive of the end+1d midnight instant (no seed row sits on it).
