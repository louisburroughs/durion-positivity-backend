-- V15: Add source linking and assignment status columns to the appointment table.
-- CAP-249 Story #12: supports creating appointments from estimates and work orders.
-- source_type: ESTIMATE or WORK_ORDER (null = directly booked appointment)
-- source_id: external ID of the originating estimate or work order
-- assignment_status: AWAITING_SKILL_FULFILLMENT when no matching mechanic skills at creation

ALTER TABLE appointment
    ADD COLUMN IF NOT EXISTS source_type      VARCHAR(32),
    ADD COLUMN IF NOT EXISTS source_id        VARCHAR(128),
    ADD COLUMN IF NOT EXISTS assignment_status VARCHAR(64);
