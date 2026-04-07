-- Phase 5.3 follow-up: close remaining customer precision-gap examples.

ALTER TABLE IF EXISTS commercial_party
    ADD COLUMN IF NOT EXISTS party_number VARCHAR(128),
    ADD COLUMN IF NOT EXISTS legal_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS party_type VARCHAR(64);

ALTER TABLE IF EXISTS processing_log
    ADD COLUMN IF NOT EXISTS status VARCHAR(64);

ALTER TABLE IF EXISTS contact
    ADD COLUMN IF NOT EXISTS active BOOLEAN;

ALTER TABLE IF EXISTS merge_audit
    ALTER COLUMN merge_audit_id SET NOT NULL,
    ALTER COLUMN survivor_party_id SET NOT NULL,
    ALTER COLUMN source_party_id SET NOT NULL,
    ALTER COLUMN merged_by_user_id SET NOT NULL,
    ALTER COLUMN merge_reason SET NOT NULL,
    ALTER COLUMN merged_at SET DEFAULT NOW(),
    ALTER COLUMN merged_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT NOW(),
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE IF EXISTS contact
    ALTER COLUMN active SET DEFAULT TRUE;
