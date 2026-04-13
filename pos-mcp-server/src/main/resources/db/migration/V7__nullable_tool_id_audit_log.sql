-- V7: Make tool_id nullable in mcp_tool_invocation_log to support session-level audit records
ALTER TABLE mcp_tool_invocation_log
ALTER COLUMN tool_id DROP NOT NULL;