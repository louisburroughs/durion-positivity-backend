ALTER TABLE accounting_audit_log
    ALTER COLUMN entity_id TYPE UUID
    USING NULLIF(entity_id, '')::UUID;
