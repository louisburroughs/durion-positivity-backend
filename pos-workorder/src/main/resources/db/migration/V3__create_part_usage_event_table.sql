-- CAP:005 Story #158 - Parts Usage Tracking
-- Create workorder_part_usage_event table and update related tables

-- Add part_usage_event_id to idempotency_keys for parts usage idempotency
ALTER TABLE idempotency_keys ADD COLUMN part_usage_event_id UUID;

COMMENT ON COLUMN idempotency_keys.part_usage_event_id IS 'ID of the part usage event created with this key (nullable)';

-- Add usage tracking fields to workorder_part
ALTER TABLE workorder_part ADD COLUMN quantity_issued DECIMAL(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE workorder_part ADD COLUMN quantity_consumed DECIMAL(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE workorder_part ADD COLUMN quantity_returned DECIMAL(19, 4) NOT NULL DEFAULT 0;

COMMENT ON COLUMN workorder_part.quantity_issued IS 'Total quantity issued to workorder (sum of ISSUE events)';
COMMENT ON COLUMN workorder_part.quantity_consumed IS 'Total quantity consumed on workorder (sum of CONSUME events)';
COMMENT ON COLUMN workorder_part.quantity_returned IS 'Total quantity returned to inventory (sum of RETURN events)';

-- Create workorder_part_usage_event table
CREATE TABLE workorder_part_usage_event (
    id UUID PRIMARY KEY,
    workorder_part_id UUID NOT NULL,
    workorder_id UUID NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    quantity DECIMAL(19, 4) NOT NULL,
    performed_by UUID NOT NULL,
    performed_at TIMESTAMP NOT NULL,
    notes TEXT,
    CONSTRAINT fk_usage_event_part FOREIGN KEY (workorder_part_id) REFERENCES workorder_part(id) ON DELETE CASCADE,
    CONSTRAINT fk_usage_event_workorder FOREIGN KEY (workorder_id) REFERENCES workorder(id) ON DELETE CASCADE,
    CONSTRAINT chk_event_type CHECK (event_type IN ('ISSUE', 'CONSUME', 'RETURN')),
    CONSTRAINT chk_positive_quantity CHECK (quantity > 0)
);

-- Indexes for efficient queries
CREATE INDEX idx_workorder_part_usage_part_id ON workorder_part_usage_event(workorder_part_id);
CREATE INDEX idx_workorder_part_usage_workorder_id ON workorder_part_usage_event(workorder_id);
CREATE INDEX idx_workorder_part_usage_performed_at ON workorder_part_usage_event(performed_at);

-- Add comments for documentation
COMMENT ON TABLE workorder_part_usage_event IS 'Immutable append-only log of parts usage events (ISSUE, CONSUME, RETURN) on workorders';
COMMENT ON COLUMN workorder_part_usage_event.workorder_part_id IS 'ID of the workorder part line item';
COMMENT ON COLUMN workorder_part_usage_event.workorder_id IS 'Denormalized workorder ID for efficient cross-part queries';
COMMENT ON COLUMN workorder_part_usage_event.event_type IS 'Event type: ISSUE (reserved for use), CONSUME (actually used), or RETURN (returned to inventory)';
COMMENT ON COLUMN workorder_part_usage_event.quantity IS 'Quantity for this event (always positive)';
COMMENT ON COLUMN workorder_part_usage_event.performed_by IS 'User who performed/recorded this event';
COMMENT ON COLUMN workorder_part_usage_event.performed_at IS 'When the event occurred';
COMMENT ON COLUMN workorder_part_usage_event.notes IS 'Optional notes about this event';
