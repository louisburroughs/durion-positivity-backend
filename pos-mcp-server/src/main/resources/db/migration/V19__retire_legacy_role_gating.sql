-- Gate 2B / #780: retire the legacy role-gating tables.
-- Tool visibility is now fully determined by permission gating
-- (mcp_tool_permission ∩ mcp_workflow_state). The mcp_role / mcp_tool_role tables are no longer
-- queried at runtime (findAllRoleNames / findEnabledByRoleAndWorkflow removed).
--
-- Reversible rename (not DROP) per the gate rollback policy: keep the data for one release so a
-- rollback can rename them back. A later migration may DROP them once the retirement is confirmed.
ALTER TABLE IF EXISTS mcp_tool_role RENAME TO mcp_tool_role_deprecated;
ALTER TABLE IF EXISTS mcp_role RENAME TO mcp_role_deprecated;
