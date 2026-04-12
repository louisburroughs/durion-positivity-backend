-- V5: Add missing role mappings for LocationFacadeTool and ShopManagerFacadeTool
INSERT INTO mcp_tool_role (tool_id, role_id)
SELECT t.id,
  r.id
FROM mcp_tool t,
  mcp_role r
WHERE t.name = 'LocationFacadeTool'
  AND r.name = 'ROLE_SERVICE_WRITER' ON CONFLICT DO NOTHING;
INSERT INTO mcp_tool_role (tool_id, role_id)
SELECT t.id,
  r.id
FROM mcp_tool t,
  mcp_role r
WHERE t.name = 'LocationFacadeTool'
  AND r.name = 'ROLE_MANAGER' ON CONFLICT DO NOTHING;
INSERT INTO mcp_tool_role (tool_id, role_id)
SELECT t.id,
  r.id
FROM mcp_tool t,
  mcp_role r
WHERE t.name = 'ShopManagerFacadeTool'
  AND r.name = 'ROLE_SERVICE_WRITER' ON CONFLICT DO NOTHING;
INSERT INTO mcp_tool_role (tool_id, role_id)
SELECT t.id,
  r.id
FROM mcp_tool t,
  mcp_role r
WHERE t.name = 'ShopManagerFacadeTool'
  AND r.name = 'ROLE_MANAGER' ON CONFLICT DO NOTHING;