-- Gate 2B / #780: drop the legacy role-gating tables.
-- V19 renamed mcp_tool_role -> mcp_tool_role_deprecated and mcp_role -> mcp_role_deprecated as a
-- reversible one-release retention. That retention window has passed and role gating is fully
-- superseded by permission gating (mcp_tool_permission ∩ mcp_workflow_state), so the deprecated
-- tables are now removed. Drop the join table first for FK safety. Portable to H2 and Postgres.
DROP TABLE IF EXISTS mcp_tool_role_deprecated;
DROP TABLE IF EXISTS mcp_role_deprecated;
