-- Phase 5.3 follow-up: close remaining catalog precision-gap examples.

ALTER TABLE IF EXISTS approval_request
    ADD COLUMN IF NOT EXISTS override_id UUID,
    ADD COLUMN IF NOT EXISTS status VARCHAR(64),
    ADD COLUMN IF NOT EXISTS assigned_approver_id UUID,
    ADD COLUMN IF NOT EXISTS assignment_strategy VARCHAR(64),
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ;

ALTER TABLE IF EXISTS product_replacement
    ALTER COLUMN original_product_id SET NOT NULL,
    ALTER COLUMN replacement_product_id SET NOT NULL;

ALTER TABLE IF EXISTS item_cost_audit
    ALTER COLUMN item_id SET NOT NULL,
    ALTER COLUMN audit_timestamp SET NOT NULL,
    ALTER COLUMN cost_type_changed SET NOT NULL,
    ALTER COLUMN change_source_type SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_approval_request_status_assignee
    ON approval_request (status, assigned_approver_id);
