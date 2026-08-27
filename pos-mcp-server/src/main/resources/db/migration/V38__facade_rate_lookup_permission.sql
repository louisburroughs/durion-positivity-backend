-- V38: Add the tax:rates:view permission to TaxFacadeTool's seed (#1519 Wave 2 / #1522).
--
-- TaxFacadeTool.getTaxRate is restored (issue #1522, pos-mcp-server Wave 2 facade
-- restoration) as a composition of the existing location leg and a new direct GET
-- to pos-tax's /v1/tax/rates endpoint:
--
--   location   GET /v1/locations/{locationId}         -> hasAuthority('location:read')
--                                                          (already unioned into the seed by V37)
--   rates      GET /v1/tax/rates                       -> hasAuthority('tax:rates:view')
--                                                          TaxController:239 (new @PreAuthorize, #1522)
--
-- Net effect: TaxFacadeTool's derivation table (V37 header) gains tax:rates:view, giving
-- {tax:calculate, location:read, reporting:view:financial-statements, tax:rates:view}.
--
-- HrFacadeTool and EventsFacadeTool need NO change here:
--   * HrFacadeTool.searchEmployees calls GET /v1/people/employees ->
--     hasAuthority('people:employee:view'), already present in the V37 seed.
--   * EventsFacadeTool.getEventHistory calls GET /v1/events, which carries no @PreAuthorize
--     (module has no Spring Security; the gateway enforces JWT). Per the #1115 guard, a tool
--     already seeded with a real permission code must never also carry the AUTHENTICATED
--     sentinel, and EventsFacadeTool already has zero real codes and stays AUTHENTICATED-only
--     — adding a code here would be wrong, and EventsFacadeTool has none to add.
--
-- Shape: per-tool DELETE then INSERT (V37's idiom) — idempotent (ON CONFLICT DO NOTHING) and a
-- no-op for any tool name absent from mcp_tool. V18/V29/V35/V36/V37 are applied migrations and
-- are never edited (README rule); this migration supersedes their net TaxFacadeTool state.

-- TaxFacadeTool ───────────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'TaxFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('tax:calculate'),
    ('location:read'),
    ('reporting:view:financial-statements'),
    ('tax:rates:view')
) AS perms(code)
WHERE mcp_tool.name = 'TaxFacadeTool'
ON CONFLICT DO NOTHING;
