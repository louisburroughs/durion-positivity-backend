ALTER TABLE IF EXISTS estimate ADD COLUMN IF NOT EXISTS appointment_id UUID;
DO $$
BEGIN
    IF to_regclass('public.estimate') IS NOT NULL THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_estimate_appointment_id ON estimate(appointment_id)';
    END IF;
END $$;
