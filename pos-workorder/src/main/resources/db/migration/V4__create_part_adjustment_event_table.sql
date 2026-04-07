-- CAP:005 Story #157 - Handle Part Substitutions and Returns
-- Create workorder_part_adjustment_event table and update idempotency_keys

-- Add part_adjustment_event_id to idempotency_keys for adjustment idempotency
ALTER TABLE IF EXISTS idempotency_keys ADD COLUMN IF NOT EXISTS part_adjustment_event_id UUID;

COMMENT ON COLUMN idempotency_keys.part_adjustment_event_id IS 'ID of the part adjustment event created with this key (nullable)';

-- Create workorder_part_adjustment_event table
CREATE TABLE IF NOT EXISTS workorder_part_adjustment_event (
    id UUID PRIMARY KEY,
    original_part_id UUID NOT NULL,
    workorder_id UUID NOT NULL,
    adjustment_type VARCHAR(30) NOT NULL,
    substituted_with_part_id UUID,
    quantity_adjustment DECIMAL(19, 4) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    performed_by UUID NOT NULL,
    performed_at TIMESTAMP NOT NULL,
    notes TEXT,
    CONSTRAINT fk_adjustment_original_part FOREIGN KEY (original_part_id) REFERENCES workorder_part(id) ON DELETE CASCADE,
    CONSTRAINT fk_adjustment_workorder FOREIGN KEY (workorder_id) REFERENCES workorder(id) ON DELETE CASCADE,
    CONSTRAINT chk_adjustment_type CHECK (adjustment_type IN ('SUBSTITUTION', 'ADDITIONAL_RETURN', 'CORRECTION'))
);

-- Indexes for efficient queries
CREATE INDEX IF NOT EXISTS idx_part_adjustment_original_part ON workorder_part_adjustment_event(original_part_id);
CREATE INDEX IF NOT EXISTS idx_part_adjustment_workorder ON workorder_part_adjustment_event(workorder_id);
CREATE INDEX IF NOT EXISTS idx_part_adjustment_performed_at ON workorder_part_adjustment_event(performed_at);

-- Add comments for documentation
COMMENT ON TABLE workorder_part_adjustment_event IS 'Immutable append-only log of part adjustments (substitutions, returns, corrections) on workorders';
COMMENT ON COLUMN workorder_part_adjustment_event.original_part_id IS 'ID of the part being adjusted';
COMMENT ON COLUMN workorder_part_adjustment_event.workorder_id IS 'Denormalized workorder ID for efficient cross-part queries';
COMMENT ON COLUMN workorder_part_adjustment_event.adjustment_type IS 'Type: SUBSTITUTION (replace with another part), ADDITIONAL_RETURN (return unused), CORRECTION (admin fix)';
COMMENT ON COLUMN workorder_part_adjustment_event.substituted_with_part_id IS 'For SUBSTITUTION: ID of the new part (nullable for other types)';
COMMENT ON COLUMN workorder_part_adjustment_event.quantity_adjustment IS 'Signed adjustment: negative for returns, positive for additions';
COMMENT ON COLUMN workorder_part_adjustment_event.reason IS 'Reason for adjustment (required for audit trail)';
COMMENT ON COLUMN workorder_part_adjustment_event.performed_by IS 'User who performed the adjustment';
COMMENT ON COLUMN workorder_part_adjustment_event.performed_at IS 'When the adjustment occurred';
COMMENT ON COLUMN workorder_part_adjustment_event.notes IS 'Optional additional context';
