-- Give time_entry a writer (#1564).
--
-- An employee's time entry is produced by the clock surface: start, break, finish, submit.
-- Until now nothing inserted into time_entry at all, so the approval, adjustment, exception,
-- and payroll-export surfaces that read it could only ever answer "not found" or return an
-- empty result. Submitting a work session now writes the entry.
--
-- work_session_id carries provenance back to the session that produced the entry. It is
-- unique so a replayed submit cannot create a second entry for the same session; NULLs are
-- not compared in Postgres, so entries from other sources remain possible.
--
-- break_minutes is stored because the attendance window (attendance_start_at ..
-- attendance_end_at) is gross wall-clock time and includes breaks. The payroll export
-- subtracts it to report net worked hours.

ALTER TABLE time_entry ADD COLUMN work_session_id uuid;
ALTER TABLE time_entry ADD COLUMN break_minutes integer;

ALTER TABLE time_entry
    ADD CONSTRAINT uq_time_entry_work_session UNIQUE (work_session_id);
