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
--
--   Source is ext_invoice in pos_accounting_db, the same replica q13 reads, with the same
--   status filter and the same party_id UUID guard — a first draft here queried a table named
--   `invoice` in pos_invoice_db, which does not exist. Document date follows q13's
--   receivableDocumentDate coalesce so the two questions bucket an invoice into the same month.

-- DB: pos_accounting_db
\if :{?as_of_date}
\else
\set as_of_date '2026-09-01'
\endif

WITH bounds AS (
    SELECT
        (date_trunc('month', DATE :'as_of_date') - INTERVAL '12 months')::date AS start_date,
        (date_trunc('month', DATE :'as_of_date') - INTERVAL '1 day')::date     AS end_date
),
revenue_invoices AS (
    SELECT
        CAST(i.party_id AS uuid) AS customer_id,
        COALESCE(i.total, 0)     AS total,
        CAST(COALESCE(i.invoice_created_at, i.finalized_at, i.updated_at)
             AT TIME ZONE 'UTC' AS date) AS document_date
    FROM ext_invoice i
    WHERE i.status IN ('FINALIZED', 'POSTED')
      AND i.party_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
),
in_window AS (
    SELECT r.customer_id, r.total
    FROM revenue_invoices r, bounds b
    WHERE r.document_date >= b.start_date AND r.document_date <= b.end_date
),
per_customer AS (
    SELECT customer_id, sum(total) AS revenue, count(*) AS invoices
    FROM in_window GROUP BY customer_id
),
total AS (SELECT COALESCE(sum(revenue), 0) AS all_revenue FROM per_customer)
SELECT
    p.customer_id,
    p.invoices,
    p.revenue,
    round(100.0 * p.revenue / nullif(t.all_revenue, 0), 2) AS pct_of_total_revenue,
    rank() OVER (ORDER BY p.revenue DESC)                  AS revenue_rank
FROM per_customer p, total t
ORDER BY p.revenue DESC
LIMIT 10;

-- The denominator, stated separately: a top-five-only denominator would make the shares sum to
-- 100%, which is the specific error this question exists to detect.
WITH bounds AS (
    SELECT
        (date_trunc('month', DATE :'as_of_date') - INTERVAL '12 months')::date AS start_date,
        (date_trunc('month', DATE :'as_of_date') - INTERVAL '1 day')::date     AS end_date
),
revenue_invoices AS (
    SELECT
        CAST(i.party_id AS uuid) AS customer_id,
        COALESCE(i.total, 0)     AS total,
        CAST(COALESCE(i.invoice_created_at, i.finalized_at, i.updated_at)
             AT TIME ZONE 'UTC' AS date) AS document_date
    FROM ext_invoice i
    WHERE i.status IN ('FINALIZED', 'POSTED')
      AND i.party_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
)
SELECT
    count(DISTINCT r.customer_id) AS customers,
    count(*)                      AS invoices,
    COALESCE(sum(r.total), 0)     AS total_revenue,
    (SELECT b.start_date FROM bounds b) AS window_start,
    (SELECT b.end_date   FROM bounds b) AS window_end
FROM revenue_invoices r, bounds b
WHERE r.document_date >= b.start_date AND r.document_date <= b.end_date;
