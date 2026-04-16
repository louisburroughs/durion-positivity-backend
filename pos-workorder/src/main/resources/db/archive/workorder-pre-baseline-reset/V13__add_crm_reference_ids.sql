-- CAP-094 Story #93: Add CRM reference ID columns to estimate and workorder tables
-- NOT NULL constraints will be enforced at the service/validation layer and tightened post-GREEN

ALTER TABLE IF EXISTS estimate
    ADD COLUMN crm_party_id   VARCHAR(36),
    ADD COLUMN crm_vehicle_id VARCHAR(36),
    ADD COLUMN crm_contact_ids TEXT;

ALTER TABLE IF EXISTS workorder
    ADD COLUMN crm_party_id   VARCHAR(36),
    ADD COLUMN crm_vehicle_id VARCHAR(36),
    ADD COLUMN crm_contact_ids TEXT;
