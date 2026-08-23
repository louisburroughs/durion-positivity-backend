-- #1479: carry the demand line onto the pick-task replica.
--
-- Pick tasks generated for a promoted workorder name the workorder part they fulfil. Without that
-- link a consumption fact can update the pick task's consumed quantity but cannot find the part
-- line it belongs to, which is why workorder_part.quantity_consumed never moved for anything
-- picked and consumed through the pick flow.
--
-- Nullable and backfill-free on purpose: rows replicated before schema v2 of
-- inventory.pick-task.updated carry no demand line, and a pick task generated from a source with
-- no demand line never will.
ALTER TABLE ext_pick_task ADD COLUMN workorder_line_id uuid;

CREATE INDEX idx_ext_pick_task_workorder_line ON ext_pick_task (workorder_line_id);
