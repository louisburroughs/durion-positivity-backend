-- Wave 5 (odoo parity #1085): deposit / down-payment credits.
-- Gate V2 verdict: InvoiceAdjustment cannot carry deposit provenance (single externalReference,
-- no remaining-balance, single-invoice, DRAFT-only), so deposits get a dedicated artifact with a
-- typed source, an original/remaining balance, and an application-audit trail.

CREATE TABLE deposit_credit (
  deposit_credit_id UUID PRIMARY KEY,
  version INTEGER NOT NULL DEFAULT 0,
  -- Typed provenance: which source document the deposit was taken against.
  source_type VARCHAR(16) NOT NULL,
  source_id UUID NOT NULL,
  -- The pos-order sale that took the deposit; unique so a replayed take is idempotent.
  order_id UUID NOT NULL,
  party_id VARCHAR(64),
  currency_code VARCHAR(3) NOT NULL DEFAULT 'USD',
  original_amount NUMERIC(19, 4) NOT NULL,
  remaining_balance NUMERIC(19, 4) NOT NULL,
  status VARCHAR(24) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_deposit_credit_order UNIQUE (order_id),
  CONSTRAINT chk_deposit_original_positive CHECK (original_amount > 0),
  CONSTRAINT chk_deposit_remaining_range CHECK (remaining_balance >= 0 AND remaining_balance <= original_amount)
);

CREATE INDEX ix_deposit_credit_source ON deposit_credit (source_type, source_id);
CREATE INDEX ix_deposit_credit_status ON deposit_credit (status);

-- Application-audit rows: one per draw-down of a credit against a settlement invoice. The full
-- provenance chain is deposit_credit (source) -> deposit_credit_application (invoice).
CREATE TABLE deposit_credit_application (
  application_id UUID PRIMARY KEY,
  deposit_credit_id UUID NOT NULL,
  invoice_id UUID NOT NULL,
  amount_applied NUMERIC(19, 4) NOT NULL,
  applied_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT fk_deposit_application_credit FOREIGN KEY (deposit_credit_id)
    REFERENCES deposit_credit (deposit_credit_id) ON DELETE CASCADE,
  -- A given credit applies to a given invoice at most once (idempotent auto-apply on invoice create).
  CONSTRAINT uq_deposit_application_credit_invoice UNIQUE (deposit_credit_id, invoice_id),
  CONSTRAINT chk_deposit_application_positive CHECK (amount_applied > 0)
);

CREATE INDEX ix_deposit_application_invoice ON deposit_credit_application (invoice_id);
