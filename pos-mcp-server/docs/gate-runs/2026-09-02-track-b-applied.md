# Track B applied — seeds live on alpha, ground truth measured — 2026-09-02

Plan: analytics-capability-plan.md §2.3 · Branch `claude/mcp-track-b`

## What happened

1. **Seed applied**: 2,257 inserts across 5 databases (`apply_seed.sh`, per-file transactions,
   marker-scoped deletes), pre/post row-count snapshots in the run log. First attempt failed on
   live-schema drift in pos_customer_db (`commercial_party.party_number` absent live;
   `person_party` names moved to people-contact) — transactionality held, nothing partial landed;
   a full both-direction validation of all 35 seeded tables against alpha's information_schema
   found no other drift, and the fix changed no numbers (four of five SQL files byte-identical).
2. **Ground truth executed on alpha**: all 26 sections of the 20-question suite (multi-database questions contribute one section per database), zero SQL errors.
3. **EXPECTED.md re-derived from the live run** under the accepted co-tenancy policy.

## Co-tenancy decision (user, 2026-09-02)

Alpha's invoice/A-R books carry pre-existing test data (37 unpaid invoices × 286.17 finalized
2026-08-23/24 by itest, 133 open co-tenant workorders, and relatives). Decision: **accept, do not
clean**. Expected values include co-tenant data by design; gate comparisons remain valid because
the chat path reads the same tables. Values are stable only while nothing else writes to these
tables — on drift, suspect environment churn first, seed regression second, and re-run
`run_ground_truth.sh` to refresh. The seed's own contribution stays re-derivable at any time via
the TRACKB markers.

## Question classification (EXPECTED.md is the reference sheet)

- ABSOLUTE: Q1–Q5, Q8, Q15–Q18 (Q1/Q2 carry a named caveat: co-tenant technician rows with zero
  revenue/completions exist; a "top by hours" misreading would name the wrong person).
- CO-TENANT-RELATIVE (seed contribution exact, book totals include co-tenant mass, every delta
  cross-foots to N × 286.17): Q7, Q9–Q14 partials, Q19, Q20.
- BLOCKED: Q6 (D2 — parts cost unseeded, pos-inventory); Q10 true past-due trend and Q14 true
  balances/DSO (W3.1 point-in-time reconstruction).

## Verification chain

Seed numbers were verified three independent ways before touching alpha (generator mirror-model
asserts; throwaway Postgres 16 run of the full suite; DATASET.md hand-derivation) and a fourth on
alpha (live run matches all three — zero discrepancies). Two genuine system findings from the
build are documented in DATASET.md: accounting's `ext_invoice.workorder_id NOT NULL` cannot
represent order-fronted invoices, and `InvoiceBalanceCalculator` ignores deposit draw-downs
(settlement invoice reports 1000.00 open, economically 500.00 — carried as a DIVERGENCE note).

## What this unblocks

Plan §2.1 criterion 1 (answer correctness) is measurable for the first time. Next: the #1601
chat-path runs — the four discovery-only questions (Q3/Q4/Q12/Q16) plus scoring facade-path
answers against EXPECTED.md — and the under-permissioned degradation run.
