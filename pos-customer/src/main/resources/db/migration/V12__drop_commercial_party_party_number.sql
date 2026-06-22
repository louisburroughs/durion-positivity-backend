-- =============================================================
-- V12__drop_commercial_party_party_number.sql
-- Remove the redundant commercial_party.party_number column.
--
-- Supersedes the in-place edit of the V1 baseline made in #720,
-- which mutated an already-applied migration and broke Flyway
-- validation on existing databases. The baseline is restored to
-- its original checksum; this forward migration drops the column
-- the proper way so existing environments self-heal on deploy and
-- fresh environments converge to the same schema.
-- =============================================================
SET TIME ZONE 'UTC';

ALTER TABLE commercial_party DROP CONSTRAINT IF EXISTS commercial_party_party_number_key;

ALTER TABLE commercial_party DROP COLUMN IF EXISTS party_number;
