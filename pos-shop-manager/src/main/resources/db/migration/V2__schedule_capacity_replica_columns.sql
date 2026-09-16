-- Replica columns the schedule-capacity read needs (#2023, #2021).
--
-- These arrived with the capacity work as edits to V1__baseline_shop_manager.sql. That baseline is
-- already applied on alpha, so changing its text changed its checksum, and Flyway validation —
-- which this module enforces for versioned migrations (see the ignore-migration-patterns note in
-- application.yml) — failed startup before the EntityManagerFactory could be built. The baseline is
-- back to the text alpha applied and the new columns live here instead. Read the two in order.
--
-- Every column is nullable and none is backfilled: they are replicated facts, not derived ones. A
-- null reads as "the owner has not published this yet", which is what is true until the next fact
-- for that aggregate arrives, and the listeners already distinguish an absent field from an
-- explicitly null one so a pre-change producer cannot overwrite a value a newer consumer replicated.

ALTER TABLE public.ext_bay ADD COLUMN bay_type character varying(64);

COMMENT ON COLUMN public.ext_bay.bay_type IS
    'Owner''s bay type discriminator, stored verbatim (#2023/#2021). Null until the owner publishes it.';

ALTER TABLE public.ext_location ADD COLUMN timezone character varying(64);
ALTER TABLE public.ext_location ADD COLUMN operating_hours text;
ALTER TABLE public.ext_location ADD COLUMN holiday_closures text;
ALTER TABLE public.ext_location ADD COLUMN check_in_buffer_minutes integer;
ALTER TABLE public.ext_location ADD COLUMN cleanup_buffer_minutes integer;

COMMENT ON COLUMN public.ext_location.timezone IS
    'IANA timezone id mirrored verbatim from the owner (#2023 F4); authoritative for GET /v1/schedules/capacity.';
COMMENT ON COLUMN public.ext_location.operating_hours IS
    'Weekly opening windows as the raw JSON array the fact carried (#2023). NULL means never configured; ''[]'' means configured as closed every day (DECISION-LOCATION-004) — the two are different facts and must not be collapsed.';
COMMENT ON COLUMN public.ext_location.holiday_closures IS
    'Dated closures as the raw JSON array the fact carried (#2023). Same NULL-versus-empty distinction as operating_hours (DECISION-LOCATION-005).';
COMMENT ON COLUMN public.ext_location.check_in_buffer_minutes IS
    'Minutes reserved before an appointment for check-in; NULL when the owner has not configured one (#2023).';
COMMENT ON COLUMN public.ext_location.cleanup_buffer_minutes IS
    'Minutes reserved after an appointment for cleanup; NULL when the owner has not configured one (#2023).';

ALTER TABLE public.ext_workorder ADD COLUMN work_started_at timestamp(6) with time zone;
ALTER TABLE public.ext_workorder ADD COLUMN completed_at timestamp(6) with time zone;
ALTER TABLE public.ext_workorder ADD COLUMN expected_end_at timestamp(6) with time zone;

COMMENT ON COLUMN public.ext_workorder.work_started_at IS
    'When work actually began (owner''s work_started_at); NULL while not started. The actual start, never a planned or scheduled time (#2021).';
COMMENT ON COLUMN public.ext_workorder.completed_at IS
    'When the workorder actually completed (owner''s completed_at); NULL while still open (#2021).';
COMMENT ON COLUMN public.ext_workorder.expected_end_at IS
    'Owner''s projection of when a running job will finish (#2021). The owner publishes NULL in every fact today by design; this module never fills it from another source.';
