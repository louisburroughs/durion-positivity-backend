-- Phase 5.3 follow-up: close remaining workorder precision-gap examples.

ALTER TABLE IF EXISTS work_order_state_transitions
    ADD COLUMN IF NOT EXISTS from_status VARCHAR(64),
    ADD COLUMN IF NOT EXISTS to_status VARCHAR(64),
    ADD COLUMN IF NOT EXISTS transitioned_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS transitioned_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS reason TEXT,
    ADD COLUMN IF NOT EXISTS metadata TEXT,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

ALTER TABLE IF EXISTS work_order_state_transitions
    ALTER COLUMN workorder_id SET NOT NULL,
    ALTER COLUMN from_status SET NOT NULL,
    ALTER COLUMN to_status SET NOT NULL,
    ALTER COLUMN transitioned_at SET DEFAULT NOW(),
    ALTER COLUMN transitioned_at SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT NOW(),
    ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_work_order_state_transitions_workorder
    ON work_order_state_transitions (workorder_id, transitioned_at);
