ALTER TABLE timekeeping_entry
    ADD COLUMN IF NOT EXISTS correction_id UUID,
    ADD COLUMN IF NOT EXISTS correction_reason TEXT,
    ADD COLUMN IF NOT EXISTS original_source_session_id UUID;

CREATE INDEX idx_timekeeping_entry_correction_id
    ON timekeeping_entry(correction_id)
    WHERE correction_id IS NOT NULL;