-- CAP-142 Story #60: Add composite index to support findByScheduledDateAndLocationId (SLA: P95<2s)
DO $$
BEGIN
    IF to_regclass('public.workorder') IS NOT NULL THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_workorder_scheduled_date_location_id ON workorder (scheduled_date, location_id)';
    END IF;
END $$;
