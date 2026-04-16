-- Create workorder_labor_entry table for tracking labor performed on workorders
-- CAP:005 Story #159 - Record Labor Performed

CREATE TABLE workorder_labor_entry (
    id UUID PRIMARY KEY,
    workorder_id UUID NOT NULL,
    workorder_service_id UUID NOT NULL,
    technician_id UUID NOT NULL,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    hours_worked DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    notes TEXT,
    adjustment_reason TEXT,
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_labor_workorder FOREIGN KEY (workorder_id) REFERENCES workorder(id) ON DELETE CASCADE
);

-- Indexes for efficient queries
CREATE INDEX idx_labor_workorder_id ON workorder_labor_entry(workorder_id);
CREATE INDEX idx_labor_service_id ON workorder_labor_entry(workorder_service_id);
CREATE INDEX idx_labor_start_time ON workorder_labor_entry(start_time);

-- Add comments for documentation
COMMENT ON TABLE workorder_labor_entry IS 'Tracks labor sessions performed on workorder service items';
COMMENT ON COLUMN workorder_labor_entry.workorder_id IS 'ID of the workorder this labor belongs to';
COMMENT ON COLUMN workorder_labor_entry.workorder_service_id IS 'ID of the service line item this labor is for';
COMMENT ON COLUMN workorder_labor_entry.technician_id IS 'ID of the technician who performed the work';
COMMENT ON COLUMN workorder_labor_entry.start_time IS 'When the labor session started';
COMMENT ON COLUMN workorder_labor_entry.end_time IS 'When the labor session ended (null for active sessions)';
COMMENT ON COLUMN workorder_labor_entry.hours_worked IS 'Hours worked (calculated or manually adjusted)';
COMMENT ON COLUMN workorder_labor_entry.notes IS 'Session notes';
COMMENT ON COLUMN workorder_labor_entry.adjustment_reason IS 'Reason for manual adjustment (if applicable)';
COMMENT ON COLUMN workorder_labor_entry.created_by IS 'User who created this entry';
COMMENT ON COLUMN workorder_labor_entry.created_at IS 'When this entry was created';
