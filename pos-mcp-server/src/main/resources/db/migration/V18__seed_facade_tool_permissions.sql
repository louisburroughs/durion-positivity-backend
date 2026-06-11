-- V18: Seed mcp_tool_permission rows for the 15 facade tools.
--
-- Rule: OR semantics — the tool is visible to a caller who holds ANY of the
-- listed permission codes.  One row per (tool, code) pair.
--
-- Code derivation per tool:
--   * For each @Tool method, find the downstream REST call (URI template from
--     application.yml), locate the matching controller in the target service,
--     and read the merged @PreAuthorize on class + method.
--   * hasAuthority('X') / hasAnyAuthority('X','Y') → codes X, Y.
--   * isAuthenticated() or no @PreAuthorize → 'AUTHENTICATED'.
--   * Union all codes across every @Tool method in the tool class.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 1. InventoryFacadeTool
--    checkStock      → /inventory/v1/inventory/stock/{sku}
--                      → InventoryAvailabilityController (/v1/inventory/availability/by-sku)
--                      → hasAnyAuthority('inventory:on_hand:view','inventory:on_hand:search')
--    searchInventory → /inventory/v1/inventory/search?q={query}
--                      → InventoryAvailabilityController (same controller, same codes)
--    getLocationStock→ /inventory/v1/inventory/locations/{locationId}/stock
--                      → LocationInventoryInquiryController (/v1/inventory/locations/{locationId}/inventory-inquiry)
--                      → hasAuthority('inventory:on_hand:view')
--    Union: inventory:on_hand:view, inventory:on_hand:search
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('inventory:on_hand:view'),
    ('inventory:on_hand:search')
) AS perms(code)
WHERE mcp_tool.name = 'InventoryFacadeTool';

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. OrderFacadeTool
--    getOrder      → /order/v1/orders/{orderId}
--                    → SalesOrderController (/v1/orders) class-level: @PreAuthorize("isAuthenticated()")
--    searchOrders  → /order/v1/orders/search?q={query}
--                    → SalesOrderController (same, isAuthenticated())
--    Union: AUTHENTICATED
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('AUTHENTICATED')
) AS perms(code)
WHERE mcp_tool.name = 'OrderFacadeTool';

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. CustomerFacadeTool
--    getCustomer        → /customer/v1/customers/{customerId}
--                         → CustomerRequirementsController (/v1/customers/{id}/requirements-met)
--                         → hasAuthority('crm:party:view')
--    searchCustomers    → /customer/v1/customers/search?q={query}
--                         → CustomerController (/v1/crm, GET /{id}) + CrmAccountsController search
--                         → hasAuthority('crm:party:view')
--    getCustomerHistory → /customer/v1/customers/{customerId}/history
--                         → CustomerController / CrmSnapshotController
--                         → hasAuthority('crm:party:view')
--    Union: crm:party:view
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('crm:party:view')
) AS perms(code)
WHERE mcp_tool.name = 'CustomerFacadeTool';

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. PricingFacadeTool
--    getPriceForSku → /price/v1/pricing/sku/{sku}
--                     → PriceQuoteController (/v1/price/quotes): @PreAuthorize("isAuthenticated()")
--    searchPricing  → /price/v1/pricing/search?q={query}
--                     → PricingSnapshotController (/v1/price/snapshots): @PreAuthorize("isAuthenticated()")
--    getPriceList   → /price/v1/pricing/lists/{priceListId}
--                     → PricingSnapshotController or PriceNormalizationController: isAuthenticated()
--    Union: AUTHENTICATED
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('AUTHENTICATED')
) AS perms(code)
WHERE mcp_tool.name = 'PricingFacadeTool';

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. WorkorderFacadeTool
--    getWorkorder       → /workorder/v1/workorders/{workorderId}
--                         → WorkorderController (@GetMapping("/{workorderId}"))
--                         → @PreAuthorize("hasAuthority('workorder:workorder:view')")
--    searchWorkorders   → /workorder/v1/workorders/search?q={query}
--                         → WorkorderController (@GetMapping list)
--                         → @PreAuthorize("hasAuthority('workorder:workorder:view')")
--    getWorkorderStatus → /workorder/v1/workorders/{workorderId}/status
--                         → WorkorderController (transitions/snapshots fallback: isAuthenticated())
--                         → @PreAuthorize("isAuthenticated()") for state-inspection endpoints
--    Union: workorder:workorder:view, AUTHENTICATED
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('workorder:workorder:view'),
    ('AUTHENTICATED')
) AS perms(code)
WHERE mcp_tool.name = 'WorkorderFacadeTool';

-- ─────────────────────────────────────────────────────────────────────────────
-- 6. CatalogFacadeTool
--    getProduct         → /catalog/v1/catalog/products/{productId}
--                         → ProductController (/v1/products/{productId})
--                         → @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
--                         → role-based only — no hasAuthority(); use AUTHENTICATED
--    searchCatalog      → /catalog/v1/catalog/search?q={query}
--                         → ProductController (/v1/products/search) — same role gate
--    getCatalogByCategory → /catalog/v1/catalog/categories/{category}
--                         → CatalogController (/v1/catalogs) — same role gate
--    Union: AUTHENTICATED  (role gates are enforced separately at the gateway)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('AUTHENTICATED')
) AS perms(code)
WHERE mcp_tool.name = 'CatalogFacadeTool';

-- ─────────────────────────────────────────────────────────────────────────────
-- 7. VehicleFacadeTool
--    getVehicle           → /customer/v1/vehicles/{vehicleId}
--                           → CrmVehiclesController (@GetMapping("/{customerId}/vehicles/{vehicleId}"))
--                           → hasAuthority('crm:vehicle:view')
--    searchVehicles       → /customer/v1/vehicles/search?q={query}
--                           → CrmVehiclesController / CustomerController
--                           → hasAuthority('crm:vehicle:search')
--    getVehiclesByCustomer→ /customer/v1/vehicles/customer/{customerId}
--                           → CrmVehiclesController
--                           → hasAuthority('crm:vehicle:view')
--    Union: crm:vehicle:view, crm:vehicle:search
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('crm:vehicle:view'),
    ('crm:vehicle:search')
) AS perms(code)
WHERE mcp_tool.name = 'VehicleFacadeTool';

-- ─────────────────────────────────────────────────────────────────────────────
-- 8. AccountingFacadeTool
--    getAccountBalance     → /accounting/v1/accounting/accounts/{accountId}/balance
--                            → GLAccountController (@GetMapping("/{glAccountId}/balance"))
--                            → @PreAuthorize("hasAuthority('accounting:coa:view')")
--    searchJournalEntries  → /accounting/v1/accounting/journal-entries/search?q={query}
--                            → JournalEntryController (@GetMapping list)
--                            → @PreAuthorize("hasAuthority('accounting:je:view')")
--    getFinancialSummary   → /accounting/v1/accounting/summary/{period}
--                            → FinancialReportingController (/v1/accounting/reports/financial)
--                            → @PreAuthorize("hasAuthority('reporting:view:financial-statements')")
--    Union: accounting:coa:view, accounting:je:view, reporting:view:financial-statements
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('accounting:coa:view'),
    ('accounting:je:view'),
    ('reporting:view:financial-statements')
) AS perms(code)
WHERE mcp_tool.name = 'AccountingFacadeTool';

-- ─────────────────────────────────────────────────────────────────────────────
-- 9. InvoiceFacadeTool
--    getInvoice            → /invoice/v1/invoices/{invoiceId}
--                            → InvoiceController class-level: @PreAuthorize("hasAuthority('invoice:manage')")
--    searchInvoices        → /invoice/v1/invoices/search?q={query}
--                            → InvoiceController (same class-level gate)
--    getInvoicesByCustomer → /invoice/v1/invoices/customer/{customerId}
--                            → InvoiceController (same class-level gate)
--    Union: invoice:manage
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('invoice:manage')
) AS perms(code)
WHERE mcp_tool.name = 'InvoiceFacadeTool';

-- ─────────────────────────────────────────────────────────────────────────────
-- 10. HrFacadeTool
--    getEmployee       → /people/v1/people/employees/{employeeId}
--                        → EmployeeController (@GetMapping("/{employeeId}"))
--                        → @PreAuthorize("hasAuthority('people:employee:view')")
--    searchEmployees   → /people/v1/people?q={query}
--                        → PersonController (@GetMapping, @PreAuthorize("hasAuthority('people:person:view')"))
--    getEmployeeSchedule → /people/v1/people/availability?employeeId={employeeId}
--                        → PeopleAvailabilityController (@GetMapping("/availability"))
--                        → @PreAuthorize("hasAuthority('people:availability:view')")
--    Union: people:employee:view, people:person:view, people:availability:view
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('people:employee:view'),
    ('people:person:view'),
    ('people:availability:view')
) AS perms(code)
WHERE mcp_tool.name = 'HrFacadeTool';

-- ─────────────────────────────────────────────────────────────────────────────
-- 11. ReportingFacadeTool
--    getSalesReport    → /accounting/v1/reporting/sales/{period}
--                        → FinancialReportingController (/v1/accounting/reports/financial/income-statement)
--                        → @PreAuthorize("hasAuthority('reporting:view:financial-statements')")
--    getInventoryReport→ /accounting/v1/reporting/inventory/{locationId}
--                        → FinancialReportingController (same controller, same gate)
--    getRevenueReport  → /accounting/v1/reporting/revenue/{period}
--                        → FinancialReportingController (same controller, same gate)
--    Union: reporting:view:financial-statements
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('reporting:view:financial-statements')
) AS perms(code)
WHERE mcp_tool.name = 'ReportingFacadeTool';

-- ─────────────────────────────────────────────────────────────────────────────
-- 12. LocationFacadeTool
--    getLocation          → /location/v1/locations/{locationId}
--                           → LocationController (@GetMapping("/{locationId}"))
--                           → @PreAuthorize("hasAuthority('location:read')")
--    searchLocations      → /location/v1/locations/search?q={query}
--                           → LocationController (@GetMapping list)
--                           → @PreAuthorize("hasAuthority('location:read')")
--    getLocationInventory → /location/v1/locations/{locationId}/inventory
--                           → InventoryReferenceDataController (/v1/inventory/locations)
--                           → @PreAuthorize("hasAuthority('inventory:location:view')")
--    Union: location:read, inventory:location:view
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('location:read'),
    ('inventory:location:view')
) AS perms(code)
WHERE mcp_tool.name = 'LocationFacadeTool';

-- ─────────────────────────────────────────────────────────────────────────────
-- 13. ShopManagerFacadeTool
--    getShopStatus → /shop-manager/v1/shop/{shopId}/status
--                    → ShopController (/v1/shop-manager/{locationId}/...): @PreAuthorize("hasAuthority('shop:location:view')")
--    getShopQueue  → /shop-manager/v1/shop/{shopId}/queue
--                    → ShopController / ScheduleController
--                    → hasAuthority('shop:location:view') / hasAuthority('shop:schedule:view')
--    searchShops   → /shop-manager/v1/shop/search?q={query}
--                    → ShopController: hasAuthority('shop:location:view')
--    Union: shop:location:view, shop:schedule:view
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('shop:location:view'),
    ('shop:schedule:view')
) AS perms(code)
WHERE mcp_tool.name = 'ShopManagerFacadeTool';

-- ─────────────────────────────────────────────────────────────────────────────
-- 14. TaxFacadeTool
--    getTaxRate    → pos.tax.base-url = http://pos-tax/v1/tax; template = /rates/{locationId}
--                    → full path /v1/tax/rates/{locationId}; no matching endpoint in TaxController
--                    → nearest: TaxController GET /v1/tax/mode → hasAuthority('tax:mode:view')
--                    → no exact endpoint for /rates → treat as AUTHENTICATED
--    calculateTax  → /v1/tax/calculate?amount=…&locationId=…
--                    → TaxController POST /v1/tax/calculate → @PreAuthorize("hasAuthority('tax:calculate')")
--                    → (note: calculateTax uses GET in tool but POST in controller — both need tax:calculate)
--    getTaxSummary → /v1/tax/summary/{period}; no matching endpoint → AUTHENTICATED
--    Union: tax:calculate, AUTHENTICATED
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('tax:calculate'),
    ('AUTHENTICATED')
) AS perms(code)
WHERE mcp_tool.name = 'TaxFacadeTool';

-- ─────────────────────────────────────────────────────────────────────────────
-- 15. AdminFacadeTool
--    getSystemStatus      → hardcoded, no downstream call → AUTHENTICATED
--    listUsers            → UserController → security:user:view
--    getUserPermissions / getMyPermissions → UserRoleController → security:permission:view
--    getAuditLog          → AuditController → security:audit:view
--    Union: AUTHENTICATED, security:user:view, security:permission:view, security:audit:view
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('AUTHENTICATED'),
    ('security:user:view'),
    ('security:permission:view'),
    ('security:audit:view')
) AS perms(code)
WHERE mcp_tool.name = 'AdminFacadeTool';

-- ─────────────────────────────────────────────────────────────────────────────
-- 16. EventsFacadeTool
--    getEventTypes  → /event-receiver/v1/events/eventTypes
--                     → EventTypeController (/v1/eventTypes, @GetMapping): no @PreAuthorize → AUTHENTICATED
--    searchEvents   → /event-receiver/v1/events/events/summary?q={query}
--                     → EventSummaryController (/v1/events/summary): no @PreAuthorize → AUTHENTICATED
--    getEventHistory→ /event-receiver/v1/events/events/summary?entityId={entityId}
--                     → EventSummaryController: no @PreAuthorize → AUTHENTICATED
--    Union: AUTHENTICATED
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('AUTHENTICATED')
) AS perms(code)
WHERE mcp_tool.name = 'EventsFacadeTool';
