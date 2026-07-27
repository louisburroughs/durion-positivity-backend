-- Story parity-C1 follow-on (issue #992): CustomerCredit lifecycle + GL liability relief.
--
-- #975 recognized the customer-credit liability on overpayment (Dr Undeposited Funds 1090 /
-- Cr Customer Credit Liability 2300) but nothing ever relieved it: `customer_credit` had no
-- consumption model, so the 2300 control account only ever grew and the #975 AC-7 invariant
-- (Σ open customer_credit == 2300 balance) held only while credits sat unused.
--
-- This migration adds the consumption model:
--   * customer_credit gains a lifecycle status and running applied/refunded totals, so the
--     "open" amount (amount − applied − refunded) is a first-class column-derived fact;
--   * customer_credit_transaction records each draw-down — an APPLICATION against an invoice
--     (Dr 2300 / Cr AR) or a REFUND of cash to the customer (Dr 2300 / Cr Undeposited Funds).
--
-- The posting-lifecycle columns (gl_journal_entry_id / gl_posted_at) live on the *transaction*
-- rather than on customer_credit, because one credit can be relieved many times and each relief
-- is a separately idempotent, separately traceable journal entry. `request_id` is the caller's
-- idempotency key and is globally unique, so a retried apply/refund is a no-op instead of a
-- double relief.
--
-- FLYWAY COLLISION NOTE: numbered V21 as the next contiguous version after V20. Unmerged
-- accounting/tax-plan branches may also claim a V2x on pos-accounting; if one merges first this
-- file must be renumbered on rebase.

ALTER TABLE customer_credit
    ADD COLUMN status varchar(20) NOT NULL DEFAULT 'AVAILABLE';
ALTER TABLE customer_credit
    ADD COLUMN applied_amount numeric(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE customer_credit
    ADD COLUMN refunded_amount numeric(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE customer_credit
    ADD COLUMN updated_at timestamp(6) with time zone;
-- Optimistic lock: concurrent apply/refund on the same credit must not both pass the
-- "enough remaining" check and over-draw the liability.
ALTER TABLE customer_credit
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

-- A credit can never be consumed beyond the amount that was issued.
ALTER TABLE customer_credit
    ADD CONSTRAINT chk_customer_credit_consumed_within_amount
        CHECK (applied_amount >= 0 AND refunded_amount >= 0 AND applied_amount + refunded_amount <= amount);

CREATE INDEX idx_customer_credit_status ON customer_credit (status);

CREATE TABLE customer_credit_transaction (
    credit_transaction_id uuid NOT NULL,
    credit_id uuid NOT NULL,
    -- APPLICATION (settles an invoice) | REFUND (cash back to the customer)
    transaction_type varchar(20) NOT NULL,
    -- Target invoice; required for APPLICATION, always null for REFUND.
    invoice_id uuid,
    amount numeric(19, 4) NOT NULL,
    currency varchar(3) NOT NULL,
    -- Caller-supplied idempotency key; also the basis of the GL posting idempotency key and the
    -- journal entry sourceEventId.
    request_id varchar(100) NOT NULL,
    gl_journal_entry_id uuid,
    gl_posted_at timestamp(6) with time zone,
    trace_id varchar(100),
    created_at timestamp(6) with time zone NOT NULL,
    created_by varchar(50),
    CONSTRAINT customer_credit_transaction_pkey PRIMARY KEY (credit_transaction_id),
    CONSTRAINT fk_customer_credit_transaction_credit FOREIGN KEY (credit_id)
        REFERENCES customer_credit (credit_id),
    CONSTRAINT uq_customer_credit_transaction_request UNIQUE (request_id),
    CONSTRAINT chk_customer_credit_transaction_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_customer_credit_transaction_type
        CHECK (transaction_type IN ('APPLICATION', 'REFUND')),
    -- An APPLICATION settles a specific invoice; a REFUND settles none.
    CONSTRAINT chk_customer_credit_transaction_invoice
        CHECK ((transaction_type = 'APPLICATION' AND invoice_id IS NOT NULL)
            OR (transaction_type = 'REFUND' AND invoice_id IS NULL))
);

CREATE INDEX idx_customer_credit_transaction_credit ON customer_credit_transaction (credit_id);
CREATE INDEX idx_customer_credit_transaction_invoice ON customer_credit_transaction (invoice_id);
