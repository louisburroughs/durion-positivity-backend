-- Give time_entry the submitted_at the approvals surface reads (#1573).
--
-- CAP_139.130 requires submittedAtUtc on every entry it lists, and the approvals queue orders
-- by it: a supervisor works the oldest submission first. The column did not exist, so the
-- only submission timestamp available was work_session.submitted_at, one join away and absent
-- for entries from any other producer.
--
-- Existing rows are backfilled from the session that produced them. Entries with no session
-- (none exist today, but the column is nullable by design) keep a null submitted_at, which is
-- how a DRAFT entry is represented: nothing has been submitted yet.
--
-- The partial index serves the approvals queue query, which always filters on status and
-- orders within a single day of attendance_start_at.

ALTER TABLE time_entry ADD COLUMN submitted_at timestamp(6) with time zone;

UPDATE time_entry te
SET submitted_at = ws.submitted_at
FROM work_session ws
WHERE te.work_session_id = ws.session_id
  AND ws.submitted_at IS NOT NULL;

CREATE INDEX idx_time_entry_status_start ON time_entry (status, attendance_start_at);
