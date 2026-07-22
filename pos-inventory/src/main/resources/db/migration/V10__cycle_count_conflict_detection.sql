-- Cycle-count conflict detection (odoo-parity I2, issue #1026).
--
-- 1. cycle_count_task.status gains the CONFLICT state: interfering stock
--    movements were detected between task creation and count submission (or
--    adjustment approval), so the expected-quantity snapshot is stale and an
--    explicit reviewer choice (recount, or approve with the variance
--    recomputed against current on-hand) is required.
ALTER TABLE cycle_count_task DROP CONSTRAINT cycle_count_task_status_check;

ALTER TABLE cycle_count_task ADD CONSTRAINT cycle_count_task_status_check CHECK (((status)::text = ANY ((ARRAY['ASSIGNED'::character varying, 'COUNTED_PENDING_REVIEW'::character varying, 'CONFLICT'::character varying, 'REQUIRES_INVESTIGATION'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[])));

-- 2. cycle_count_adjustment can now reference the cycle-count task it settles,
--    so approval can consult the task's conflict state and recompute the
--    variance against current on-hand. Nullable: adjustments may still be
--    created without a task reference.
ALTER TABLE cycle_count_adjustment ADD COLUMN task_id uuid;

ALTER TABLE cycle_count_adjustment ADD CONSTRAINT fk_cycle_count_adjustment_task FOREIGN KEY (task_id) REFERENCES cycle_count_task(task_id);

CREATE INDEX idx_cycle_count_adjustment_task_id ON cycle_count_adjustment (task_id);
