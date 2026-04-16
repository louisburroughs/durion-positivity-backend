-- V7 H2: make tool_id nullable
ALTER TABLE mcp_tool_invocation_log
ALTER COLUMN tool_id DROP NOT NULL;
