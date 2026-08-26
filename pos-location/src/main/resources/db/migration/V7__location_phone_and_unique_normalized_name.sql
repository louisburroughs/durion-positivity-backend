-- Adds the location phone number column (bulk ingest previously accepted a
-- phoneNumber and silently dropped it — the table had nowhere to put it) and
-- enforces location-name uniqueness at the database level.
--
-- LocationServiceImpl has always mapped violations of
-- uq_location_normalized_name to LOCATION_NAME_TAKEN (409), but the
-- constraint itself never existed: uniqueness was enforced only by an
-- application pre-check plus an in-process cache, so concurrent creates
-- (or two service instances) could persist duplicate names.

ALTER TABLE location ADD COLUMN phone_number varchar(50);

-- Suffix any already-persisted duplicate names (keeping the earliest row
-- untouched) so the unique constraint can be created on existing data.
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY normalized_name ORDER BY created_at, id) AS rn
    FROM location
    WHERE normalized_name IS NOT NULL
)
UPDATE location l
SET name            = l.name || ' (' || r.rn || ')',
    normalized_name = l.normalized_name || ' (' || r.rn || ')'
FROM ranked r
WHERE l.id = r.id
  AND r.rn > 1;

ALTER TABLE location ADD CONSTRAINT uq_location_normalized_name UNIQUE (normalized_name);
