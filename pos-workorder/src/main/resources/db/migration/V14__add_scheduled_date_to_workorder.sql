-- CAP-142 Story #60: Add scheduled_date column for Daily Dispatch Board Dashboard
ALTER TABLE workorder
    ADD COLUMN scheduled_date DATE;
