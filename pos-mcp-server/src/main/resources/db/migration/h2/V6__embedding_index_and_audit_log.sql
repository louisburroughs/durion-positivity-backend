-- V6 H2 variant: audit log only (H2 does not support vector indexes)
CREATE TABLE IF NOT EXISTS mcp_tool_invocation_log (
  id UUID PRIMARY KEY DEFAULT random_uuid(),
  tool_id UUID NOT NULL,
  user_id VARCHAR(100),
  session_id VARCHAR(100),
  intent VARCHAR(200),
  workflow_state VARCHAR(100),
  semantic_rank INT,
  final_score NUMERIC(8, 4),
  selected BOOLEAN NOT NULL DEFAULT FALSE,
  success BOOLEAN NOT NULL DEFAULT FALSE,
  fallback_invoked BOOLEAN NOT NULL DEFAULT FALSE,
  execution_time_ms INT,
  error_type VARCHAR(200),
  created_at TIMESTAMP DEFAULT now()
);