-- #1621, ADR-0044 R3/R6: replica of pos-invoice's deposit / down-payment credit
-- draw-downs (payment.events.v1 payment.deposit-credit.applied), feeding the
-- collections-analytics `nonCashSettled` figure.
--
-- The parent DepositCredit is deliberately NOT replicated here — a windowed
-- draw-down sum over applied_at does not need the credit's own lifecycle, only the
-- individual application facts. application_id is generated locally (UUID v7): the
-- event itself carries no application id, only the (depositCreditId, invoiceId) pair
-- pos-invoice's applyAvailableCredits() applies at most once per pair.
CREATE TABLE ext_invoice_deposit_credit_application (
    application_id uuid NOT NULL,
    deposit_credit_id uuid NOT NULL,
    invoice_id uuid NOT NULL,
    amount_applied numeric(19, 4) NOT NULL,
    applied_at timestamp(6) with time zone NOT NULL,
    source_event_id uuid NOT NULL,
    CONSTRAINT ext_invoice_deposit_credit_application_pkey PRIMARY KEY (application_id),
    CONSTRAINT uk_ext_invoice_deposit_credit_application_pair UNIQUE (deposit_credit_id, invoice_id)
);

CREATE INDEX idx_ext_invoice_deposit_credit_application_applied_at
    ON ext_invoice_deposit_credit_application (applied_at);
