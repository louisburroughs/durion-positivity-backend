-- TODO(scaffold-remove-in-green): CAP-220-story-24
CREATE TABLE inventory_allocation_audit (
    allocation_audit_id UUID PRIMARY KEY,
    stock_item_id UUID NOT NULL,
    reason_code VARCHAR(50) NOT NULL,
    triggered_by VARCHAR(10) NOT NULL,
    trigger_reference_id VARCHAR(255),
    previous_state TEXT,
    new_state TEXT,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_allocation_audit_stock_item ON inventory_allocation_audit(stock_item_id);
CREATE INDEX idx_allocation_audit_reason_code ON inventory_allocation_audit(reason_code);
