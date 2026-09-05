-- Ground truth for gate question Q22 (#1689 band 5, multi-stage aggregation):
--   "What share of our revenue came from our top five customers?"
--
-- Band 5 is percentage-of-total and top-N-by-derived-metric. q13 covers a Pareto over
-- ACCOUNTS RECEIVABLE (what is owed); this covers a share over REVENUE (what was earned).
-- They are different measures over different populations and a model that conflates them
-- gets a plausible wrong number, which is the failure worth catching.
--
-- SEMANTICS:
--   Revenue = net recognized revenue on FINALIZED invoices, after credit memos and refunds —
--   the ratified glossary definition of "largest customers" (BusinessGlossary, 2026-09-05.1).
--   DRAFT invoices are not revenue. The share is each customer's revenue over the total across
--   ALL customers, not over the top five — a denominator error here produces shares summing to
--   100%, which is the specific mistake this question exists to detect.
--
--   Window: trailing 12 complete calendar months ending with the last complete month, per the
--   glossary's default window for customer ranking. With EVAL_AS_OF 2026-09-01 that is
--   2025-09-01 .. 2026-08-31.

-- DB: pos_invoice_db
\if :{?as_of_date}
\else
\set as_of_date '2026-09-01'
\endif

WITH window_bounds AS (
    SELECT
        (date_trunc('month', DATE :'as_of_date') - INTERVAL '12 months')::date AS start_date,
        (date_trunc('month', DATE :'as_of_date') - INTERVAL '1 day')::date     AS end_date
),
per_customer AS (
    SELECT
        i.customer_id,
        sum(i.total_amount) AS revenue
    FROM invoice i, window_bounds w
    WHERE i.status = 'FINALIZED'
      AND i.invoice_date >= w.start_date
      AND i.invoice_date <= w.end_date
    GROUP BY i.customer_id
),
total AS (SELECT coalesce(sum(revenue), 0) AS all_revenue FROM per_customer)
SELECT
    p.customer_id,
    p.revenue,
    round(100.0 * p.revenue / nullif(t.all_revenue, 0), 2) AS pct_of_total_revenue,
    rank() OVER (ORDER BY p.revenue DESC)                  AS revenue_rank
FROM per_customer p, total t
ORDER BY p.revenue DESC
LIMIT 10;

-- The denominator, stated separately so the share can be cross-footed and a top-five-only
-- denominator is visibly distinguishable from the all-customer one.
WITH window_bounds AS (
    SELECT
        (date_trunc('month', DATE :'as_of_date') - INTERVAL '12 months')::date AS start_date,
        (date_trunc('month', DATE :'as_of_date') - INTERVAL '1 day')::date     AS end_date
)
SELECT
    (SELECT count(DISTINCT i.customer_id) FROM invoice i, window_bounds w
      WHERE i.status = 'FINALIZED' AND i.invoice_date >= w.start_date AND i.invoice_date <= w.end_date) AS customers,
    (SELECT count(*) FROM invoice i, window_bounds w
      WHERE i.status = 'FINALIZED' AND i.invoice_date >= w.start_date AND i.invoice_date <= w.end_date) AS finalized_invoices,
    (SELECT coalesce(sum(i.total_amount),0) FROM invoice i, window_bounds w
      WHERE i.status = 'FINALIZED' AND i.invoice_date >= w.start_date AND i.invoice_date <= w.end_date) AS total_revenue,
    (SELECT w.start_date FROM window_bounds w) AS window_start,
    (SELECT w.end_date   FROM window_bounds w) AS window_end;
