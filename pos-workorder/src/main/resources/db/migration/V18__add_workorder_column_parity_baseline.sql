-- Phase 5.2 bootstrap: add explicit entity-mapped columns for workorder parity tables.

ALTER TABLE IF EXISTS approval_record
    ADD COLUMN IF NOT EXISTS change_request_id UUID,
    ADD COLUMN IF NOT EXISTS workorder_id UUID;

ALTER TABLE IF EXISTS estimate
    ADD COLUMN IF NOT EXISTS approval_configuration_id UUID;

ALTER TABLE IF EXISTS estimate_item
    ADD COLUMN IF NOT EXISTS estimate_id UUID;

ALTER TABLE IF EXISTS estimate_snapshot
    ADD COLUMN IF NOT EXISTS estimate_id UUID;

ALTER TABLE IF EXISTS substitute_audit
    ADD COLUMN IF NOT EXISTS link_id UUID;

ALTER TABLE IF EXISTS technician_assignment
    ADD COLUMN IF NOT EXISTS workorder_id UUID;

ALTER TABLE IF EXISTS work_order_snapshots
    ADD COLUMN IF NOT EXISTS workorder_id UUID;

ALTER TABLE IF EXISTS work_order_state_transitions
    ADD COLUMN IF NOT EXISTS workorder_id UUID;

ALTER TABLE IF EXISTS work_session
    ADD COLUMN IF NOT EXISTS work_order_id UUID;

ALTER TABLE IF EXISTS workorder_labor_entry
    ADD COLUMN IF NOT EXISTS workorder_id UUID,
    ADD COLUMN IF NOT EXISTS workorder_service_id UUID;

ALTER TABLE IF EXISTS workorder_part_substitution
    ADD COLUMN IF NOT EXISTS workorder_id UUID,
    ADD COLUMN IF NOT EXISTS workorder_line_item_id UUID;

ALTER TABLE IF EXISTS travel_segment
    ADD COLUMN IF NOT EXISTS work_order_id UUID;

ALTER TABLE IF EXISTS travel_segment_adjustment
    ADD COLUMN IF NOT EXISTS travel_segment_id UUID;

ALTER TABLE IF EXISTS time_entry
    ADD COLUMN IF NOT EXISTS work_order_id UUID;

ALTER TABLE IF EXISTS time_entry_adjustment
    ADD COLUMN IF NOT EXISTS time_entry_id UUID;

ALTER TABLE IF EXISTS workorder_part_usage_event
    ADD COLUMN IF NOT EXISTS workorder_part_id UUID;

ALTER TABLE IF EXISTS workorder_part_adjustment_event
    ADD COLUMN IF NOT EXISTS original_part_id UUID,
    ADD COLUMN IF NOT EXISTS workorder_id UUID,
    ADD COLUMN IF NOT EXISTS adjustment_type VARCHAR(30),
    ADD COLUMN IF NOT EXISTS substituted_with_part_id UUID,
    ADD COLUMN IF NOT EXISTS quantity_adjustment NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS performed_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS performed_at TIMESTAMPTZ;
