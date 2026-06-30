-- Gate 6 (G6.1): persisted write-action plan awaiting confirmation. The exact persisted args are
-- executed verbatim on confirm (never re-parsed). Conversation lifecycle in `status`.
CREATE TABLE IF NOT EXISTS nlti_write_plan (
    id                          uuid PRIMARY KEY,
    request_id                  uuid NOT NULL,
    session_id                  uuid NOT NULL,
    idempotency_key             varchar(128) NOT NULL UNIQUE,
    target_tool                 varchar(255) NOT NULL,
    args_json                   text NOT NULL,
    arg_provenance_json         text NOT NULL,
    risk_level                  varchar(16) NOT NULL,
    source_entity_versions_json text,
    summary_text                text NOT NULL,
    status                      varchar(32) NOT NULL DEFAULT 'PENDING_CONFIRMATION',
    expires_at                  timestamp with time zone NOT NULL,
    created_at                  timestamp with time zone NOT NULL,
    executed_at                 timestamp with time zone
);

CREATE INDEX IF NOT EXISTS idx_nlti_write_plan_session_status ON nlti_write_plan (session_id, status);

-- At most one PENDING_CONFIRMATION plan per session (Gate 6 single-pending rule).
CREATE UNIQUE INDEX IF NOT EXISTS uq_nlti_write_plan_one_pending
    ON nlti_write_plan (session_id)
    WHERE status = 'PENDING_CONFIRMATION';
