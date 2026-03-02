ALTER TABLE workorder ADD COLUMN operational_context_version VARCHAR(255);
ALTER TABLE workorder ADD COLUMN work_started_at TIMESTAMP WITH TIME ZONE;