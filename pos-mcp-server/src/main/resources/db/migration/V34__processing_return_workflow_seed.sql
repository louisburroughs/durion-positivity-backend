-- V34: seed the PROCESSING_RETURN workflow tool set (Gate 2C completeness, issue #1215).
--
-- V4 seeded the IDLE set plus CREATING_PO / RECEIVING_ASN / INVENTORY_RECON, but neither the
-- PROCESSING_RETURN workflow-state row nor any PROCESSING_RETURN -> tool mapping. With zero rows in
-- mcp_tool_workflow for that state, a session advanced to PROCESSING_RETURN selects NO tools at all
-- (findEnabledByPermissionsAndWorkflow joins mcp_tool_workflow x mcp_workflow_state on the state
-- name), so the Gate 2C completeness item "PROCESSING_RETURN -> return-processing tools" can never
-- pass. This migration closes that gap.
--
-- Tool set: returns processing spans the order/RMA record (Order), return-to-stock and disposition
-- (Inventory), the returning party (Customer), credit/refund documents (Invoice), item identity and
-- units (Catalog), refund pricing and restock fees (Pricing), and warranty/service-related returns
-- (Workorder).
--
-- Join key: mcp_tool.name carries the PascalCase tool name ('OrderFacadeTool'); the camelCase form
-- ('orderFacadeTool') is the Spring bean id stored in mcp_tool.handler_bean, NOT the name column.
-- Joining on the camelCase form would silently match zero rows.
--
-- Idempotent: both inserts are ON CONFLICT DO NOTHING against the natural keys
-- (uk_mcp_workflow_state_name, and the (tool_id, workflow_state_id) primary key).
WITH workflow_states(name) AS (
  VALUES ('PROCESSING_RETURN')
)
INSERT INTO mcp_workflow_state (name)
SELECT name
FROM workflow_states ON CONFLICT DO NOTHING;
WITH workflow_tool(workflow_name, tool_name) AS (
  VALUES ('PROCESSING_RETURN', 'OrderFacadeTool'),
    ('PROCESSING_RETURN', 'InventoryFacadeTool'),
    ('PROCESSING_RETURN', 'CustomerFacadeTool'),
    ('PROCESSING_RETURN', 'InvoiceFacadeTool'),
    ('PROCESSING_RETURN', 'CatalogFacadeTool'),
    ('PROCESSING_RETURN', 'PricingFacadeTool'),
    ('PROCESSING_RETURN', 'WorkorderFacadeTool')
)
INSERT INTO mcp_tool_workflow (tool_id, workflow_state_id)
SELECT t.id,
  ws.id
FROM workflow_tool wt
  JOIN mcp_tool t ON t.name = wt.tool_name
  JOIN mcp_workflow_state ws ON ws.name = wt.workflow_name ON CONFLICT DO NOTHING;
