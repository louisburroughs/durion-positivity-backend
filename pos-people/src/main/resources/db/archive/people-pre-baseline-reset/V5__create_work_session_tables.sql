-- Migration: create lightweight work session tables
-- PostgreSQL dialect

CREATE TABLE IF NOT EXISTS work_session (
  session_id bigserial PRIMARY KEY,
  person_id varchar(64) NOT NULL,
  status varchar(32) NOT NULL,
  started_at timestamptz NOT NULL,
  ended_at timestamptz,
  actor varchar(128),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS work_session_break (
  break_id bigserial PRIMARY KEY,
  session_id bigint NOT NULL,
  started_at timestamptz NOT NULL,
  ended_at timestamptz,
  actor varchar(128),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_work_session_person_id ON work_session(person_id);
CREATE INDEX IF NOT EXISTS idx_work_session_status ON work_session(status);
CREATE INDEX IF NOT EXISTS idx_work_session_break_session_id ON work_session_break(session_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_work_session_active_person
  ON work_session(person_id) WHERE ended_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_work_session_break_active_session
  ON work_session_break(session_id) WHERE ended_at IS NULL;
