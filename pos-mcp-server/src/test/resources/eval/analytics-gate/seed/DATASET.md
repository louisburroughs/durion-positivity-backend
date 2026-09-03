# TRACKB analytics-gate fixture dataset — narrative spec

This document is the human specification of the seed; `generate_seed.py` is its
compilation and `seed/sql/*.sql` its output. Change them together (plan §7, fixture
drift: a fixture change without a matching ground-truth change is a review blocker).

- **As-of date**: `EVAL_AS_OF = 2026-09-01`. Every ground-truth run and every aging
  call uses this date.
- **Months**: 24 invoice-bearing calendar months, 2024-09 .. 2026-08. The 25th month
  (2026-09, the as-of month) is the boundary-safety month: nothing is invoiced in it
  and only the four Q16 vendor bills are dated on 2026-09-01.
- **Determinism**: all UUIDs are `uuid5` under the namespace
  `uuid5(NAMESPACE_URL, "durion://pos-mcp-server/eval/analytics-gate/trackb")`
  = `e9574906-4b31-563c-ad51-a6dd3fd21ca9`. Rerunning the generator reproduces every
  id and every SQL file byte-for-byte.
- **Markers / idempotency**: every row is recognizable — `invoice_number`,
  `workorder_number`, `bill_number`, `payment_ref`, `application_request_id`,
  `request_id`, `*_number` all start `TRACKB-`; audit columns (`created_by`,
  `reversed_by`, `created_by_user_id`, `finalized_by`) are `trackb-seed`; tables with
  neither are deleted by their deterministic ids. Each SQL file deletes its prior
  TRACKB rows first, inside one transaction, and uses plain INSERTs (a collision with
  drifted data fails loudly — no ON CONFLICT).
- **Timezone**: everything is UTC. Invoices are created 14:00Z and finalized 14:30Z
  the same day; payments clear at 15:00Z; workorders complete at 10:00Z.
- **Tax is excluded by user decision.** `tax_amount`/`tax` are 0.00 everywhere,
  subtotal == total, and no tax tables (`invoice_line_tax`, `invoice_tax_summary`,
  `ext_invoice_tax`, pos-tax anything) are seeded.
- **Round numbers**: every money amount is a multiple of 50.00; hours are x.00/x.50.

## Cast

| Entity | Name | UUID |
|---|---|---|
| Location | TRACKB Main Street Garage | `488cfdfb-01ed-5028-83d3-deada1a5c961` |
| Technician T1 | Sam Ellison (`sam.ellison`) — the Q3 reopen technician | person `9dc929b7-1e21-5ccc-89e0-4938905332ca` |
| Technician T2 | Nadia Torres (`nadia.torres`) — top labor revenue last month | person `343b5366-8531-5a2e-9976-b1eec230bf0e` |
| Technician T3 | Alex Kim (`alex.kim`) — most completions/hours last month | person `8eea60f6-2807-5578-90ee-187caf666b3b` |
| C1 (commercial) | Bluerock Freight LLC — A/R whale (45%), one 90+ invoice | `e79a3e7a-e63b-5633-ae72-2c84233f0dfc` |
| C2 (commercial) | Harbor Tool & Die Inc — second 60+ past-due; rising sales; deposit pair | `b4b79106-4dde-5458-8e66-c017ffc2f111` |
| C3 (individual) | Alice Prescott — fast payer (10d); the refund/reversal chain | `1dc41416-eec7-5788-9416-042d5af62667` |
| C4 (individual) | Marcus Webb — lapsed high-value customer (Q8) | `61ef5d96-2e4b-5138-8295-22c74b3d004f` |
| C5 (individual) | Dana Whitfield — 40d payer (31–60 cohort); credit-memo case | `ece9efad-e5d4-5bcd-98e0-078cd83ef629` |
| C6 (individual) | Peter Okafor — 75d payer (61–90 cohort); customer-credit case | `7f0433a9-2dd1-5afa-8e56-4f803cca15b7` |
| Vendor V1 | Evergreen Parts Supply — avg bill +12% YoY (Q17) | `7268ed6c-9d68-586b-bba6-24e8f368db9f` |
| Vendor V2 | Cascade Auto Warehouse — top vendor by spend (Q15) | `d1c3e5a5-dc2c-5f6b-8139-8925c147e3c5` |
| Vendor V3 | Summit Lubricants — small steady vendor | `dbf6dae0-1caa-5d5b-b234-18856f126b7b` |

## Invoice / payment scenario per customer

All monthly invoices are FINALIZED, NET_30 (due = created + 30d) unless stated. The
labor/parts split is carried identically on `invoice_items` (pos-invoice) and
`ext_invoice.labor_total/parts_total` (pos-workorder replica).

| Customer | Cadence | Amount (labor/parts) | Pays after | Specials |
|---|---|---|---|---|
| C1 | day 1, all 24 months | 1000 (600/400) | 20d | + open 2000 (1200/800) created 2026-04-15, due 2026-05-15 (**109d past due → 90+**); + open 2500 (1500/1000) created 2026-07-21, due 2026-08-20 (12d → current). Jul+Aug monthly workorders completed by **Sam** (reopen targets); everything else Nadia. |
| C2 | day 3, through 2026-05 | 500 (300/200) | 25d | Jun 2026 = 800 (500/300), Jul = 1000 (600/400), both 20d (**rising sales, Q10**); + open 1500 (900/600) created 2026-05-21, due 2026-06-20 (**73d → 61–90**); + Aug deposit pair (below). |
| C3 | day 1, all 24 months | 400 (250/150) | 10d | Jul 2026 invoice is the **refund chain** (below); + open 1200 (700/500) created 2026-08-13, NET_15, due 2026-08-28 (4d → current). Tech: Alex. |
| C4 | day 25, through 2026-04 | 900 (500/400) | 15d | final invoice 900 created **2026-05-30**, NET_45, due 2026-07-14 (**49d → 31–60**), never paid; **no invoice after 2026-05-30** (94 days before as-of). Tech: Sam. |
| C5 | day 1, through 2026-07 | 300 (0/300) | 40d | Feb 2026 invoice is the **credit-memo case**; + open 600 (0/600) created 2026-08-21, NET_15, due 2026-09-05 (**not yet due → current**). Tech: Nadia, 1.00h flat. |
| C6 | day 15, through 2026-06 | 200 (0/200) | 75d | Nov 2025 invoice is the **customer-credit case**; **no July invoice** (75d payments after ~2026-06-18 would clear past the as-of); + open 300 (0/300) created 2026-08-15, NET_15, due 2026-08-30 (2d → current). Tech: Alex, 1.00h. |

Every cash payment is one `receivable_payment` (cleared_at = application time) plus one
`payment_application` (`invoice_balance_before` = invoice total, `after` = 0 for full
payments), so `received` == `applied` in every window except where the specials below
apply.

### The refund / reversal chain (D6/D7, feeds E2 `refunded` + `applicationReversals`, E10 `reversed`)

C3's 2026-07-01 invoice (400): paid in full 2026-07-11 → refunded 2026-08-05 →
re-paid 2026-08-10.

- pos-invoice: `payment_intents` (CAPTURED, 400) + `refund_records` (COMPLETED 2026-08-05).
- pos-accounting: application #1 (400, 2026-07-11, balance_after 0);
  `payment_application_reversal` (400, reversed_at 2026-08-05);
  `ext_invoice_payment_reversal` (REFUND, 400, reversed_at 2026-08-05);
  application #2 (400, 2026-08-10, balance_after 0) with its own `receivable_payment`.
- Net effect: balance 0 (400 − 800 + 400); Q12 cohort still ≤30 (first zero-balance
  application is 2026-07-11, 10 days); August movement: applied +400, reversals 400,
  refunded +400, received +400.

### The customer-credit case (feeds E2 `nonCashSettled` + `refunded` second sources)

C6 overpaid 150.00 on 2025-10-20 (`receivable_payment` with no application;
`customer_credit` amount 150). The 2025-11-15 invoice (200) settles as 100 cash
(2025-11-20, balance_after 100) + 100 `customer_credit_transaction` APPLICATION
(2025-11-16 — sic, recorded before the cash leg cleared; order is irrelevant to every
served figure). The remaining 50 is refunded as a `customer_credit_transaction` REFUND
on **2026-08-15** (adds 50 to August `refunded`). Credit status CONSUMED
(100 applied + 50 refunded = 150).

### The credit-memo case

C5's 2026-02-01 invoice (300): 250 cash at 40d (2026-03-13, balance_after 50) + a
POSTED `credit_memo` (credit_amount 50.00, tax_amount_reversed 0.00, posted
2026-03-20). Balance 0. Note: because no payment application ever recorded
balance_after = 0, the E3 classifier would call this invoice "unpaid" — it is
deliberately dated **outside** the Q12 window (finalized Feb, window starts Mar 1).

### The deposit-take pair (D8/#1623, feeds E2 `nonCashSettled`, exclusion tests for E1/E2)

Both invoices share one workorder (completed by Nadia 2026-08-12, 3.00h):

- **D** `c2-deposit-take`: 500, created/finalized 2026-08-05, DUE_ON_RECEIPT, single
  FEE line, `deposit_source_type = 'WORKORDER'` on `invoices` AND on accounting's
  `ext_invoice`. Paid in full same day (the D5 "deposit cash enters `collected`
  through the ordinary path" rule). **Excluded** from E1 revenue and E2 `invoiced`;
  **included** in E3 cohorts (≤30) per the D8 carve-out.
- **S** `c2-settlement`: 2500 gross (1500/1000), created/finalized 2026-08-12, NET_15,
  due 2026-08-27. Cash 1500 on 2026-08-20 (balance_after 1000) + deposit draw-down 500
  (`deposit_credit_application` in pos-invoice, `ext_invoice_deposit_credit_application`
  replica in accounting, applied_at 2026-08-12).
- **Deliberate real-system artifact**: `InvoiceBalanceCalculator` does NOT subtract
  deposit draw-downs, so accounting reports S with a **1000.00 open balance**
  (economically 500 is outstanding). That 1000 is C2's designed "current" A/R bucket —
  the fixture exercises the documented gap rather than hiding it. Any future fix to
  the balance calculator will move C2's buckets and must update this spec.
- E4 protection: the shared workorder's `ext_workorder.workorder_created_at`
  (pos-invoice replica) is **NULL**, so D and S are excluded from the invoicing-lag
  average (#1592 null-anchor rule) and every monthly average stays exact.

## Designed ground truths (verified by the generator's cross-check)

### A/R aging at 2026-09-01 (Q13 Pareto, Q5) — ages by DUE DATE (#1604)

| Rank | Customer | current | 31–60 | 61–90 | 90+ | total | share | cumulative |
|---|---|---|---|---|---|---|---|---|
| 1 | C1 Bluerock | 2500 | 0 | 0 | 2000 | **4500** | 45% | 45% |
| 2 | C2 Harbor | 1000 | 0 | 1500 | 0 | **2500** | 25% | 70% |
| 3 | C3 Prescott | 1200 | 0 | 0 | 0 | **1200** | 12% | **82%** ← Pareto-80 boundary row |
| 4 | C4 Webb | 0 | 900 | 0 | 0 | **900** | 9% | 91% |
| 5 | C5 Whitfield | 600 | 0 | 0 | 0 | **600** | 6% | 97% |
| 6 | C6 Okafor | 300 | 0 | 0 | 0 | **300** | 3% | 100% |

Grand total **10000.00**. Exactly **2 customers 60+ days past due by due date**: C1
(90+) and C2 (61–90). C4 is past due but only 49 days. The Pareto-80 cutoff lands
after customer 3 of 6 (C1+C2+C3). C5's 600 is **not yet due** (due 2026-09-05) and
appears in `current` under the post-#1604 rule.

### Q5 open workorders for the 60+ customers

Four never-invoiced workorders, all created Aug 2026: C1 ASSIGNED (2026-08-18),
C1 WORK_IN_PROGRESS (2026-08-24, has a running labor entry so E12's
`technicianId` filter matches), C2 AWAITING_PARTS (2026-08-20), and a decoy —
C3 APPROVED (2026-08-26) for a customer that is NOT 60+ past due.

### Q12 payment-lag cohorts (invoices finalized 2026-03-01 .. 2026-08-31)

| Cohort | invoices | amount |
|---|---|---|
| ≤30 | 20 | 14000.00 |
| 31–60 | 5 | 1500.00 |
| 61–90 | 4 | 800.00 |
| unpaid | 8 | 11500.00 |

(≤30 includes the deposit-take D per the D8 carve-out; unpaid = the 8 designed open
invoices: C1×2+2000/2500, C2 1500 + S 2500, C3 1200, C4 900, C5 600, C6 300.)

### E1 revenue by customer (deposit-take excluded; windows on `invoices.created_at`)

| Customer | 2025-09-01..2026-08-31 | 2024-09-01..2025-08-31 | lastInvoiceDate |
|---|---|---|---|
| C1 | **16500** | 12000 | 2026-08-01 |
| C2 | **10300** | 6000 | 2026-08-12 |
| C4 | 8100 | **10800** | **2026-05-30** (Q8: >10k prior year, none in last 90d) |
| C3 | 6000 | 4800 | 2026-08-13 |
| C5 | 3900 | 3600 | 2026-08-21 |
| C6 | 2300 | 2400 | 2026-08-15 |

### Q4 invoicing lag (E4, monthly, WO-linked invoices with non-null replica anchor)

2.00d for every month through 2026-02, then **Mar 2.00 → Apr 3.00 → May 4.00 →
Jun 5.00 → Jul 6.00 → Aug 7.00** (exactly; all invoices in a month share the month's
lag, and the two deposit-pair invoices are excluded via the NULL anchor).

### Q1/E5 technician labor, August 2026

| Technician | completed WOs | billed hours | labor revenue |
|---|---|---|---|
| Nadia Torres | 2 | 4.00 | **1500.00** (settlement 1500 + C5 0) |
| Alex Kim | 3 | **5.00** | 950.00 (250 + 700 + 0) |
| Sam Ellison | 1 | 4.00 | 600.00 |

Top by labor revenue: Nadia; top by hours: Alex; top avg hours/WO: Sam (4.00).
Attribution chain as the impl reads it: completion transition `transitioned_by`
(username) → `ext_people_contact_user_link` (ACTIVE) → person id →
`ext_people_contact_person` name; hours from `workorder_labor_entry` (stopped entries,
start_time in window); revenue from workorder-db `ext_invoice.labor_total` of the
completed WOs.

### Q3/E6 reopens within 7 days, quarter 2026-07-01 .. 2026-09-01

Reopens are the same-status `COMPLETED → COMPLETED` marker rows
`WorkorderStateMachine.reopenCompletedWorkorder` records (reason prefixed
`Reopened: `); the workorder row also carries `is_reopened/reopened_at`.

| Workorder | completed | reopened | gap | completing tech |
|---|---|---|---|---|
| C1 2026-07 monthly | 2026-07-01 | 2026-07-04 | 3d | **Sam** |
| C1 2026-08 monthly | 2026-08-01 | 2026-08-06 | 5d | **Sam** |
| C3 2026-07 monthly | 2026-07-01 | 2026-07-05 | 4d | Alex |
| C1 "open-current" WO (invoice 2500) | 2026-07-21 | 2026-08-15 | 25d — decoy, outside 7d | Nadia |

Answer: **Sam Ellison, 2 reopens** (Alex 1, Nadia 0 within 7d).

### E2 collections, headline windows (calendar months)

| Window | invoiced | applied | reversals | collected | refunded | received | nonCashSettled |
|---|---|---|---|---|---|---|---|
| 2026-06 | 2700 | 2700 | 0 | 2700 | 0 | 2700 | 0 |
| 2026-07 | 5200 | 2900 | 0 | 2900 | 0 | 2900 | 0 |
| 2026-08 | **6000** | 4300 | **400** | **3900** | **450** (400 refund + 50 credit REFUND) | 4300 | **500** (deposit draw-down; settled = 4400) |

(August `invoiced` excludes the 500 deposit-take document.)

### Vendors (E8/E9/aged payables)

Monthly bills, bill day 10, paid in full day 25 the same month (`ap_payment`
GL_POSTED + one `ap_payment_allocation`; bill status PAID):

- **V1 Evergreen**: 1000.00/mo through 2026-02, **1120.00/mo from 2026-03** →
  avgIssuedBillAmount Mar–Aug 2026 = 1120 vs Mar–Aug 2025 = 1000 = **+12.0% YoY (Q17)**.
- **V2 Cascade**: 2000.00/mo — top vendor by paid spend in every window (Q15).
- **V3 Summit**: 400.00/mo.

Q16 — four unpaid APPROVED bills, bill_date 2026-09-01 (kept out of the Mar–Aug bill
windows so Q17's average stays exact), due within 14 days of as-of, several vendors
and days:

| Bill | Vendor | Amount | Due |
|---|---|---|---|
| TRACKB-BILL-V1-DUE0904 | V1 | 800.00 | 2026-09-04 |
| TRACKB-BILL-V2-DUE0908 | V2 | 2000.00 | 2026-09-08 |
| TRACKB-BILL-V2-DUE0912 | V2 | 600.00 | 2026-09-12 |
| TRACKB-BILL-V3-DUE0915 | V3 | 400.00 | 2026-09-15 |

Aged payables at 2026-09-01 = exactly these four, all `current` (not yet due,
included since #1604): V1 800, V2 2600, V3 400; grand total 3800.00.

## Scenario → table map per gate question

| Q | Endpoint | Tables the fixture feeds (owning db) |
|---|---|---|
| Q1/Q2 | E5 technician-labor | `work_order_state_transitions`, `workorder_labor_entry`, `ext_invoice` (labor_total), `ext_people_contact_user_link`, `ext_people_contact_person` (pos_workorder_db) |
| Q3 | E6 reopened + E7 transitions | `work_order_state_transitions` (+ user-link/person replicas) |
| Q4 | E4 invoicing-lag | `invoices`, `ext_workorder.workorder_created_at` (pos_invoice_db) |
| Q5 | aged-receivables + E12 search | accounting `ext_invoice` + balance tables; `workorder` + `workorder_labor_entry` + `ext_customer_party` (pos_workorder_db) |
| Q7/Q8/Q9/Q10 | E1 revenue-by-customer | `invoices` (+ `ext_customer_party` names, pos_invoice_db) |
| Q9/E10 | payment-application list | `payment_application`, `payment_application_reversal` (pos_accounting_db) |
| Q11/E2 | collections | `ext_invoice`, `payment_application(+reversal)`, `ext_invoice_payment_reversal`, `ext_invoice_deposit_credit_application`, `receivable_payment`, `customer_credit_transaction` (pos_accounting_db) |
| Q12/E3 | payment-lag-cohorts | `ext_invoice` + `payment_application` (pos_accounting_db) |
| Q13 | aged-receivables | `ext_invoice`, `payment_application(+reversal)`, `credit_memo`, `customer_credit_transaction` (pos_accounting_db) |
| Q15/Q17/Q18 | E8 vendor-spend | `ap_payment`, `vendor_bill`, `ap_vendor` (pos_accounting_db) |
| Q16 | E9 vendor-bill list + aged-payables | `vendor_bill` (+ `ap_payment_allocation` for open balance) |
| E11 | invoice search | `invoices`, `ext_customer_party`, `ext_workorder` (pos_invoice_db) |

## Deviations from plan §2.3, and why

1. **~148 workorders instead of ~120.** Accounting's `ext_invoice.workorder_id` was
   `NOT NULL` (V5) when this fixture was seeded, so a workorder-less (order-fronted)
   invoice could not be replicated coherently — every one of the 145 invoices
   therefore carries a workorder (144 distinct + the shared deposit-pair WO) plus 4
   open WOs. **#1651 lifted this constraint** (V32 drops the `NOT NULL`; `ext_invoice`
   now legitimately holds order-fronted/counter-sale/standalone-billing invoices with
   a null `workorder_id`, and they appear in A/R aging and collections like any other
   invoice) — the "~30 invoices without workorders" implied by 120/150 is reachable
   against the schema now. This fixture's every-invoice-has-a-workorder shape is a
   fixture choice, not a schema limitation; the seed is not regenerated here.
2. **The settlement invoice's accounting balance is 1000, not 500.** Real-system
   artifact (deposit draw-downs invisible to `InvoiceBalanceCalculator`), embraced and
   folded into C2's designed buckets — see the deposit-pair section.
3. **Q16 bills are dated 2026-09-01**, not late August, so Q17's +12% average is not
   diluted (E8 buckets bills by `bill_date` regardless of status).
4. **C4 sits in the 31–60 bucket** (49 days past due). §2.3 wants exactly two 60+
   customers AND a lapsed customer with an old unpaid invoice; a >90-day-old invoice
   due on normal terms would make C4 a third 60+ customer, so his final invoice is
   NET_45 from 2026-05-30 — old enough for Q8's "no purchase in 90 days", young
   enough (by due date) to stay under 60 days past due.
5. **C6 has no 2026-07 invoice.** A 75-day payer's July invoice could not be paid by
   the as-of date without breaking the designed A/R shape.
6. **Reopens are `COMPLETED → COMPLETED` marker rows**, not "COMPLETED → reopened
   state": that is what the state machine actually records (status never leaves
   COMPLETED on reopen), and E6 pairs completions with those markers.
7. **Tax excluded** (user decision) — no tax tables seeded, all tax amounts 0.
8. **Live-alpha schema drift (found on first apply, 2026-09-02).** The live database
   is the sole authority over Flyway DDL in this checkout; the full live column
   inventory used for validation was pulled from `information_schema` into
   `seed/alpha-live-columns.2026-09-02.json (checked in; originally /tmp/alpha-live-columns.json)`. Per-table drift found and fixed:
   - `pos_customer_db.commercial_party`: live has **no `party_number`** (present in
     Flyway V1; live also drops `email`/`phone_number` and adds `br_*` billing-rule
     columns, `lifecycle_stage`, `version` — all nullable or defaulted). Column
     removed from the insert.
   - `pos_customer_db.person_party`: live has **no `first_name`/`last_name`** (and no
     `email`/`phone_number`) — identity moved to pos-people-contact (#874/#875);
     live adds defaulted `lifecycle_stage`/`version`. Name columns removed from the
     insert. Person display names therefore live only in `pos_people_contact_db`,
     which this seed deliberately does not touch: **nothing the gate endpoints read
     needs them** — customer names in answers resolve from the `ext_customer_party`
     replicas (pos_invoice_db / pos_workorder_db) and technician names from
     `ext_people_contact_person` (pos_workorder_db), all of which are seeded.
   - Every other table in all five databases (33 of 35) validated clean against the
     live inventory in both directions: no unknown columns emitted, and every
     live NOT-NULL-without-default column supplied.

## Columns filled with constants (not scenario-bearing)

- `invoices`: `version 0`, `adjustments_amount 0`, `finalized_by 'trackb-seed'`;
  `order_id` only on the deposit-take invoice.
- `ext_*` replicas: `aggregate_version 1`; dimension `updated_at` = 2024-08-15Z.
- `payment_application.invoice_status` left NULL (nullable smallint; no served figure
  reads it). `receivable_payment.status 'FULLY_APPLIED'`, `unapplied 0`.
- `workorder`: `crm_party_id` = customer UUID string, `shop_id`/`location_id` = the
  location; `workorder_service` rows exist only because `workorder_labor_entry
  .workorder_service_id` is NOT NULL — quantity 1, unit_price/line_total 0.
- `vendor_bill`: `created_by/modified_by 'trackb-seed'`, `approved_at` = bill_date+1h;
  no `vendor_bill_line` rows (nothing served reads them).
- `ap_payment`: `payment_method 'ACH'`, `fee 0`, `net = gross`, `gl_posted_at` =
  payment_date+1h, status `GL_POSTED` (in E8's settled set).
- pos_customer parties: ordinal columns status=0 (ACTIVE), tier=0 (STANDARD),
  party_type=1 (COMMERCIAL) / person rows have no party_type;
  `preferred_contact_method 'EMAIL'`. No name columns on live `person_party` (see
  drift note above); commercial names go in `legal_name`/`display_name`.
- pos_people `employee`: hire_date 2024-01-15, status ACTIVE;
  `employee_location_assignment` role TECHNICIAN (not read by any gate endpoint,
  seeded for coherence).

## Lesson: Flyway DDL and a live schema can diverge

The first alpha apply failed on `commercial_party.party_number` — a column that
exists in this branch's Flyway V1 baseline but not on alpha. A live database may
carry newer migrations than the checked-out branch, repeatable migrations, or
baseline collapses that the working tree has not caught up with. Two safeguards are
therefore load-bearing, not optional:

1. **`apply_seed.sh` wraps each file in one BEGIN/COMMIT with `ON_ERROR_STOP=1`** —
   the failed apply landed nothing partial.
2. **Validate the generator against a live column inventory before applying**: pull
   `{db: {table: [[column, is_nullable, data_type, has_default], ...]}}` from
   `information_schema` on the target host (the 2026-09-02 snapshot is
   `seed/alpha-live-columns.2026-09-02.json (checked in; originally /tmp/alpha-live-columns.json)`) and check every emitted INSERT for unknown
   columns and for missing NOT-NULL-without-default columns. Where live disagrees
   with Flyway, live wins.

## Known not-seeded (read-adjacent, deliberately out)

- `pos_people_contact_db.person` (identity moved there by #874/#875): the gate
  endpoints resolve technicians purely from the pos_workorder_db replicas, so the
  identity-owner rows are not needed for ground truth. If a future GT script wants
  them, add a sixth SQL file.
- `ext_people_staffing_assignment`, `ext_vehicle`, vehicles generally (workorders have
  `vehicle_id NULL`; the search enrichment skips null vehicles).
- `event_outbox` / `processed_events` — the seed bypasses Kafka by writing both sides
  of every replica pair with identical ids and totals.

### Retained verification artifacts (PR #1647 review finding 4)

- `seed/alpha-live-columns.2026-09-02.json` — the live information_schema inventory the INSERTs
  were validated against (snapshot; regenerate against the current environment before any future
  apply, e.g. per-DB:
  `SELECT column_name, is_nullable, data_type, column_default IS NOT NULL FROM information_schema.columns WHERE table_schema='public' AND table_name='<t>' ORDER BY ordinal_position`).
- `ground-truth/runs/2026-09-02-alpha-run.txt` — the live run EXPECTED.md was transcribed from.
