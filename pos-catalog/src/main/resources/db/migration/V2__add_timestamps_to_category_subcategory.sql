-- V1 created category and subcategory without audit timestamps.
-- The repeatable seed scripts reference created_at/updated_at on both tables,
-- so those columns must exist before the seeds can run.
ALTER TABLE category
    ADD COLUMN created_at  timestamp(6) with time zone,
    ADD COLUMN updated_at  timestamp(6) with time zone;

UPDATE category SET created_at = NOW(), updated_at = NOW();

ALTER TABLE category
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE subcategory
    ADD COLUMN created_at  timestamp(6) with time zone,
    ADD COLUMN updated_at  timestamp(6) with time zone;

UPDATE subcategory SET created_at = NOW(), updated_at = NOW();

ALTER TABLE subcategory
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL;
