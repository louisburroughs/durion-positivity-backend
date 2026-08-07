-- Gate 6 (#1193): persist the execution outcome on the write plan so an idempotent re-confirm
-- returns the prior result without re-executing (G6.6/G6.7).
ALTER TABLE nlti_write_plan ADD COLUMN IF NOT EXISTS execution_result text;
