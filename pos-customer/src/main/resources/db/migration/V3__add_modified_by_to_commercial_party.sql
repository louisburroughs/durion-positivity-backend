-- V3: Add modified_by actor audit column to commercial_party for ADR-0018 compliance
ALTER TABLE commercial_party
    ADD COLUMN IF NOT EXISTS modified_by VARCHAR(255);
