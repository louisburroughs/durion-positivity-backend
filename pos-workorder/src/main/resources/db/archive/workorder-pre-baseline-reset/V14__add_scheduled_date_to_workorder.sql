-- CAP-142 Story #60: Add scheduled_date column for Daily Dispatch Board Dashboard
ALTER TABLE IF EXISTS workorder
    ADD COLUMN IF NOT EXISTS scheduled_date DATE;
