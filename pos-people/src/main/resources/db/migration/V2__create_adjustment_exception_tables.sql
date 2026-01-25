-- Migration: create time_entry_adjustment and time_entry_exception tables
-- PostgreSQL dialect

CREATE TABLE IF NOT EXISTS time_entry_adjustment (
  adjustment_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  time_entry_id varchar(64) NOT NULL,
  reason_code varchar(200),
  notes text,
  proposed_start_at timestamptz,
  proposed_end_at timestamptz,
  minutes_delta integer,
  status varchar(50),
  created_by varchar(64),
  created_at timestamptz DEFAULT now(),
  decided_by varchar(64),
  decided_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_time_entry_adjustment_time_entry_id ON time_entry_adjustment(time_entry_id);

CREATE TABLE IF NOT EXISTS time_entry_exception (
  exception_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  employee_id varchar(64) NOT NULL,
  work_date date,
  exception_code varchar(200),
  severity varchar(50),
  status varchar(50),
  time_entry_id varchar(64),
  resolution_notes text,
  detected_at timestamptz DEFAULT now(),
  resolved_by varchar(64),
  resolved_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_time_entry_exception_employee_id ON time_entry_exception(employee_id);
CREATE INDEX IF NOT EXISTS idx_time_entry_exception_time_entry_id ON time_entry_exception(time_entry_id);
