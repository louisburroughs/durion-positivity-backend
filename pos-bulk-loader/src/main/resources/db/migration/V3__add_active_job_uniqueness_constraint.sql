-- Enforce at most one non-terminal job per operator
-- Terminal states: COMPLETED, CANCELLED, FAILED
CREATE UNIQUE INDEX idx_bulk_load_job_one_active_per_operator
    ON bulk_load_job (operator_id)
    WHERE status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED');
