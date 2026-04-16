ALTER TABLE accounting_event
    ALTER COLUMN mapping_version_attempted TYPE VARCHAR(50)
    USING mapping_version_attempted::TEXT;
