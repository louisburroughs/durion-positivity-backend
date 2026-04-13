-- V6: ivfflat index on mcp_tool.embedding + mcp_tool_invocation_log table
-- Phase 3: Add ivfflat index for pgvector cosine similarity on tool embeddings
-- (mcp_tool.embedding vector(768) added in V3)
CREATE INDEX IF NOT EXISTS idx_mcp_tool_embedding ON mcp_tool USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
-- Phase 4: Tool invocation audit log for adaptive priority tuning
CREATE TABLE IF NOT EXISTS mcp_tool_invocation_log (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tool_id UUID NOT NULL REFERENCES mcp_tool(id),
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
CREATE INDEX IF NOT EXISTS idx_mcp_tool_invocation_log_tool_id ON mcp_tool_invocation_log (tool_id);
CREATE INDEX IF NOT EXISTS idx_mcp_tool_invocation_log_created_at ON mcp_tool_invocation_log (created_at);