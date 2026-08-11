-- #1175: promote the service interval out of the unstructured JSONB preferences blob into a
-- structured, validated column so pos-vehicle-inventory can emit it as a
-- vehicle.care-preference.updated fact for CRM service-due consumers. Null means "no
-- per-vehicle override" — consumers fall back to their module-wide default interval.
ALTER TABLE vehicle_care_preferences
    ADD COLUMN service_interval_months integer;

ALTER TABLE vehicle_care_preferences
    ADD CONSTRAINT vehicle_care_preferences_service_interval_months_chk
        CHECK (service_interval_months IS NULL OR service_interval_months BETWEEN 1 AND 120);

-- Backfill from the legacy JSONB key where a row already carries a plausible integer value,
-- then strip the promoted key so the blob and the structured column cannot drift apart.
-- Unparseable/out-of-range values are left in the blob untouched (nothing ever read them).
UPDATE vehicle_care_preferences
SET service_interval_months = (preferences ->> 'serviceIntervalMonths')::integer,
    preferences             = preferences - 'serviceIntervalMonths'
WHERE preferences ? 'serviceIntervalMonths'
  AND preferences ->> 'serviceIntervalMonths' ~ '^[0-9]{1,3}$'
  AND (preferences ->> 'serviceIntervalMonths')::integer BETWEEN 1 AND 120;
