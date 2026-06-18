-- Work-session submission (issue #687): persist submitted totals + timestamp.
ALTER TABLE work_session ADD COLUMN billable_minutes integer;
ALTER TABLE work_session ADD COLUMN break_minutes integer;
ALTER TABLE work_session ADD COLUMN submitted_at timestamp(6) with time zone;
