-- V41: re-derive two facade tools' permission groups after #1612 moved their endpoint guards.
--
-- ─── Why a migration is needed at all ─────────────────────────────────────────────────────
-- V40 derives every group from the guard on the endpoint the @Tool method calls (rule R1). Two
-- of those guards changed in pos-invoice and pos-security-service, so the derivation is stale
-- for exactly two tools. Nothing about V40's semantics changes here: a tool is still offered iff
-- the caller holds ALL codes of AT LEAST ONE group.
--
-- ─── InvoiceFacadeTool ────────────────────────────────────────────────────────────────────
-- All three of its methods are reads, and all three sat behind invoice:manage:
--   getInvoice            GET /v1/invoices/{invoiceId}
--   searchInvoices        GET /v1/invoices/search
--   getInvoicesByCustomer GET /v1/invoices/items/search
-- #1612 gave those routes invoice:invoice:view, so the whole tool moves with them. This is the
-- finding the issue opened on: seven roles were blocked from an entirely read-only facade by a
-- write permission. Method→endpoint evidence is
-- pos-mcp-server/src/test/resources/facade-contract.yaml.
--
-- ─── AdminFacadeTool ──────────────────────────────────────────────────────────────────────
-- getMyPermissions delegates to getUserPermissions with the caller's OWN user id. That endpoint
-- required security:permission:view for self and other alike; #1612 made the self case authorised
-- by identity instead, so the method now requires no permission code.
--
-- It therefore contributes NO group, under V40 rule R3 — not an AUTHENTICATED group under R4. R4
-- is for a tool whose ONLY guard is the sentinel, which is why EventsFacadeTool carries it;
-- AdminFacadeTool has three privileged groups beside this one. Giving it a sentinel group as well
-- would satisfy the OR for every authenticated caller and offer an admin tool to everyone,
-- including the two customer roles — the #1115 defect, which
-- FacadeToolPermissionSeedTest#noFacadeMixesAuthenticatedWithPrivilege exists to catch and did.
--
-- The consequence is deliberate: the endpoint fix means any caller can read their own permissions
-- through the API, while the MCP tool stays gated on the admin codes. Reaching the tool is a
-- separate question from reaching the endpoint, and only the second one was a defect.
--
-- The other three groups are unchanged and are re-inserted verbatim, because the DELETE below
-- clears the tool's rows wholesale. In particular getAuditLog keeps security:audit:view. Issue
-- #1612 proposed closing the AdminFacadeTool row as "no action" on the grounds that
-- security:audit:view gates the tool; V40:436-441 shows it does not — the tool has four
-- single-code groups and reachability is OR across them, so the cheapest unlock was
-- security:user:view. No grant is made for it here.
--
-- ─── Scope ────────────────────────────────────────────────────────────────────────────────
-- Facade tools only, as in V40. Discovered OpenAPI operations keep their singleton-group shape
-- and are untouched.
--
-- ─── H2 twin ──────────────────────────────────────────────────────────────────────────────
-- None, matching V38/V39/V40: the tool-registry tables exist only in the Postgres chain.
-- ═════════════════════════════════════════════════════════════════════════════════════════

-- InvoiceFacadeTool ───────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'InvoiceFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('getInvoice',            'invoice:invoice:view'),
    ('searchInvoices',        'invoice:invoice:view'),
    ('getInvoicesByCustomer', 'invoice:invoice:view')
) AS perms(grp, code)
WHERE mcp_tool.name = 'InvoiceFacadeTool'
ON CONFLICT DO NOTHING;

-- AdminFacadeTool ─────────────────────────────────────────────────────────────
-- getSystemStatus and, since #1612, getMyPermissions both contribute no group (R3): the first
-- makes no HTTP call, the second no longer needs a permission code.
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'AdminFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('listUsers',          'security:user:view'),
    ('getUserPermissions', 'security:permission:view'),
    ('getAuditLog',        'security:audit:view')
) AS perms(grp, code)
WHERE mcp_tool.name = 'AdminFacadeTool'
ON CONFLICT DO NOTHING;
