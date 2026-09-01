-- V42: W2.3 facade promotion (issue #1601) — promote three Wave 2 analytics aggregates to
-- facade methods so they compete for candidate-tool slots as first-class facades rather than
-- relying solely on OpenAPI discovery (analytics-capability-plan.md §4 W2.3).
--
-- Three *FacadeTool classes each gain one new @Tool method:
--
--   InvoiceFacadeTool.getRevenueByCustomer   GET /v1/invoices/analytics/revenue-by-customer
--                                             (E1, #1589) -> hasAuthority('invoice:analytics:view')
--                                                                InvoiceAnalyticsController:81
--   WorkorderFacadeTool.getTechnicianLaborAnalytics
--                                             GET /v1/workorders/analytics/technician-labor
--                                             (E5, #1593) -> hasAuthority('workorder:analytics:view')
--                                                                WorkorderAnalyticsController:194
--   AccountingFacadeTool.getVendorSpend       GET /v1/accounting/analytics/vendor-spend
--                                             (E8, #1596) -> hasAuthority('accounting:analytics:view')
--                                                                AccountingAnalyticsController:206
--
-- Method->endpoint evidence: pos-mcp-server/src/test/resources/facade-contract.yaml (new entries
-- InvoiceFacadeTool.getRevenueByCustomer / WorkorderFacadeTool.getTechnicianLaborAnalytics /
-- AccountingFacadeTool.getVendorSpend).
--
-- ─── mcp_tool rows ─────────────────────────────────────────────────────────────────────────
-- No new mcp_tool rows: facade tools are keyed PER CLASS in mcp_tool.name (PascalCase — see the
-- V34/V39 header note and SpringAiToolCallbackResolver, which keys permission gating and
-- invocation logging on the facade's user-class simple name; the @Tool method name is only the
-- Spring AI callback name). All three class rows have existed since V4, so each new method is
-- covered by its class row — the fail-closed rule holds because the class row carries permission
-- mappings. Descriptions are refreshed below so tool selection can find the new capability, and
-- each row's embedding is nulled for ToolEmbeddingInitializer to re-embed on next startup
-- (V11/V13/V39 precedent; seeds never write embedding vectors — they are derived data, V33
-- header).
--
-- ─── Permission groups (V40/#1606 AND-group semantics) ───────────────────────────────────────
-- Each new method is a plain GET with one required code and no composition, so it is a new
-- singleton group named after the method (R1 of the V40 derivation rules). None of the three
-- classes' EXISTING groups change codes here; this migration re-derives each class's FULL group
-- set (V40/V41 per-tool delete-and-reinsert idiom) because the DELETE below clears the tool's
-- rows wholesale, and FacadeToolPermissionSeedTest replays the migration chain looking for a
-- full per-tool re-seed, not a partial add.
--
--   InvoiceFacadeTool     + getRevenueByCustomer   -> invoice:analytics:view
--                           (existing three read methods keep invoice:invoice:view, V41)
--   WorkorderFacadeTool   + getTechnicianLaborAnalytics -> workorder:analytics:view
--                           (existing three methods keep workorder:workorder:view, V40)
--   AccountingFacadeTool  + getVendorSpend          -> accounting:analytics:view
--                           (existing five methods keep their V39-derived codes unchanged)
--
-- ─── Scope ────────────────────────────────────────────────────────────────────────────────
-- Facade tools only, as in V38/V39/V40/V41. The other nine Wave 2 operations (E2, E3, E4, E6,
-- E7, E9, E10, plus the E11/E12 search-filter additions) are left to OpenAPI discovery
-- (ToolRegistrationServiceImpl), which grants each op's x-required-permissions extension as its
-- own singleton group at bootstrap — no seed row needed here.
--
-- ─── H2 twin ──────────────────────────────────────────────────────────────────────────────
-- None, matching V38/V39/V40/V41: the tool-registry tables exist only in the Postgres chain (see
-- the H2 V27 header). Nothing to mirror.
-- ═════════════════════════════════════════════════════════════════════════════════════════

-- InvoiceFacadeTool ───────────────────────────────────────────────────────────
UPDATE mcp_tool
SET description = 'Invoice lookup, invoice search, a customer''s distinct invoices, and per-customer '
    || 'revenue for a reporting period (top customers by revenue, invoice count, average invoice '
    || 'value, and most recent invoice date).',
    embedding    = NULL
WHERE name = 'InvoiceFacadeTool';

DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'InvoiceFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('getInvoice',            'invoice:invoice:view'),
    ('searchInvoices',        'invoice:invoice:view'),
    ('getInvoicesByCustomer', 'invoice:invoice:view'),
    ('getRevenueByCustomer',  'invoice:analytics:view')
) AS perms(grp, code)
WHERE mcp_tool.name = 'InvoiceFacadeTool'
ON CONFLICT DO NOTHING;

-- WorkorderFacadeTool ─────────────────────────────────────────────────────────
UPDATE mcp_tool
SET description = 'Workorder lookup, workorder search, workorder status, and per-technician labor '
    || 'and revenue summaries for a reporting period (completed workorder count, billed hours, '
    || 'and labor revenue, per technician).',
    embedding    = NULL
WHERE name = 'WorkorderFacadeTool';

DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'WorkorderFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('getWorkorder',                 'workorder:workorder:view'),
    ('searchWorkorders',             'workorder:workorder:view'),
    ('getWorkorderStatus',           'workorder:workorder:view'),
    ('getTechnicianLaborAnalytics',  'workorder:analytics:view')
) AS perms(grp, code)
WHERE mcp_tool.name = 'WorkorderFacadeTool'
ON CONFLICT DO NOTHING;

-- AccountingFacadeTool ────────────────────────────────────────────────────────
UPDATE mcp_tool
SET description = 'Accounting summaries and ledger-facing business context: GL account balances, '
    || 'general-ledger activity, income statement / balance sheet / trial balance summaries, '
    || 'per-customer A/R and per-vendor A/P aging reports (past-due buckets, outstanding balances, '
    || 'point-in-time balances for historical as-of dates), and per-vendor spend for a reporting '
    || 'period (top vendors by settled A/P cash, bill count, and average bill amount).',
    embedding    = NULL
WHERE name = 'AccountingFacadeTool';

DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'AccountingFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('getAccountBalance',   'accounting:coa:view'),
    ('getGeneralLedger',    'reporting:view:financial-statements'),
    ('getFinancialSummary', 'reporting:view:financial-statements'),
    ('getAgedReceivables',  'reporting:view:financial-statements'),
    ('getAgedPayables',     'reporting:view:financial-statements'),
    ('getVendorSpend',      'accounting:analytics:view')
) AS perms(grp, code)
WHERE mcp_tool.name = 'AccountingFacadeTool'
ON CONFLICT DO NOTHING;
