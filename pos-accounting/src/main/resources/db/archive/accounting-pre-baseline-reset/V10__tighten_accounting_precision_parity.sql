-- Phase 5.3 precision parity tightening for accounting.

ALTER TABLE IF EXISTS accounting_event
    ALTER COLUMN event_id SET NOT NULL,
    ALTER COLUMN event_type SET NOT NULL,
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN sequence_number SET DEFAULT 0,
    ALTER COLUMN sequence_number SET NOT NULL,
    ALTER COLUMN attempt_count SET DEFAULT 0,
    ALTER COLUMN attempt_count SET NOT NULL,
    ALTER COLUMN version SET DEFAULT 0,
    ALTER COLUMN version SET NOT NULL;

ALTER TABLE IF EXISTS ap_payment
    ALTER COLUMN payment_id SET NOT NULL,
    ALTER COLUMN vendor_id SET NOT NULL,
    ALTER COLUMN payment_ref SET NOT NULL,
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN currency TYPE VARCHAR(3),
    ALTER COLUMN currency SET NOT NULL,
    ALTER COLUMN gross_amount SET DEFAULT 0,
    ALTER COLUMN gross_amount SET NOT NULL,
    ALTER COLUMN fee_amount SET DEFAULT 0,
    ALTER COLUMN fee_amount SET NOT NULL,
    ALTER COLUMN net_amount SET DEFAULT 0,
    ALTER COLUMN net_amount SET NOT NULL,
    ALTER COLUMN unapplied_amount SET DEFAULT 0,
    ALTER COLUMN unapplied_amount SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE IF EXISTS ap_payment_allocation
    ALTER COLUMN allocation_id SET NOT NULL,
    ALTER COLUMN payment_id SET NOT NULL,
    ALTER COLUMN vendor_bill_id SET NOT NULL,
    ALTER COLUMN applied_amount SET DEFAULT 0,
    ALTER COLUMN applied_amount SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE IF EXISTS journal_entry
    ALTER COLUMN journal_entry_id SET NOT NULL,
    ALTER COLUMN transaction_date SET NOT NULL,
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN entry_type SET NOT NULL,
    ALTER COLUMN total_debits SET DEFAULT 0,
    ALTER COLUMN total_debits SET NOT NULL,
    ALTER COLUMN total_credits SET DEFAULT 0,
    ALTER COLUMN total_credits SET NOT NULL,
    ALTER COLUMN is_balanced SET DEFAULT FALSE,
    ALTER COLUMN is_balanced SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN modified_at SET DEFAULT NOW(),
    ALTER COLUMN modified_at SET NOT NULL;

ALTER TABLE IF EXISTS journal_entry_line
    ALTER COLUMN line_id SET NOT NULL,
    ALTER COLUMN journal_entry_id SET NOT NULL,
    ALTER COLUMN line_number SET NOT NULL,
    ALTER COLUMN gl_account_id SET NOT NULL,
    ALTER COLUMN debit_amount SET DEFAULT 0,
    ALTER COLUMN debit_amount SET NOT NULL,
    ALTER COLUMN credit_amount SET DEFAULT 0,
    ALTER COLUMN credit_amount SET NOT NULL;

ALTER TABLE IF EXISTS payment_application
    ALTER COLUMN payment_application_id SET NOT NULL,
    ALTER COLUMN payment_id SET NOT NULL,
    ALTER COLUMN invoice_id SET NOT NULL,
    ALTER COLUMN customer_id SET NOT NULL,
    ALTER COLUMN currency TYPE VARCHAR(3),
    ALTER COLUMN currency SET NOT NULL,
    ALTER COLUMN application_request_id SET NOT NULL,
    ALTER COLUMN applied_amount SET DEFAULT 0,
    ALTER COLUMN applied_amount SET NOT NULL,
    ALTER COLUMN invoice_balance_before SET DEFAULT 0,
    ALTER COLUMN invoice_balance_before SET NOT NULL,
    ALTER COLUMN invoice_balance_after SET DEFAULT 0,
    ALTER COLUMN invoice_balance_after SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN application_timestamp SET DEFAULT NOW(),
    ALTER COLUMN application_timestamp SET NOT NULL;

ALTER TABLE IF EXISTS posting_rule_set
    ALTER COLUMN posting_rule_set_id SET NOT NULL,
    ALTER COLUMN name SET NOT NULL,
    ALTER COLUMN event_type SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN modified_at SET DEFAULT NOW(),
    ALTER COLUMN modified_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_accounting_event_event_id ON accounting_event (event_id);
CREATE INDEX IF NOT EXISTS idx_ap_payment_payment_id ON ap_payment (payment_id);
CREATE INDEX IF NOT EXISTS idx_ap_payment_allocation_payment_id ON ap_payment_allocation (payment_id);
CREATE INDEX IF NOT EXISTS idx_journal_entry_journal_entry_id ON journal_entry (journal_entry_id);
CREATE INDEX IF NOT EXISTS idx_journal_entry_line_journal_entry_id ON journal_entry_line (journal_entry_id);
CREATE INDEX IF NOT EXISTS idx_payment_application_payment_id ON payment_application (payment_id);
CREATE INDEX IF NOT EXISTS idx_posting_rule_set_name_event_type ON posting_rule_set (name, event_type);
