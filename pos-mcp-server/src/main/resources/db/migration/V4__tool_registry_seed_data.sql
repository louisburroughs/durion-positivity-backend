WITH roles(name) AS (
  VALUES ('ROLE_CASHIER'),
    ('ROLE_SERVICE_WRITER'),
    ('ROLE_MANAGER'),
    ('ROLE_ADMIN'),
    ('ROLE_SUPPLIER')
)
INSERT INTO mcp_role (name)
SELECT name
FROM roles ON CONFLICT DO NOTHING;
WITH workflow_states(name) AS (
  VALUES ('IDLE'),
    ('CREATING_PO'),
    ('RECEIVING_ASN'),
    ('INVENTORY_RECON')
)
INSERT INTO mcp_workflow_state (name)
SELECT name
FROM workflow_states ON CONFLICT DO NOTHING;
WITH tools(
  name,
  display_name,
  description,
  domain,
  priority,
  cost_level,
  avg_latency_ms,
  enabled,
  handler_bean
) AS (
  VALUES (
      'InventoryFacadeTool',
      'Inventory',
      'Inventory availability, stocking, and reconciliation workflows',
      'inventory',
      1.0,
      'low',
      220,
      TRUE,
      'inventoryFacadeTool'
    ),
    (
      'OrderFacadeTool',
      'Order',
      'Purchase and sales order workflows across suppliers and stores',
      'order',
      1.0,
      'low',
      240,
      TRUE,
      'orderFacadeTool'
    ),
    (
      'CustomerFacadeTool',
      'Customer',
      'Customer profile, account, and communication context',
      'customer',
      0.9,
      'low',
      180,
      TRUE,
      'customerFacadeTool'
    ),
    (
      'PricingFacadeTool',
      'Pricing',
      'Price lookup, discount, and margin guidance',
      'pricing',
      0.9,
      'medium',
      210,
      TRUE,
      'pricingFacadeTool'
    ),
    (
      'WorkorderFacadeTool',
      'Workorder',
      'workorder intake, assignment, and lifecycle orchestration',
      'workorder',
      1.1,
      'medium',
      260,
      TRUE,
      'workorderFacadeTool'
    ),
    (
      'CatalogFacadeTool',
      'Catalog',
      'Product catalog discovery and item metadata lookup',
      'catalog',
      0.9,
      'low',
      170,
      TRUE,
      'catalogFacadeTool'
    ),
    (
      'VehicleFacadeTool',
      'Vehicle',
      'Vehicle fitment, VIN lookups, and compatibility checks',
      'vehicle',
      0.8,
      'medium',
      260,
      TRUE,
      'vehicleFacadeTool'
    ),
    (
      'AccountingFacadeTool',
      'Accounting',
      'Accounting summaries and ledger-facing business context',
      'accounting',
      0.8,
      'high',
      320,
      TRUE,
      'accountingFacadeTool'
    ),
    (
      'InvoiceFacadeTool',
      'Invoice',
      'Invoice generation and invoice status operations',
      'invoice',
      0.9,
      'medium',
      250,
      TRUE,
      'invoiceFacadeTool'
    ),
    (
      'HrFacadeTool',
      'HR',
      'Workforce scheduling and HR policy information',
      'hr',
      0.7,
      'medium',
      300,
      TRUE,
      'hrFacadeTool'
    ),
    (
      'ReportingFacadeTool',
      'Reporting',
      'Operational and executive reporting metrics',
      'reporting',
      0.8,
      'high',
      350,
      TRUE,
      'reportingFacadeTool'
    ),
    (
      'LocationFacadeTool',
      'Location',
      'Store and location context for local operations',
      'location',
      0.8,
      'low',
      180,
      TRUE,
      'locationFacadeTool'
    ),
    (
      'ShopManagerFacadeTool',
      'Shop Manager',
      'Shop manager controls and branch-level operations',
      'shop-manager',
      0.9,
      'medium',
      260,
      TRUE,
      'shopManagerFacadeTool'
    ),
    (
      'TaxFacadeTool',
      'Tax',
      'Tax computation and tax rule interpretation',
      'tax',
      0.7,
      'high',
      300,
      TRUE,
      'taxFacadeTool'
    ),
    (
      'AdminFacadeTool',
      'Admin',
      'Administrative controls and platform governance operations',
      'admin',
      0.8,
      'high',
      320,
      TRUE,
      'adminFacadeTool'
    ),
    (
      'EventsFacadeTool',
      'Events',
      'Event audit, retrieval, and observability context',
      'events',
      0.8,
      'low',
      200,
      TRUE,
      'eventsFacadeTool'
    )
)
INSERT INTO mcp_tool (
    name,
    display_name,
    description,
    domain,
    priority,
    cost_level,
    avg_latency_ms,
    enabled,
    handler_bean
  )
SELECT name,
  display_name,
  description,
  domain,
  priority,
  cost_level,
  avg_latency_ms,
  enabled,
  handler_bean
FROM tools ON CONFLICT DO NOTHING;
WITH role_tool(role_name, tool_name) AS (
  VALUES -- ROLE_CASHIER
    ('ROLE_CASHIER', 'InventoryFacadeTool'),
    ('ROLE_CASHIER', 'OrderFacadeTool'),
    ('ROLE_CASHIER', 'CustomerFacadeTool'),
    ('ROLE_CASHIER', 'PricingFacadeTool'),
    -- ROLE_SERVICE_WRITER
    ('ROLE_SERVICE_WRITER', 'WorkorderFacadeTool'),
    ('ROLE_SERVICE_WRITER', 'CustomerFacadeTool'),
    ('ROLE_SERVICE_WRITER', 'VehicleFacadeTool'),
    ('ROLE_SERVICE_WRITER', 'CatalogFacadeTool'),
    ('ROLE_SERVICE_WRITER', 'PricingFacadeTool'),
    ('ROLE_SERVICE_WRITER', 'InventoryFacadeTool'),
    -- ROLE_MANAGER (service writer + reporting/accounting/invoice/hr)
    ('ROLE_MANAGER', 'WorkorderFacadeTool'),
    ('ROLE_MANAGER', 'CustomerFacadeTool'),
    ('ROLE_MANAGER', 'VehicleFacadeTool'),
    ('ROLE_MANAGER', 'CatalogFacadeTool'),
    ('ROLE_MANAGER', 'PricingFacadeTool'),
    ('ROLE_MANAGER', 'InventoryFacadeTool'),
    ('ROLE_MANAGER', 'ReportingFacadeTool'),
    ('ROLE_MANAGER', 'AccountingFacadeTool'),
    ('ROLE_MANAGER', 'InvoiceFacadeTool'),
    ('ROLE_MANAGER', 'HrFacadeTool'),
    -- ROLE_SUPPLIER
    ('ROLE_SUPPLIER', 'OrderFacadeTool'),
    ('ROLE_SUPPLIER', 'InventoryFacadeTool'),
    ('ROLE_SUPPLIER', 'CatalogFacadeTool'),
    -- ROLE_ADMIN (all 16)
    ('ROLE_ADMIN', 'InventoryFacadeTool'),
    ('ROLE_ADMIN', 'OrderFacadeTool'),
    ('ROLE_ADMIN', 'CustomerFacadeTool'),
    ('ROLE_ADMIN', 'PricingFacadeTool'),
    ('ROLE_ADMIN', 'WorkorderFacadeTool'),
    ('ROLE_ADMIN', 'CatalogFacadeTool'),
    ('ROLE_ADMIN', 'VehicleFacadeTool'),
    ('ROLE_ADMIN', 'AccountingFacadeTool'),
    ('ROLE_ADMIN', 'InvoiceFacadeTool'),
    ('ROLE_ADMIN', 'HrFacadeTool'),
    ('ROLE_ADMIN', 'ReportingFacadeTool'),
    ('ROLE_ADMIN', 'LocationFacadeTool'),
    ('ROLE_ADMIN', 'ShopManagerFacadeTool'),
    ('ROLE_ADMIN', 'TaxFacadeTool'),
    ('ROLE_ADMIN', 'AdminFacadeTool'),
    ('ROLE_ADMIN', 'EventsFacadeTool')
)
INSERT INTO mcp_tool_role (tool_id, role_id)
SELECT t.id,
  r.id
FROM role_tool rt
  JOIN mcp_tool t ON t.name = rt.tool_name
  JOIN mcp_role r ON r.name = rt.role_name ON CONFLICT DO NOTHING;
WITH idle_workflow(tool_name) AS (
  VALUES ('InventoryFacadeTool'),
    ('OrderFacadeTool'),
    ('CustomerFacadeTool'),
    ('PricingFacadeTool'),
    ('WorkorderFacadeTool'),
    ('CatalogFacadeTool'),
    ('VehicleFacadeTool'),
    ('AccountingFacadeTool'),
    ('InvoiceFacadeTool'),
    ('HrFacadeTool'),
    ('ReportingFacadeTool'),
    ('LocationFacadeTool'),
    ('ShopManagerFacadeTool'),
    ('TaxFacadeTool'),
    ('AdminFacadeTool'),
    ('EventsFacadeTool')
)
INSERT INTO mcp_tool_workflow (tool_id, workflow_state_id)
SELECT t.id,
  ws.id
FROM idle_workflow iw
  JOIN mcp_tool t ON t.name = iw.tool_name
  JOIN mcp_workflow_state ws ON ws.name = 'IDLE' ON CONFLICT DO NOTHING;
WITH workflow_tool(workflow_name, tool_name) AS (
  VALUES ('CREATING_PO', 'OrderFacadeTool'),
    ('CREATING_PO', 'InventoryFacadeTool'),
    ('CREATING_PO', 'CatalogFacadeTool'),
    ('CREATING_PO', 'PricingFacadeTool'),
    ('RECEIVING_ASN', 'InventoryFacadeTool'),
    ('RECEIVING_ASN', 'OrderFacadeTool'),
    ('RECEIVING_ASN', 'CatalogFacadeTool'),
    ('RECEIVING_ASN', 'LocationFacadeTool'),
    ('RECEIVING_ASN', 'ShopManagerFacadeTool'),
    ('INVENTORY_RECON', 'InventoryFacadeTool'),
    ('INVENTORY_RECON', 'CatalogFacadeTool'),
    ('INVENTORY_RECON', 'LocationFacadeTool'),
    ('INVENTORY_RECON', 'ShopManagerFacadeTool'),
    ('INVENTORY_RECON', 'ReportingFacadeTool')
)
INSERT INTO mcp_tool_workflow (tool_id, workflow_state_id)
SELECT t.id,
  ws.id
FROM workflow_tool wt
  JOIN mcp_tool t ON t.name = wt.tool_name
  JOIN mcp_workflow_state ws ON ws.name = wt.workflow_name ON CONFLICT DO NOTHING;