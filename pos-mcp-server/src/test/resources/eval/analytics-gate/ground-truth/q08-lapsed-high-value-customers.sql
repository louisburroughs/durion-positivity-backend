-- Ground truth for gate question Q8 (analytics-capability-plan.md §6; tool-selection fixture
-- q08-lapsed-high-value-customers):
--   "Which customers have not bought anything in the last 90 days but spent more than
--    $10,000 with us in the prior year?"
--
-- Serving endpoint: E1 — GET /v1/invoices/analytics/revenue-by-customer (pos-invoice):
--   one call for the prior-year window (revenue > 10k) and one for a recent window whose
--   lastInvoiceDate answers the 90-day test; the model does the set arithmetic. Budget (§6): 3.
--   Tolerance (§2.1): exact. Against EVAL_AS_OF 2026-09-01: prior year =
--   2024-09-01..2025-08-31 (the DATASET.md "prior year" column); no purchase in the last 90 days =
--   no qualifying invoice with created_at >= 2026-06-03 00:00Z (as-of minus 90 days).
--
-- SEMANTICS — E1 universe throughout (q07 header: FINALIZED/POSTED, deposit-take excluded,
--   window on created_at). A customer qualifies when:
--     prior-year revenue > 10000.00  AND  no qualifying invoice created in the last 90 days.
--   "Bought anything" is measured exactly as E1 measures revenue — a DRAFT or deposit-take
--   document in the window would not count as a purchase, mirroring the tool the model uses.
--   Designed answer: C4 Marcus Webb alone (prior year 10800.00, last invoice 2026-05-30 —
--   94 days before the as-of date).
--
-- Usage: psql -v as_of_date="'2026-09-01'" -f q08-... <pos_invoice_db>
-- DB: pos_invoice_db
\if :{?as_of_date}
\else
\set as_of_date '''2026-09-01'''
\endif
WITH params AS (
    SELECT CAST(:as_of_date AS date) AS as_of_date,
           date '2024-09-01' AS prior_start, date '2025-08-31' AS prior_end
),
e1_universe AS (
    SELECT i.customer_id, i.total_amount, i.created_at
    FROM invoices i
    WHERE i.customer_id IS NOT NULL
      AND i.status IN ('FINALIZED', 'POSTED')
      AND i.deposit_source_type IS NULL
),
prior_year AS (
    SELECT u.customer_id, SUM(u.total_amount) AS prior_year_revenue
    FROM e1_universe u CROSS JOIN params p
    WHERE u.created_at >= (p.prior_start::timestamp AT TIME ZONE 'UTC')
      AND u.created_at <  ((p.prior_end + 1)::timestamp AT TIME ZONE 'UTC')
    GROUP BY u.customer_id
),
last_activity AS (
    SELECT customer_id, MAX(created_at) AS last_invoice_at
    FROM e1_universe
    GROUP BY customer_id
)
SELECT
    py.customer_id,
    cp.display_name AS name,
    py.prior_year_revenue,
    la.last_invoice_at,
    (p.as_of_date - CAST(la.last_invoice_at AT TIME ZONE 'UTC' AS date)) AS days_since_last_invoice
FROM prior_year py
CROSS JOIN params p
LEFT JOIN last_activity la ON la.customer_id = py.customer_id
LEFT JOIN ext_customer_party cp ON cp.party_id::text = py.customer_id
WHERE py.prior_year_revenue > 10000.00
  AND (la.last_invoice_at IS NULL
       OR la.last_invoice_at < ((p.as_of_date - 90)::timestamp AT TIME ZONE 'UTC'))
ORDER BY py.prior_year_revenue DESC;
