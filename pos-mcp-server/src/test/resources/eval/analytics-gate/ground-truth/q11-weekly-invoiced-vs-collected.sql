-- Ground truth for gate question Q11 (analytics-capability-plan.md §6):
--   "Weekly invoiced vs collected for the last 12 weeks, and the rate."
--
-- Written from the money-measure semantics settled by #1605 (plan §W2.5 D5–D8) and delivered by
-- #1620/#1621/#1622 — NOT from the pre-#1605 premise that one number can serve both "how much of
-- what we billed got settled" and "how much cash came in". Q11 is the settlement question, so the
-- primary rate here is settlement_rate_pct (settled / invoiced); collection_rate_pct (cash-only)
-- is also emitted because the chat answer is expected to present both, labeled. The chat answer is
-- produced by looping E2 — GET /v1/accounting/analytics/collections?startDate=&endDate= — one call
-- per week (12 calls, at the §6 budget edge) until W3.1's groupBy=week lands; this script is the
-- specification of the expected figures (plan §2.1 criterion 1: exact for currency, ±0.5 % for
-- derived ratios).
--
-- Schema: pos-accounting ONLY (ext_invoice and the two #1620/#1621 replicas are event-fed; never
-- join across a database boundary). Definitions mirror AccountingAnalyticsServiceImpl
-- .getCollectionsAnalytics exactly. Five behaviours are easy to get wrong:
--   1. invoiced excludes deposit-take invoices (deposit_source_type IS NOT NULL) — they are
--      contract-liability documents, not sales (D8, #1623). Rows replicated before the V29
--      enrichment stay unmarked until their invoice next emits, so historical windows can be
--      inflated; the seed must emit post-enrichment events only.
--   2. collected is movement-basis A/R relief (D7): applications dated in the week minus
--      application reversals dated in the week. A January application reversed in March reduces
--      March; January is never restated. It is NOT cash in the till.
--   3. non_cash_settled sums BOTH sources together (D5, #1621): deposit-credit draw-downs
--      (ext_invoice_deposit_credit_application, replicated from pos-invoice) AND accounting's own
--      customer-credit APPLICATION transactions. One source alone is systematically short.
--   4. settled = collected + non_cash_settled; settlement_rate_pct = settled / invoiced. An
--      invoice settled entirely by deposit credit reaches 100 % here while collection_rate_pct
--      stays 0 — both are correct, they answer different questions.
--   5. Windows are UTC calendar dates: the service converts LocalDate at UTC start-of-day /
--      end-of-day (23:59:59.999999999), which the half-open [start, start + 7 days) predicate
--      below reproduces to within nanoseconds. Rates are NUMERIC(…, 2), HALF_UP, and NULL (not 0)
--      when invoiced = 0 for the week.
--
-- Usage:  psql -v last_week_end="'2026-06-28'" -f q11-weekly-invoiced-vs-collected.sql <pos-accounting db>
--   last_week_end = the last day (inclusive) of the most recent complete week; the script emits
--   that week and the 11 before it, oldest first.

\if :{?last_week_end}
\else
\set last_week_end '''2026-06-28'''
\endif

WITH weeks AS (
    SELECT
        (CAST(:last_week_end AS date) - 6 - (w * 7))::timestamptz AT TIME ZONE 'UTC' AS week_start,
        (CAST(:last_week_end AS date) + 1 - (w * 7))::timestamptz AT TIME ZONE 'UTC' AS week_end,
        CAST(:last_week_end AS date) - 6 - (w * 7) AS week_start_date
    FROM generate_series(11, 0, -1) AS w
),
figures AS (
    SELECT
        wk.week_start_date,
        (SELECT COALESCE(SUM(i.total), 0)
           FROM ext_invoice i
          WHERE i.finalized_at >= wk.week_start AND i.finalized_at < wk.week_end
            AND i.deposit_source_type IS NULL)                              AS invoiced,
        (SELECT COALESCE(SUM(pa.applied_amount), 0)
           FROM payment_application pa
          WHERE pa.application_timestamp >= wk.week_start
            AND pa.application_timestamp < wk.week_end)
      - (SELECT COALESCE(SUM(par.amount), 0)
           FROM payment_application_reversal par
          WHERE par.reversed_at >= wk.week_start AND par.reversed_at < wk.week_end) AS collected,
        (SELECT COALESCE(SUM(dca.amount_applied), 0)
           FROM ext_invoice_deposit_credit_application dca
          WHERE dca.applied_at >= wk.week_start AND dca.applied_at < wk.week_end)
      + (SELECT COALESCE(SUM(cct.amount), 0)
           FROM customer_credit_transaction cct
          WHERE cct.transaction_type = 'APPLICATION'
            AND cct.created_at >= wk.week_start AND cct.created_at < wk.week_end) AS non_cash_settled
    FROM weeks wk
)
SELECT
    week_start_date                                        AS week_starting,
    invoiced,
    collected,
    non_cash_settled,
    collected + non_cash_settled                           AS settled,
    CASE WHEN invoiced = 0 THEN NULL
         ELSE ROUND(collected * 100.0 / invoiced, 2) END   AS collection_rate_pct,
    CASE WHEN invoiced = 0 THEN NULL
         ELSE ROUND((collected + non_cash_settled) * 100.0 / invoiced, 2) END AS settlement_rate_pct
FROM figures
ORDER BY week_starting;
