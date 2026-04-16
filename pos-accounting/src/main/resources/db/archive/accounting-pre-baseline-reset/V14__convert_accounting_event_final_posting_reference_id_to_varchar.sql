ALTER TABLE accounting_event
    ALTER COLUMN final_posting_reference_id TYPE VARCHAR(100)
    USING final_posting_reference_id::TEXT;
