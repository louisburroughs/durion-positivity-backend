-- ADR-0044 §6 Phase 3.2 (#875): identity ownership moved to pos-people-contact (#874).
-- CUTOVER PREREQUISITE (deployed environments): person, person_contact_point, and
-- user_person_links rows must already be copied into the pos-people-contact schema
-- (see the #874/#875 cutover runbook) before this migration runs — it drops the data.

-- HR rows keep person_id as a plain UUID reference into the people-contact domain;
-- cross-service foreign keys are not allowed (platform rule), so the constraints go.
ALTER TABLE employee DROP CONSTRAINT IF EXISTS fk_employee_person;
ALTER TABLE employee_offboarding_retry_queue DROP CONSTRAINT IF EXISTS fk_employee_offboarding_person;
ALTER TABLE work_session DROP CONSTRAINT IF EXISTS fk_work_session_person;

DROP TABLE IF EXISTS user_person_links;
DROP TABLE IF EXISTS person_contact_point;
DROP TABLE IF EXISTS person;
