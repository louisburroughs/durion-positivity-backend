-- V35: Retarget facade tools with real domain read permissions off AUTHENTICATED.
--
-- V18 mirrored the downstream controller guards literally when seeding mcp_tool_permission.
-- That left three domain facades on the synthetic AUTHENTICATED floor even though the domains
-- already define explicit read permissions for the data the facades expose:
--   * OrderFacadeTool   → order:order:view
--   * PricingFacadeTool → pricing:price_book:view, pricing:rule:view
--   * CatalogFacadeTool → catalog:product:view, catalog:category:view
--
-- Tool selection should be bounded by those domain permissions, not by the universal
-- AUTHENTICATED sentinel. This changes only the MCP candidate gate; it does not alter the
-- downstream controller authorization yet.
--
-- EventsFacadeTool is intentionally untouched here. pos-event-receiver still has no registered
-- read permission catalog, so there is no domain permission code to seed for it yet.
--
-- Idempotent:
--   * deleting a missing AUTHENTICATED row is a no-op
--   * INSERT ... ON CONFLICT DO NOTHING tolerates re-runs

DELETE FROM mcp_tool_permission
WHERE permission_code = 'AUTHENTICATED'
  AND tool_id IN (
      SELECT id FROM mcp_tool WHERE name IN ('OrderFacadeTool', 'PricingFacadeTool', 'CatalogFacadeTool')
  );

INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('order:order:view')
) AS perms(code)
WHERE mcp_tool.name = 'OrderFacadeTool'
ON CONFLICT DO NOTHING;

INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('pricing:price_book:view'),
    ('pricing:rule:view')
) AS perms(code)
WHERE mcp_tool.name = 'PricingFacadeTool'
ON CONFLICT DO NOTHING;

INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('catalog:product:view'),
    ('catalog:category:view')
) AS perms(code)
WHERE mcp_tool.name = 'CatalogFacadeTool'
ON CONFLICT DO NOTHING;
