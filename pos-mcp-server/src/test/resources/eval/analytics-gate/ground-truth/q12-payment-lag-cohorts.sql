-- Ground truth for gate question Q12 (analytics-capability-plan.md §6):
--   "Payment-lag cohorts, 6 months" — how quickly do invoices issued in the last six months
--   get paid (≤30 / 31–60 / 61–90 days / still unpaid)?
--
-- Serving endpoint: E3 — GET /v1/accounting/analytics/payment-lag-cohorts?issuedFrom=&issuedTo=
--   (pos-accounting; relocated from the plan's original pos-invoice slot by D3's replica set).
--   Budget (§6): 2. Tolerance (§2.1): exact. Window against EVAL_AS_OF 2026-09-01:
--   invoices FINALIZED 2026-03-01..2026-08-31 (the DATASET.md Q12 window).
--
-- SEMANTICS — mirrors AccountingAnalyticsServiceImpl.getPaymentLagCohorts exactly:
--   * Universe: ext_invoice rows with finalized_at in [issuedFrom 00:00Z,
--     issuedTo 23:59:59.999999Z]. No status filter and NO deposit-take exclusion (D8 covers
--     revenue-shaped measures only and was deliberately NOT extended to E3 — the deposit-take
--     document IS counted here; the seed's ≤30 cohort includes it by design).
--   * paid anchor: the FIRST payment_application whose invoice_balance_after = 0
--     (chronological); an invoice with no zero-balance application is 'unpaid'.
--   * lag = whole days between finalized_at and that application_timestamp
--     (ChronoUnit.DAYS = truncation, clamped at >= 0); <=30 / 31-60 / 61-90; a lag > 90
--     also lands in 'unpaid' (impl quirk: >90-day payers are reported as unpaid).
--   * amount = SUM(ext_invoice.total) per cohort; count = invoices per cohort.
--
-- DIVERGENCE:
--   * "unpaid" conflates never-paid with paid-after-90-days (no seed row exercises the
--     latter, but the rule is part of the spec).
--   * The C5 credit-memo invoice (2026-02) settles 250 cash + 50 POSTED credit memo, so no
--     application ever records balance_after = 0 and E3 would call it unpaid; it is
--     deliberately dated OUTSIDE this window (DATASET.md credit-memo section).
--
-- Usage: psql -v issued_from="'2026-03-01'" -v issued_to="'2026-08-31'" -f q12-... <pos_accounting_db>
-- DB: pos_accounting_db
\if :{?issued_from}
\else
\set issued_from '''2026-03-01'''
\endif
\if :{?issued_to}
\else
\set issued_to '''2026-08-31'''
\endif
WITH params AS (
    SELECT CAST(:issued_from AS date) AS issued_from, CAST(:issued_to AS date) AS issued_to
),
universe AS (
    SELECT i.invoice_id, i.total, i.finalized_at,
           (SELECT MIN(pa.application_timestamp) FROM payment_application pa
             WHERE pa.invoice_id = i.invoice_id AND pa.invoice_balance_after = 0) AS paid_at
    FROM ext_invoice i CROSS JOIN params p
    WHERE i.finalized_at >= (p.issued_from::timestamp AT TIME ZONE 'UTC')
      AND i.finalized_at <  ((p.issued_to + 1)::timestamp AT TIME ZONE 'UTC')
),
classified AS (
    SELECT *,
           CASE
             WHEN paid_at IS NULL THEN 'unpaid'
             ELSE CASE
               WHEN GREATEST(0, FLOOR(EXTRACT(EPOCH FROM (paid_at - finalized_at)) / 86400.0)) <= 30 THEN '<=30'
               WHEN FLOOR(EXTRACT(EPOCH FROM (paid_at - finalized_at)) / 86400.0) <= 60 THEN '31-60'
               WHEN FLOOR(EXTRACT(EPOCH FROM (paid_at - finalized_at)) / 86400.0) <= 90 THEN '61-90'
               ELSE 'unpaid'   -- impl quirk: >90-day lag reports as unpaid
             END
           END AS cohort
    FROM universe
)
SELECT c.cohort, COUNT(*) AS invoice_count, SUM(c.total) AS amount
FROM classified c
GROUP BY c.cohort
ORDER BY CASE c.cohort WHEN '<=30' THEN 1 WHEN '31-60' THEN 2 WHEN '61-90' THEN 3 ELSE 4 END;
