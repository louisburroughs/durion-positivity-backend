-- ADR-0018: add actor audit column to remaining AbstractParty concrete tables
-- V3 covered commercial_party; this migration covers all other concrete party tables.

ALTER TABLE person_party
    ADD COLUMN IF NOT EXISTS modified_by VARCHAR(255);
