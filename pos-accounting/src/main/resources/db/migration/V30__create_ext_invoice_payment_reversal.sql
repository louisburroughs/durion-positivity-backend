-- #1620, ADR-0044 R3/R6: read-only replica of pos-invoice's completed-refund facts
-- (payment.events.v1 payment.payment.reversed with reversalType = REFUND). Only
-- SettlementEventsListener writes this table.
--
-- VOID reversals are deliberately NOT stored here: a VOID releases an authorization
-- that never captured funds, so it never produced a PaymentApplication and removes no
-- collected cash — recording it as a "refund" would subtract money that was never
-- added. This replica feeds the collections-analytics `refunded` figure, which is
-- movement-basis over reversed_at (#1620), so only genuine cash-out events belong here.
CREATE TABLE ext_invoice_payment_reversal (
    refund_id uuid NOT NULL,
    payment_intent_id uuid,
    invoice_id uuid,
    party_id character varying(64),
    amount numeric(19, 4) NOT NULL,
    currency_code character varying(3) NOT NULL,
    reversal_type character varying(16) NOT NULL,
    reversed_at timestamp(6) with time zone NOT NULL,
    source_event_id uuid NOT NULL,
    CONSTRAINT ext_invoice_payment_reversal_pkey PRIMARY KEY (refund_id)
);

CREATE INDEX idx_ext_invoice_payment_reversal_reversed_at ON ext_invoice_payment_reversal (reversed_at);
