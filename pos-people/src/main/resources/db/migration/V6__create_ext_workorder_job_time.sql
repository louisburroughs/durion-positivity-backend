-- ADR-0044 §6 (#875): read-only job-time replica fed by workorder.job_time.recorded.v1 facts
-- on workorder.events.v1. Replaces the synchronous /v1/workexec/job-time-totals lookup; the
-- attendance-discrepancy report buckets end_at_utc by the requested timezone locally.
CREATE TABLE ext_workorder_job_time (
    labor_entry_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    technician_id uuid NOT NULL,
    location_id uuid,
    end_at_utc timestamp(6) with time zone NOT NULL,
    minutes integer NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_workorder_job_time_pkey PRIMARY KEY (labor_entry_id)
);

CREATE INDEX idx_ext_job_time_end_at ON ext_workorder_job_time (end_at_utc);
CREATE INDEX idx_ext_job_time_technician ON ext_workorder_job_time (technician_id, end_at_utc);
