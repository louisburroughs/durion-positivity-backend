-- V1 created service without audit timestamps.
-- The repeatable seed scripts reference created_at/updated_at on this table.
ALTER TABLE service
    ADD COLUMN created_at  timestamp(6) with time zone,
    ADD COLUMN updated_at  timestamp(6) with time zone;

UPDATE service SET created_at = NOW(), updated_at = NOW();

ALTER TABLE service
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL;
