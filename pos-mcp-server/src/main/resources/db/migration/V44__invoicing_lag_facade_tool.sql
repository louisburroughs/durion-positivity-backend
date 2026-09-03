-- V44: promote E4 (workorder-creation-to-invoice lag) to InvoiceFacadeTool.getInvoicingLag
-- (issue #1660, plan A2 of docs/gate-closeout-plan-1660-1676.md).
--
-- V42 promoted E1/E5/E8 to facades and left E4 behind, so InvoiceFacadeTool exposed only
-- getInvoice/searchInvoices/getInvoicesByCustomer/getRevenueByCustomer. q04 ("average time from
-- work order creation to invoice, by month") has no facade to reach and can only fall into
-- searchInvoices, which is a free-text lookup with no work-order-creation timestamp in its rows
-- and cannot answer the question at all — see the plan's "Findings that change the plan" #1. This
-- migration is not a new mcp_tool row (the class row has existed since V4, same as V42's
-- InvoiceFacadeTool block): it refreshes the class row's description and re-derives its FULL
-- permission group set (V40/V42 per-tool delete-and-reinsert idiom).
--
-- ─── mcp_tool row ──────────────────────────────────────────────────────────────────────────
-- Description refreshed so tool selection can find the new capability; embedding nulled for
-- ToolEmbeddingInitializer to re-embed on next startup (V11/V13/V39/V42/V43 precedent — seeds
-- never write embedding vectors, they are derived data, V33 header).
--
-- ─── Permission group (V40/V42 AND-group semantics) ──────────────────────────────────────────
-- getInvoicingLag is a plain GET with no composition, guarded server-side by
-- InvoiceAnalyticsController.getInvoicingLag's hasAuthority('invoice:analytics:view')
-- (InvoiceAnalyticsController.java, W2 E4) — the same permission V42 already derived for
-- getRevenueByCustomer on this class, so it is a new singleton group named after the method (R1
-- of the V40 derivation rules), not a change to any existing group. The other three
-- InvoiceFacadeTool groups (getInvoice/searchInvoices/getInvoicesByCustomer ->
-- invoice:invoice:view) are unchanged and carried forward verbatim, because the DELETE below
-- clears the tool's rows wholesale and FacadeToolPermissionSeedTest replays the migration chain
-- looking for a full per-tool re-seed, not a partial add.
--
-- ─── Method → endpoint evidence ────────────────────────────────────────────────────────────
-- pos-mcp-server/src/test/resources/facade-contract.yaml, InvoiceFacadeTool.getInvoicingLag.
--
-- ─── Scope ────────────────────────────────────────────────────────────────────────────────
-- InvoiceFacadeTool only, as in V38/V39/V40/V41/V42/V43. No other facade changes here.
--
-- ─── H2 twin ──────────────────────────────────────────────────────────────────────────────
-- None, matching V38/V39/V40/V41/V42/V43: the tool-registry tables exist only in the Postgres
-- chain (H2 V27 header). Nothing to mirror.
-- ═════════════════════════════════════════════════════════════════════════════════════════

UPDATE mcp_tool
SET description = 'Invoice lookup, invoice search, a customer''s distinct invoices, per-customer '
    || 'revenue for a reporting period (top customers by revenue, invoice count, average invoice '
    || 'value, and most recent invoice date), and the average number of days from workorder '
    || 'creation to invoice creation for one date window (loop once per period for a by-month '
    || 'trend).',
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
    ('getRevenueByCustomer',  'invoice:analytics:view'),
    ('getInvoicingLag',       'invoice:analytics:view')
) AS perms(grp, code)
WHERE mcp_tool.name = 'InvoiceFacadeTool'
ON CONFLICT DO NOTHING;
