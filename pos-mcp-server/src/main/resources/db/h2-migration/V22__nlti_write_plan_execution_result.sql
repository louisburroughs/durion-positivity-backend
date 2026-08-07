-- Gate 6 (#1193): H2 dev/test variant of pg V30 -- execution outcome for idempotent re-confirm.
ALTER TABLE nlti_write_plan ADD COLUMN IF NOT EXISTS execution_result text;
