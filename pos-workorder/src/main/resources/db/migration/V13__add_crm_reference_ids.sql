-- CAP-094 Story #93: Add CRM reference ID columns to estimate and workorder tables

ALTER TABLE estimate
    ADD COLUMN crm_party_id   VARCHAR(36) NOT NULL DEFAULT '',
    ADD COLUMN crm_vehicle_id VARCHAR(36) NOT NULL DEFAULT '',
    ADD COLUMN crm_contact_ids TEXT        NOT NULL DEFAULT '[]';

ALTER TABLE workorder
    ADD COLUMN crm_party_id   VARCHAR(36) NOT NULL DEFAULT '',
    ADD COLUMN crm_vehicle_id VARCHAR(36) NOT NULL DEFAULT '',
    ADD COLUMN crm_contact_ids TEXT        NOT NULL DEFAULT '[]';
