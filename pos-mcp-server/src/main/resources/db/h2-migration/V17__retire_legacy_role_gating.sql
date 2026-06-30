-- Gate 2B / #780: retire the legacy role-gating tables (H2 dev/test variant of pg V19).
-- IF EXISTS so this is a safe no-op when the H2 baseline never created these tables.
ALTER TABLE IF EXISTS mcp_tool_role RENAME TO mcp_tool_role_deprecated;
ALTER TABLE IF EXISTS mcp_role RENAME TO mcp_role_deprecated;
