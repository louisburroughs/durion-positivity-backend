CREATE TABLE mcp_eval_turn_trace (
    turn_id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    trace_payload JSON NOT NULL
);

CREATE INDEX idx_mcp_eval_turn_trace_expires_at
    ON mcp_eval_turn_trace (expires_at);