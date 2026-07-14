-- Ensure existing rows comply before adding NOT NULL constraints.
UPDATE product
SET status = 'ACTIVE'
WHERE status IS NULL;

UPDATE product
SET lifecycle_state = 'ACTIVE'
WHERE lifecycle_state IS NULL;

ALTER TABLE product
    ALTER COLUMN status SET DEFAULT 'ACTIVE',
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN lifecycle_state SET DEFAULT 'ACTIVE',
    ALTER COLUMN lifecycle_state SET NOT NULL;