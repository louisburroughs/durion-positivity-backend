-- CAP-142 Story #60: Add composite index to support findByScheduledDateAndLocationId (SLA: P95<2s)
CREATE INDEX IF NOT EXISTS idx_workorder_scheduled_date_location_id
    ON workorder (scheduled_date, location_id);