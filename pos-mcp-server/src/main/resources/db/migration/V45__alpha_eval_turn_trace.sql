CREATE TABLE mcp_eval_turn_trace (
    turn_id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    trace_payload JSONB NOT NULL
);

CREATE INDEX idx_mcp_eval_turn_trace_expires_at
    ON mcp_eval_turn_trace (expires_at);