-- Ground truth for gate question Q18 (analytics-capability-plan.md §6):
--   "Weekly cash in vs cash out for the last quarter — which weeks were negative?"
--
-- Written from the cash-basis semantics delivered by #1620/#1622 (plan §W2.5 D5–D6), NOT from the
-- old premise that E2's `collected` measures cash in. Q18 is a pure cash question, so both sides
-- must be genuine cash movements on one basis:
--   cash in  = received        — Σ receivable_payment.total_amount by cleared_at: cash actually
--                                taken in, whether or not applied to an invoice yet (#1622).
--   cash out = refunded        — Σ ext_invoice_payment_reversal.amount by reversed_at: completed
--                                customer refunds, REFUND reversals only (#1620)
--            + A/P settled     — Σ ap_payment.gross_amount by payment_date for the "cash already
--                                moved" statuses, matching E8 vendor-spend's paidAmount.
-- The chat answer composes E2 (A/R side) and E8 (A/P side) weekly — 26 calls under Wave 2, so Q18
-- is formally a Wave 3 gate (E2/E8 groupBy=week); this script is the specification of the
-- expected figures either way (plan §2.1 criterion 1: exact for currency).
--
-- Schema: pos-accounting ONLY. Four behaviours are easy to get wrong:
--   1. Do NOT use collected − refunded as "net cash": collected is A/R relief on a movement
--      basis, not cash in. Cash received but unapplied never enters collected, and a refunded
--      invoice payment normally produces BOTH a RefundRecord (→ refunded) and a
--      PaymentApplicationReversal (→ depresses collected), so that subtraction double-counts the
--      refund. The E2 report ships netCashCollected with exactly this caveat in its description;
--      the honest Q18 pair is received vs refunded (#1620/#1622).
--   2. refunded includes standalone refunds with no invoice (invoice_id IS NULL) — cash out is
--      uniform even when A/R treatment is not. VOID reversals never reach the replica at all: a
--      released authorization never captured cash. No status re-check across the module boundary
--      — pos-invoice only publishes completed refunds (ADR-0044 R1, #1620).
--   3. The A/P side counts payments whose status says cash moved (GATEWAY_SUCCEEDED,
--      GL_POST_PENDING, GL_POSTED, GL_POST_FAILED — GL posting state does not change whether
--      cash left), dated by payment_date, mirroring E8. ap_payment.payment_date is a naive
--      LocalDateTime; the seed must write it UTC-aligned so the weekly buckets match the
--      timestamptz sides.
--   4. Weeks are UTC calendar weeks, half-open [start, start + 7 days), matching the service's
--      LocalDate → UTC start/end-of-day conversion to within nanoseconds.
--
-- Usage:  psql -v last_week_end="'2026-06-28'" -f q18-weekly-cash-in-vs-out.sql <pos-accounting db>
--   Emits the 13 weeks (one quarter) ending at last_week_end, oldest first, with a negative-week
--   flag; the chat answer must name exactly the flagged weeks.

\if :{?last_week_end}
\else
\set last_week_end '''2026-06-28'''
\endif

WITH weeks AS (
    SELECT
        (CAST(:last_week_end AS date) - 6 - (w * 7))::timestamptz AT TIME ZONE 'UTC' AS week_start,
        (CAST(:last_week_end AS date) + 1 - (w * 7))::timestamptz AT TIME ZONE 'UTC' AS week_end,
        CAST(:last_week_end AS date) - 6 - (w * 7) AS week_start_date
    FROM generate_series(12, 0, -1) AS w
),
figures AS (
    SELECT
        wk.week_start_date,
        (SELECT COALESCE(SUM(rp.total_amount), 0)
           FROM receivable_payment rp
          WHERE rp.cleared_at >= wk.week_start AND rp.cleared_at < wk.week_end) AS received,
        (SELECT COALESCE(SUM(pr.amount), 0)
           FROM ext_invoice_payment_reversal pr
          WHERE pr.reversed_at >= wk.week_start AND pr.reversed_at < wk.week_end) AS refunded,
        (SELECT COALESCE(SUM(ap.gross_amount), 0)
           FROM ap_payment ap
          WHERE ap.status IN ('GATEWAY_SUCCEEDED', 'GL_POST_PENDING', 'GL_POSTED', 'GL_POST_FAILED')
            AND ap.payment_date >= wk.week_start AT TIME ZONE 'UTC'
            AND ap.payment_date <  wk.week_end   AT TIME ZONE 'UTC') AS ap_paid
    FROM weeks wk
)
SELECT
    week_start_date                          AS week_starting,
    received                                 AS cash_in,
    refunded + ap_paid                       AS cash_out,
    refunded,
    ap_paid,
    received - refunded - ap_paid            AS net_cash,
    (received - refunded - ap_paid) < 0      AS negative_week
FROM figures
ORDER BY week_starting;
