-- Migration: create time_entry and time_entry_audit tables
-- Compatible with PostgreSQL

CREATE TABLE IF NOT EXISTS time_entry (
  time_entry_id varchar(64) PRIMARY KEY,
  person_id varchar(64) NOT NULL,
  timesheet_id varchar(64),
  work_date date,
  clock_in_time timestamptz,
  clock_out_time timestamptz,
  hours_worked numeric(10,2),
  break_minutes integer,
  overtime_hours numeric(10,2),
  time_entry_type varchar(100),
  status varchar(50),
  notes text,
  approved_by varchar(64),
  approved_at timestamptz,
  created_at timestamptz DEFAULT now(),
  updated_at timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_time_entry_person_id ON time_entry(person_id);
CREATE INDEX IF NOT EXISTS idx_time_entry_timesheet_id ON time_entry(timesheet_id);
CREATE INDEX IF NOT EXISTS idx_time_entry_status ON time_entry(status);

CREATE TABLE IF NOT EXISTS time_entry_audit (
  audit_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  time_entry_id varchar(64) NOT NULL,
  action varchar(100) NOT NULL,
  actor_id varchar(64),
  timestamp timestamptz NOT NULL DEFAULT now(),
  correlation_id varchar(100),
  details text
);

CREATE INDEX IF NOT EXISTS idx_time_entry_audit_time_entry_id ON time_entry_audit(time_entry_id);
CREATE INDEX IF NOT EXISTS idx_time_entry_audit_actor_id ON time_entry_audit(actor_id);
