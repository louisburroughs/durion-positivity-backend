CREATE TABLE invoices (
    id UUID PRIMARY KEY,
    invoice_number VARCHAR(64),
    workorder_id UUID NOT NULL,
    estimate_id UUID,
    approval_id UUID,
    customer_id VARCHAR(64),
    status VARCHAR(20) NOT NULL,
    subtotal NUMERIC(19,4) NOT NULL,
    tax_amount NUMERIC(19,4) NOT NULL,
    adjustments_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    total_amount NUMERIC(19,4) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    finalized_at TIMESTAMP,
    finalized_by VARCHAR(64),
    version INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX idx_invoices_invoice_number ON invoices(invoice_number);
CREATE UNIQUE INDEX idx_invoices_workorder_id ON invoices(workorder_id);

CREATE TABLE invoice_items (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL,
    description VARCHAR(512) NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    unit_price NUMERIC(19,4) NOT NULL,
    line_total NUMERIC(19,4) NOT NULL,
    workorder_item_id UUID,
    CONSTRAINT fk_invoice_items_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE
);

CREATE INDEX idx_invoice_items_invoice_id ON invoice_items(invoice_id);

CREATE TABLE invoice_adjustments (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL,
    type VARCHAR(20) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    reason VARCHAR(512) NOT NULL,
    authorized_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_invoice_adjustments_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE
);

CREATE INDEX idx_invoice_adjustments_invoice_id ON invoice_adjustments(invoice_id);
