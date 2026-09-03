-- Ground truth for gate question Q20 (analytics-capability-plan.md §6):
--   "12-month business summary, 7 metrics, trend flags" — a monthly business summary for the
--   last twelve months across revenue, workorder completions, technician hours, collections,
--   A/R aging, vendor spend and A/P payments; the model flags trends in prose.
--
-- Serving endpoint: Wave 3 `businessSummary(months)` composition (plan §W3.2: parallel
--   fan-out over E1 totals, WO completions, E5 hours, E2 collections, aging batch, E8 vendor
--   spend, A/P payments). Budget (§6): 2. Tolerance (§2.1): exact currency/counts. Months
--   against EVAL_AS_OF 2026-09-01: 2025-09 .. 2026-08.
--
-- SEMANTICS — SPEC-AHEAD (composition not yet built; §7 fixture-drift rule applies). Each
--   section mirrors the shipped member endpoint it fans out to:
--   Section 1 (pos_invoice_db): monthly revenue = E1 grand total (FINALIZED/POSTED,
--     deposit-take excluded, created_at window).
--   Section 2 (pos_workorder_db): monthly completions + billed hours = E5 rules (genuine
--     completions only; stopped labor entries by start_time), totalled across technicians.
--   Section 3 (pos_accounting_db): monthly E2 invoiced/collected (finalized_at basis,
--     deposit-take excluded; movement-basis reversal netting), monthly E8 vendor paidAmount
--     (settled statuses by payment_date) — in this seed A/P payments and E8 vendor spend are
--     the SAME measure (every payment settles), so the seven-metric row carries it once with
--     both labels — and the aging-batch member: the endpoint's month-end totalOutstanding,
--     which for historical as-ofs is CURRENT-balance residue, not history (q14 DIVERGENCE;
--     the true balance trend is BLOCKED pending W3.1).
--   Trend FLAGS are prose (criterion: consistent with these series), not figures.
--
-- Usage: run via run_ground_truth.sh.
-- DB: pos_invoice_db
WITH months AS (
    SELECT generate_series(date '2025-09-01', date '2026-08-01', interval '1 month')::date AS month_start
)
SELECT m.month_start,
       COALESCE(SUM(i.total_amount), 0) AS revenue,
       COUNT(i.id)                      AS invoice_count
FROM months m
LEFT JOIN invoices i
  ON i.created_at >= (m.month_start::timestamp AT TIME ZONE 'UTC')
 AND i.created_at <  ((m.month_start + interval '1 month')::timestamp AT TIME ZONE 'UTC')
 AND i.customer_id IS NOT NULL
 AND i.status IN ('FINALIZED', 'POSTED')
 AND i.deposit_source_type IS NULL
GROUP BY m.month_start
ORDER BY m.month_start;

-- DB: pos_workorder_db
WITH months AS (
    SELECT generate_series(date '2025-09-01', date '2026-08-01', interval '1 month')::date AS month_start
),
completions AS (
    SELECT m.month_start, COUNT(*) AS completed_wo_count
    FROM months m
    JOIN work_order_state_transitions t
      ON t.transitioned_at >= (m.month_start::timestamp AT TIME ZONE 'UTC')
     AND t.transitioned_at <  ((m.month_start + interval '1 month')::timestamp AT TIME ZONE 'UTC')
    WHERE t.to_status = 'COMPLETED' AND t.from_status <> 'COMPLETED'
    GROUP BY m.month_start
),
hours AS (
    SELECT m.month_start, SUM(le.hours_worked) AS billed_hours
    FROM months m
    JOIN workorder_labor_entry le
      ON le.end_time IS NOT NULL
     AND le.start_time >= m.month_start::timestamp
     AND le.start_time <= (m.month_start + interval '1 month')::timestamp  -- inclusive (see q01)
    GROUP BY m.month_start
)
SELECT m.month_start,
       COALESCE(c.completed_wo_count, 0) AS completed_wo_count,
       COALESCE(h.billed_hours, 0)       AS billed_hours
FROM months m
LEFT JOIN completions c ON c.month_start = m.month_start
LEFT JOIN hours h       ON h.month_start = m.month_start
ORDER BY m.month_start;

-- DB: pos_accounting_db
WITH months AS (
    SELECT generate_series(date '2025-09-01', date '2026-08-01', interval '1 month')::date AS month_start
),
e2 AS (
    SELECT m.month_start,
           (SELECT COALESCE(SUM(i.total), 0) FROM ext_invoice i
             WHERE i.finalized_at >= (m.month_start::timestamp AT TIME ZONE 'UTC')
               AND i.finalized_at <  ((m.month_start + interval '1 month')::timestamp AT TIME ZONE 'UTC')
               AND i.deposit_source_type IS NULL) AS invoiced,
           (SELECT COALESCE(SUM(pa.applied_amount), 0) FROM payment_application pa
             WHERE pa.application_timestamp >= (m.month_start::timestamp AT TIME ZONE 'UTC')
               AND pa.application_timestamp <  ((m.month_start + interval '1 month')::timestamp AT TIME ZONE 'UTC'))
         - (SELECT COALESCE(SUM(r.amount), 0) FROM payment_application_reversal r
             WHERE r.reversed_at >= (m.month_start::timestamp AT TIME ZONE 'UTC')
               AND r.reversed_at <  ((m.month_start + interval '1 month')::timestamp AT TIME ZONE 'UTC')) AS collected,
           (SELECT COALESCE(SUM(p.gross_amount), 0) FROM ap_payment p
             WHERE p.status IN ('GATEWAY_SUCCEEDED', 'GL_POST_PENDING', 'GL_POSTED', 'GL_POST_FAILED')
               AND p.payment_date >= m.month_start::timestamp
               AND p.payment_date <  (m.month_start + interval '1 month')::timestamp) AS vendor_paid_and_ap_payments
    FROM months m
),
ar AS (   -- aging-batch member: endpoint totalOutstanding at each month-end (CURRENT balances; q14 caveat)
    SELECT m.month_start,
           (SELECT COALESCE(SUM(ob.open_balance), 0) FROM (
               SELECT COALESCE(i.total, 0)
                        - COALESCE((SELECT SUM(pa.applied_amount) FROM payment_application pa
                                    WHERE pa.invoice_id = i.invoice_id), 0)
                        + COALESCE((SELECT SUM(r.amount) FROM payment_application_reversal r
                                    JOIN payment_application pa2
                                      ON pa2.payment_application_id = r.original_payment_application_id
                                    WHERE pa2.invoice_id = i.invoice_id), 0)
                        - COALESCE((SELECT SUM(cm.credit_amount + cm.tax_amount_reversed) FROM credit_memo cm
                                    WHERE cm.original_invoice_id = i.invoice_id AND cm.status = 'POSTED'), 0)
                        - COALESCE((SELECT SUM(t.amount) FROM customer_credit_transaction t
                                    WHERE t.invoice_id = i.invoice_id
                                      AND t.transaction_type = 'APPLICATION'), 0)
                        -- Deposit-credit draw-downs (#1652) — mirrors InvoiceBalanceCalculator.balanceDue.
                        - COALESCE((SELECT SUM(d.amount_applied) FROM ext_invoice_deposit_credit_application d
                                    WHERE d.invoice_id = i.invoice_id), 0) AS open_balance,
                      CAST(COALESCE(i.invoice_created_at, i.finalized_at, i.updated_at)
                           AT TIME ZONE 'UTC' AS date) AS document_date
               FROM ext_invoice i
               WHERE i.status IN ('FINALIZED', 'POSTED')) ob
             WHERE ob.open_balance > 0
               AND ob.document_date <= (m.month_start + interval '1 month')::date - 1) AS endpoint_ar_total
    FROM months m
)
SELECT e2.month_start, e2.invoiced, e2.collected, e2.vendor_paid_and_ap_payments,
       ar.endpoint_ar_total
FROM e2 JOIN ar ON ar.month_start = e2.month_start
ORDER BY e2.month_start;
