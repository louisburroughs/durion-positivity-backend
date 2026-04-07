-- Phase 5.3 precision parity tightening for workorder.

ALTER TABLE IF EXISTS approval_record
    ALTER COLUMN change_request_id SET NOT NULL,
    ALTER COLUMN workorder_id SET NOT NULL;

ALTER TABLE IF EXISTS estimate_item
    ALTER COLUMN estimate_id SET NOT NULL;

ALTER TABLE IF EXISTS estimate_snapshot
    ALTER COLUMN estimate_id SET NOT NULL;

ALTER TABLE IF EXISTS substitute_audit
    ALTER COLUMN link_id SET NOT NULL;

ALTER TABLE IF EXISTS technician_assignment
    ALTER COLUMN workorder_id SET NOT NULL;

ALTER TABLE IF EXISTS work_order_snapshots
    ALTER COLUMN workorder_id SET NOT NULL;

ALTER TABLE IF EXISTS work_order_state_transitions
    ALTER COLUMN workorder_id SET NOT NULL;

ALTER TABLE IF EXISTS work_session
    ALTER COLUMN work_order_id SET NOT NULL;

ALTER TABLE IF EXISTS workorder_labor_entry
    ALTER COLUMN workorder_id SET NOT NULL,
    ALTER COLUMN workorder_service_id SET NOT NULL;

ALTER TABLE IF EXISTS workorder_part_substitution
    ALTER COLUMN workorder_id SET NOT NULL,
    ALTER COLUMN workorder_line_item_id SET NOT NULL;

ALTER TABLE IF EXISTS travel_segment
    ALTER COLUMN work_order_id SET NOT NULL;

ALTER TABLE IF EXISTS travel_segment_adjustment
    ALTER COLUMN travel_segment_id SET NOT NULL;

ALTER TABLE IF EXISTS time_entry
    ALTER COLUMN work_order_id SET NOT NULL;

ALTER TABLE IF EXISTS time_entry_adjustment
    ALTER COLUMN time_entry_id SET NOT NULL;

ALTER TABLE IF EXISTS workorder_part_usage_event
    ALTER COLUMN workorder_part_id SET NOT NULL;

ALTER TABLE IF EXISTS workorder_part_adjustment_event
    ALTER COLUMN original_part_id SET NOT NULL,
    ALTER COLUMN workorder_id SET NOT NULL,
    ALTER COLUMN adjustment_type SET NOT NULL,
    ALTER COLUMN quantity_adjustment SET DEFAULT 0,
    ALTER COLUMN quantity_adjustment SET NOT NULL,
    ALTER COLUMN performed_by SET NOT NULL,
    ALTER COLUMN performed_at SET DEFAULT NOW(),
    ALTER COLUMN performed_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_approval_record_workorder_id
    ON approval_record (workorder_id);
CREATE INDEX IF NOT EXISTS idx_work_session_work_order_id
    ON work_session (work_order_id);
CREATE INDEX IF NOT EXISTS idx_workorder_labor_entry_workorder_id
    ON workorder_labor_entry (workorder_id);
CREATE INDEX IF NOT EXISTS idx_travel_segment_work_order_id
    ON travel_segment (work_order_id);
CREATE INDEX IF NOT EXISTS idx_time_entry_work_order_id
    ON time_entry (work_order_id);
