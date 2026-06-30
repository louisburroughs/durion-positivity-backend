-- Gate 2C: persist operational workflow state on the NLTI session so tool selection can activate
-- non-IDLE tool sets (CREATING_PO, PROCESSING_RETURN, …) deterministically instead of guessing
-- from message text. Distinct from per-request conversation lifecycle state.
ALTER TABLE nlti_session ADD COLUMN IF NOT EXISTS workflow_state VARCHAR(32) NOT NULL DEFAULT 'IDLE';
