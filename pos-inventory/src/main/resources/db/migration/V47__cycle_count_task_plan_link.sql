-- Plan → task generation: tasks created from a cycle count plan carry the
-- originating plan_id, so generation is idempotent per (plan, bin, SKU) and a
-- plan's tasks can be listed. plan_id stays NULL for tasks created outside a
-- plan (direct seeding, tests), and the unique guard ignores those rows
-- because NULLs are distinct.
ALTER TABLE cycle_count_task ADD COLUMN plan_id uuid;

ALTER TABLE cycle_count_task
    ADD CONSTRAINT fk_cycle_count_task_plan FOREIGN KEY (plan_id) REFERENCES cycle_count_plan (plan_id);

CREATE INDEX idx_cycle_count_task_plan ON cycle_count_task (plan_id);

-- Hard exactly-once guard mirroring cycle_count_plan_schedule_due_key: one
-- task per (plan, bin, SKU) regardless of application-level existence checks.
ALTER TABLE cycle_count_task
    ADD CONSTRAINT cycle_count_task_plan_bin_sku_key UNIQUE (plan_id, bin_location, item_sku);
