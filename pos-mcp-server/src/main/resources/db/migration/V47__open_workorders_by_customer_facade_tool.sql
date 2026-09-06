-- V47: promote WorkorderFacadeTool.getOpenWorkordersByCustomer (issue #1855).
--
-- "Which customers have an open work order" had no answer inside a chat turn. searchWorkorders
-- exposes no page or size parameter and the backend returns 25 rows, so one status=OPEN call saw
-- 25 of alpha's 45 open work orders; the only complete plan was one search per candidate customer.
-- pos-workorder now groups them server-side (GET /v1/workorders/analytics/open-by-customer) and
-- the facade fronts it, in the shape V42 used for the Wave 2 analytics promotions.
--
-- ─── Why the description has to change ─────────────────────────────────────────────────────────
-- ToolMetadataRepositoryImpl selects candidates on mcp_tool.description and its embedding. A new
-- @Tool method whose capability is absent from the class description is effectively invisible to
-- selection, however good the method's own javadoc is — the model never sees the class row's text
-- unless the tool is already offered. So the description is refreshed and the embedding nulled for
-- re-embedding on the next backfill (#1823), exactly as V42 and V44 did.
--
-- ─── H2 twin ───────────────────────────────────────────────────────────────────────────────────
-- None, matching V38-V44 and V46: the tool-registry tables exist only in the Postgres chain.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════

UPDATE mcp_tool
SET description = 'Workorder lookup, workorder search (paged, filterable by customer, vehicle, '
    || 'status, created-at window or technician), workorder status, per-technician labor and '
    || 'revenue summaries, and per-customer counts of currently open work orders for questions '
    || 'about open work across the whole book.',
    embedding    = NULL
WHERE name = 'WorkorderFacadeTool';

-- Permission groups: the new method reads the same analytics surface as the technician-labor
-- promotion, so it carries workorder:analytics:view. Full delete then re-seed, the V40/V41 shape,
-- so the derivation is replayable rather than incrementally patched.
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'WorkorderFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('getWorkorder',                  'workorder:workorder:view'),
    ('searchWorkorders',              'workorder:workorder:view'),
    ('getWorkorderStatus',            'workorder:workorder:view'),
    ('getTechnicianLaborAnalytics',   'workorder:analytics:view'),
    ('getOpenWorkordersByCustomer',   'workorder:analytics:view')
) AS perms(grp, code)
WHERE mcp_tool.name = 'WorkorderFacadeTool'
ON CONFLICT DO NOTHING;
