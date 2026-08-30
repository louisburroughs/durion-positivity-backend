-- V39: register the W1.2 aging facades (analytics-capability-plan.md §3, Wave 1).
--
-- AccountingFacadeTool gains two @Tool methods:
--
--   getAgedReceivables  GET /v1/accounting/reports/financial/aged-receivables?asOfDate=
--                       → hasAuthority('reporting:view:financial-statements')
--                                                          FinancialReportingController:422-423
--   getAgedPayables     GET /v1/accounting/reports/financial/aged-payables?asOfDate=
--                       → hasAuthority('reporting:view:financial-statements')
--                                                          FinancialReportingController:472-473
--
-- No new mcp_tool rows: facade tools are keyed PER CLASS in mcp_tool.name (PascalCase, e.g.
-- 'AccountingFacadeTool' — see the V34 header note and SpringAiToolCallbackResolver, which keys
-- permission gating and invocation logging on the facade's user-class simple name; the @Tool
-- method name is only the Spring AI callback name). The AccountingFacadeTool row has existed
-- since V4, so both new methods are covered by that row — the fail-closed rule holds because the
-- class row carries permission mappings.
--
-- 1) Refresh the class-level description so tool selection can find the aging capability, and
--    null the embedding so ToolEmbeddingInitializer re-embeds it on next startup (V11/V13
--    precedent; seeds never write embedding vectors — they are derived data, V33 header).
UPDATE mcp_tool
SET description = 'Accounting summaries and ledger-facing business context: GL account balances, '
    || 'general-ledger activity, income statement / balance sheet / trial balance summaries, and '
    || 'per-customer A/R and per-vendor A/P aging reports (past-due buckets, outstanding balances, '
    || 'point-in-time balances for historical as-of dates).',
    embedding    = NULL
WHERE name = 'AccountingFacadeTool';

-- 2) Re-derive AccountingFacadeTool's permission set (V37 procedure, V38 idiom: per-tool DELETE
--    then INSERT — idempotent, and a no-op for any tool name absent from mcp_tool). Both new
--    endpoints are guarded by reporting:view:financial-statements, which the V37 union already
--    contains, so the derived set is unchanged:
--    {accounting:coa:view, reporting:view:financial-statements}.
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'AccountingFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('accounting:coa:view'),
    ('reporting:view:financial-statements')
) AS perms(code)
WHERE mcp_tool.name = 'AccountingFacadeTool'
ON CONFLICT DO NOTHING;
