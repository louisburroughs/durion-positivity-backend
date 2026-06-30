-- Gate 2C: persist operational workflow state on the NLTI session (H2 dev/test variant of pg V20).
ALTER TABLE nlti_session ADD COLUMN IF NOT EXISTS workflow_state VARCHAR(32) NOT NULL DEFAULT 'IDLE';
