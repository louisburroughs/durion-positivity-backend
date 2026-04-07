-- Add missing invoice module tables used by current entity mappings.
-- Keeps migration idempotent for environments where tables may already exist.

CREATE TABLE IF NOT EXISTS billing_rules (
    id UUID PRIMARY KEY,
    party_id VARCHAR(36) NOT NULL UNIQUE,
    purchase_order_required BOOLEAN NOT NULL DEFAULT FALSE,
    payment_terms_code VARCHAR(50) NOT NULL DEFAULT 'NET_30',
    invoice_delivery_method VARCHAR(20) NOT NULL DEFAULT 'EMAIL',
    invoice_grouping_strategy VARCHAR(30) NOT NULL DEFAULT 'PER_WORKORDER',
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(36) NOT NULL DEFAULT 'system'
);

CREATE INDEX IF NOT EXISTS idx_billing_rules_party_id ON billing_rules(party_id);

CREATE TABLE IF NOT EXISTS payment_intents (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    payment_flow VARCHAR(20) NOT NULL,
    payment_token VARCHAR(255) NOT NULL,
    authorized_amount NUMERIC(15, 2),
    captured_amount NUMERIC(15, 2),
    voided_remainder_amount NUMERIC(15, 2),
    gateway_provider VARCHAR(50),
    gateway_response TEXT,
    gateway_reference VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_payment_intents_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_payment_intents_invoice_id ON payment_intents(invoice_id);
CREATE INDEX IF NOT EXISTS idx_payment_intents_status ON payment_intents(status);

CREATE TABLE IF NOT EXISTS receipts (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL,
    payment_intent_id UUID NOT NULL,
    reference VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    template_id VARCHAR(128) NOT NULL,
    template_version VARCHAR(32) NOT NULL,
    cashier_id VARCHAR(128) NOT NULL,
    terminal_id VARCHAR(64) NOT NULL,
    reprint_count INTEGER NOT NULL DEFAULT 0,
    last_reprint_reason VARCHAR(256),
    last_reprinted_by VARCHAR(128),
    delivery_method VARCHAR(10),
    delivery_status VARCHAR(10),
    delivery_email_address VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_receipts_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE,
    CONSTRAINT fk_receipts_payment_intent FOREIGN KEY (payment_intent_id) REFERENCES payment_intents(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_receipts_invoice_id ON receipts(invoice_id);
CREATE INDEX IF NOT EXISTS idx_receipts_payment_intent_id ON receipts(payment_intent_id);

CREATE TABLE IF NOT EXISTS refund_records (
    id UUID PRIMARY KEY,
    payment_intent_id UUID NOT NULL,
    invoice_id UUID NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason VARCHAR(30) NOT NULL,
    notes VARCHAR(1024),
    gateway_reference VARCHAR(256),
    requested_by VARCHAR(128) NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_refund_records_payment_intent FOREIGN KEY (payment_intent_id) REFERENCES payment_intents(id) ON DELETE CASCADE,
    CONSTRAINT fk_refund_records_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_refund_records_invoice_id ON refund_records(invoice_id);
CREATE INDEX IF NOT EXISTS idx_refund_records_payment_intent_id ON refund_records(payment_intent_id);
