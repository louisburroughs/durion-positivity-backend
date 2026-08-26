-- V37: Re-derive the facade tools' mcp_tool_permission rows from their REAL downstream
-- endpoints (#1519 Wave 4).
--
-- Waves 2-3 of #1519 retargeted every facade @Tool method (and every composition leg) to a
-- verified backend endpoint (single source of truth: pos-mcp-server/src/test/resources/
-- facade-contract.yaml). This migration re-runs the V18 derivation procedure (README
-- §Facade tools) against those real targets, on the #1499/#1512-remediated permission base:
--
--   * For each backend endpoint a @Tool method (or composition leg) calls, read the merged
--     class + method @PreAuthorize on the serving controller.
--   * hasAuthority('X') / hasAnyAuthority('X','Y') contribute codes X, Y.
--   * hasRole(...) fragments cannot be mirrored (this table stores permission codes, not
--     role names) — dropped; the role gate is still enforced in the downstream service.
--   * isAuthenticated() / no @PreAuthorize contributes the AUTHENTICATED sentinel ONLY when
--     the tool class would otherwise have zero codes (mcp_tool_permission is OR-semantics —
--     see V36's header comment: a tool holding any real code is reachable via that code, so
--     adding AUTHENTICATED would open it to every logged-in caller, the #1115 failure mode).
--   * Union across every @Tool method in the tool class; compositions union across all legs.
--
-- Shape: per tool, DELETE the old rows then INSERT the derived set — idempotent
-- (ON CONFLICT DO NOTHING) and a no-op for any tool name absent from mcp_tool.
-- V18/V29/V35/V36 are applied migrations and are never edited (README rule); this
-- migration supersedes their net facade state.
--
-- Stale codes this delete+reinsert removes (seeded by V18 for endpoints the facades no
-- longer call, or never actually called):
--   InventoryFacadeTool     inventory:on_hand:search      (stale per #1497/#1499 — by-sku
--                                                          availability is guarded by
--                                                          inventory:availability:read since
--                                                          ADR-0057; V36 already added it)
--   VehicleFacadeTool       crm:vehicle:search
--   AccountingFacadeTool    accounting:je:view             (JE search RESHAPEd to the
--                                                          general-ledger report)
--   HrFacadeTool            people:person:view             (searchEmployees removed, #1523)
--   LocationFacadeTool      inventory:location:view
--   ShopManagerFacadeTool   shop:location:view
--   PricingFacadeTool       pricing:price_book:view        (price books read from catalog
--                                                          per ADR-0054 → catalog:price_book:read)
--   CatalogFacadeTool       catalog:category:view          (category browse is a products
--                                                          search, guarded by product:view)
--   AdminFacadeTool         — no change in set (security:user:view / security:permission:view /
--                             security:audit:view re-confirmed against the real endpoints)
--   ExaWebSearchTool        — untouched: external SaaS, always-on in code
--                             (ToolSelectionEngine), has no mcp_tool_permission rows.
--
-- ═════════════════════════════════════════════════════════════════════════════════════════
-- Derivation table (tool → downstream endpoints → merged @PreAuthorize → codes)
-- ═════════════════════════════════════════════════════════════════════════════════════════
--
-- AccountingFacadeTool
--   getAccountBalance      GET /v1/accounting/gl-accounts/{glAccountId}/balance
--                          → hasAuthority('accounting:coa:view')            GLAccountController:351-355
--   getGeneralLedger       GET /v1/accounting/reports/financial/general-ledger
--                          → hasAuthority('reporting:view:financial-statements')
--                                                                           FinancialReportingController:352-353
--   getFinancialSummary    COMPOSE income-statement + balance-sheet + trial-balance
--                          → hasAuthority('reporting:view:financial-statements') on all three legs
--                                                                           FinancialReportingController:69-70,123-124,162-163
--   Union: accounting:coa:view, reporting:view:financial-statements
--
-- ReportingFacadeTool
--   getSalesReport         GET /v1/accounting/reports/financial/income-statement
--                          → reporting:view:financial-statements            FinancialReportingController:69-70
--   getInventoryReport     GET /v1/inventory/locations/{locationId}/inventory-rollup
--                          → hasAuthority('inventory:on_hand:view')         LocationInventoryRollupController:36-37
--   getRevenueReport       COMPOSE income-statement + aged-receivables
--                          → reporting:view:financial-statements on both legs
--                                                                           FinancialReportingController:69-70,422-423
--   Union: reporting:view:financial-statements, inventory:on_hand:view
--
-- CatalogFacadeTool
--   getProduct             GET /v1/products/{productId}
--   searchCatalog          GET /v1/products/search?q=
--   getCatalogByCategory   GET /v1/products/search?category=
--                          → all: hasRole('ADMIN') or hasAuthority('catalog:product:view')
--                            (role fragment dropped)                        ProductController:337-341,545-549
--   Union: catalog:product:view
--
-- CustomerFacadeTool
--   getCustomer            GET /v1/crm/accounts/parties/{partyId}
--                          → hasAuthority('crm:party:view')                 CrmAccountsController:253-257
--   searchCustomers        GET /v1/crm/accounts/parties?name=
--                          → crm:party:view                                 CrmAccountsController:294-298
--   getCustomerHistory     COMPOSE:
--     snapshot             GET /v1/crm/snapshot/party/{partyId}
--                          → crm:party:view                                 CrmSnapshotController:73-74
--     interactions         GET /v1/crm/parties/{partyId}/interactions
--                          → hasAuthority('crm:interaction:view')           CrmInteractionController:66-70
--     invoices             GET /v1/invoices/items/search?partyId=
--                          → hasAuthority('invoice:manage')                 InvoiceSearchController:112-113
--     workorders           GET /v1/workorders/search?customerId=
--                          → hasAuthority('workorder:workorder:view')       WorkorderSearchController:56-57
--   Union: crm:party:view, crm:interaction:view, invoice:manage, workorder:workorder:view
--
-- EventsFacadeTool
--   getEventTypes          GET /v1/eventTypes/active     → no @PreAuthorize EventTypeController:71
--   getEventSummary        GET /v1/events/summary/{window} → no @PreAuthorize
--                                                                           EventSummaryController:39,68,97
--   Union: AUTHENTICATED (sentinel — the tool class has zero permission-coded guards)
--
-- HrFacadeTool
--   getEmployee            GET /v1/people/employees/{employeeId}
--                          → hasAuthority('people:employee:view')           EmployeeController:143-162
--   getEmployeeSchedule    GET /v1/people/availability?employeeId=
--                          → hasAuthority('people:availability:view')       PeopleAvailabilityController:63-68
--   Union: people:employee:view, people:availability:view
--
-- InventoryFacadeTool
--   checkStock             GET /v1/inventory/availability/by-sku?productSku=
--   searchInventory        GET /v1/inventory/availability/by-sku?productSku=[&locationId&sourceType]
--                          → hasAuthority('inventory:availability:read')    InventoryAvailabilityController:210-214
--   getLocationStock       GET /v1/inventory/locations/{locationId}/inventory-inquiry
--                          → hasAuthority('inventory:on_hand:view')         LocationInventoryInquiryController:35-36
--   Union: inventory:availability:read, inventory:on_hand:view   (ADR-0057 split, V36)
--
-- InvoiceFacadeTool
--   getInvoice             GET /v1/invoices/{invoiceId}
--                          → class-level hasAuthority('invoice:manage')     InvoiceController:38,176
--   searchInvoices         GET /v1/invoices/search?q=       → invoice:manage InvoiceSearchController:74-75
--   getInvoicesByCustomer  GET /v1/invoices/items/search?partyId=
--                          → invoice:manage                                 InvoiceSearchController:112-113
--   Union: invoice:manage
--
-- LocationFacadeTool
--   getLocation            GET /v1/locations/{locationId}  → hasAuthority('location:read')
--                                                                           LocationController:143-147
--   searchLocations        GET /v1/locations               → location:read  LocationController:85-89
--   getLocationInventory   GET /v1/inventory/locations/{locationId}/inventory-inquiry (cross-domain)
--                          → inventory:on_hand:view                         LocationInventoryInquiryController:35-36
--   Union: location:read, inventory:on_hand:view
--
-- OrderFacadeTool
--   getOrder               GET /v1/orders/carts/{orderId}
--   listOrders             GET /v1/orders/carts
--                          → class isAuthenticated() + method hasAuthority('order:order:view')
--                            (sentinel not added — a real code exists)      SalesOrderController:52,134-135,278-279
--   Union: order:order:view
--
-- PricingFacadeTool
--   getPriceForSku         COMPOSE:
--     product              GET /v1/products/search?sku=&detailed=true
--                          → hasRole('ADMIN') or catalog:product:view       ProductController:337-341
--     effectivePrice       GET /v1/products/pricing/effective-price/{locationId}/{productId}
--                          → hasRole('ADMIN') or catalog:location_price_override:read
--                                                                           ProductController:190-194
--   getPromotionByCode     GET /v1/promotions/offers/by-code/{promoCode}
--                          → hasAuthority('pricing:promotion:view')         PromotionOfferController:128-132
--   listPriceRestrictions  GET /v1/price/restrictions/rules
--                          → hasAuthority('pricing:rule:view')              RestrictionRuleController:146-147
--   getPriceList           GET /v1/products/price-books/{priceBookId} (ADR-0054 → catalog)
--                          → hasAuthority('catalog:price_book:read')        PriceBookController:90-94
--   Union: catalog:product:view, catalog:location_price_override:read, catalog:price_book:read,
--          pricing:promotion:view, pricing:rule:view
--
-- ShopManagerFacadeTool
--   getShopStatus          COMPOSE location + schedule + openWorkorders
--     location             GET /v1/locations/{locationId}   → location:read LocationController:143-147
--     schedule             GET /v1/schedules/view?locationId&date
--                          → hasAuthority('shop:schedule:view')             ScheduleController:56-58
--     openWorkorders       GET /v1/workexec/wip?locationId=
--                          → hasAuthority('workorder:wip:view')             WipController:67-68
--   getShopQueue           COMPOSE openWorkorders + schedule (same legs/guards)
--   searchShops            GET /v1/locations                → location:read LocationController:85-89
--   Union: location:read, shop:schedule:view, workorder:wip:view
--
-- TaxFacadeTool
--   calculateTax           COMPOSE:
--     location             GET /v1/locations/{locationId}   → location:read LocationController:143-147
--     tax                  POST /v1/tax/calculate (ADR-0021 direct)
--                          → hasAuthority('tax:calculate')                  TaxController:49-50
--   getTaxSummary          GET /v1/accounting/reports/financial/tax-liability
--                          → reporting:view:financial-statements            FinancialReportingController:524-525
--   Union: tax:calculate, location:read, reporting:view:financial-statements
--
-- VehicleFacadeTool
--   getVehicle             GET /v1/vehicle-registry/{vehicleId}
--                          → class isAuthenticated() + hasAuthority('vehicle-inventory:registry:view')
--                                                                           VehicleRegistryController:52,138-142
--   searchVehicles         GET /v1/vehicles/search?q=
--                          → class isAuthenticated() + hasAuthority('vehicle-inventory:search:view')
--                                                                           VehicleSearchController:35,116-120
--   getVehiclesByCustomer  GET /v1/crm/{customerId}/vehicles
--                          → hasAuthority('crm:vehicle:view')               CrmVehiclesController:76-80
--   Union: vehicle-inventory:registry:view, vehicle-inventory:search:view, crm:vehicle:view
--
-- WorkorderFacadeTool
--   getWorkorder           GET /v1/workorders/{workorderId} → workorder:workorder:view
--                                                                           WorkorderController:134-138
--   searchWorkorders       GET /v1/workorders/search?q=     → workorder:workorder:view
--                                                                           WorkorderSearchController:56-57
--   getWorkorderStatus     GET /v1/workorders/{workorderId} (same endpoint as getWorkorder)
--   Union: workorder:workorder:view
--
-- AdminFacadeTool
--   getSystemStatus        no HTTP call (local constant response)
--   listUsers              GET /v1/users (bare users base URL)
--                          → hasAuthority('security:user:view')             UserController:87-88
--   getUserPermissions     GET /v1/users/{userId}/permissions
--                          → hasAuthority('security:permission:view')       UserRoleController:88-92
--   getMyPermissions       same endpoint as getUserPermissions
--   getAuditLog            GET /v1/audit/events?eventType=
--                          → hasAuthority('security:audit:view')            AuditController:139-140
--   Union: security:user:view, security:permission:view, security:audit:view
--
-- Every code above is registered in the owning module's permission source
-- ({module}/src/main/resources/permissions.yaml + *Permissions/*PermissionRegistry class),
-- verified on the #1499/#1512-remediated base.
-- ═════════════════════════════════════════════════════════════════════════════════════════

-- AccountingFacadeTool ────────────────────────────────────────────────────────
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

-- ReportingFacadeTool ─────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'ReportingFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('reporting:view:financial-statements'),
    ('inventory:on_hand:view')
) AS perms(code)
WHERE mcp_tool.name = 'ReportingFacadeTool'
ON CONFLICT DO NOTHING;

-- CatalogFacadeTool ───────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'CatalogFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('catalog:product:view')
) AS perms(code)
WHERE mcp_tool.name = 'CatalogFacadeTool'
ON CONFLICT DO NOTHING;

-- CustomerFacadeTool ──────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'CustomerFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('crm:party:view'),
    ('crm:interaction:view'),
    ('invoice:manage'),
    ('workorder:workorder:view')
) AS perms(code)
WHERE mcp_tool.name = 'CustomerFacadeTool'
ON CONFLICT DO NOTHING;

-- EventsFacadeTool ────────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'EventsFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('AUTHENTICATED')
) AS perms(code)
WHERE mcp_tool.name = 'EventsFacadeTool'
ON CONFLICT DO NOTHING;

-- HrFacadeTool ────────────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'HrFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('people:employee:view'),
    ('people:availability:view')
) AS perms(code)
WHERE mcp_tool.name = 'HrFacadeTool'
ON CONFLICT DO NOTHING;

-- InventoryFacadeTool ─────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'InventoryFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('inventory:availability:read'),
    ('inventory:on_hand:view')
) AS perms(code)
WHERE mcp_tool.name = 'InventoryFacadeTool'
ON CONFLICT DO NOTHING;

-- InvoiceFacadeTool ───────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'InvoiceFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('invoice:manage')
) AS perms(code)
WHERE mcp_tool.name = 'InvoiceFacadeTool'
ON CONFLICT DO NOTHING;

-- LocationFacadeTool ──────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'LocationFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('location:read'),
    ('inventory:on_hand:view')
) AS perms(code)
WHERE mcp_tool.name = 'LocationFacadeTool'
ON CONFLICT DO NOTHING;

-- OrderFacadeTool ─────────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'OrderFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('order:order:view')
) AS perms(code)
WHERE mcp_tool.name = 'OrderFacadeTool'
ON CONFLICT DO NOTHING;

-- PricingFacadeTool ───────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'PricingFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('catalog:product:view'),
    ('catalog:location_price_override:read'),
    ('catalog:price_book:read'),
    ('pricing:promotion:view'),
    ('pricing:rule:view')
) AS perms(code)
WHERE mcp_tool.name = 'PricingFacadeTool'
ON CONFLICT DO NOTHING;

-- ShopManagerFacadeTool ───────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'ShopManagerFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('location:read'),
    ('shop:schedule:view'),
    ('workorder:wip:view')
) AS perms(code)
WHERE mcp_tool.name = 'ShopManagerFacadeTool'
ON CONFLICT DO NOTHING;

-- TaxFacadeTool ───────────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'TaxFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('tax:calculate'),
    ('location:read'),
    ('reporting:view:financial-statements')
) AS perms(code)
WHERE mcp_tool.name = 'TaxFacadeTool'
ON CONFLICT DO NOTHING;

-- VehicleFacadeTool ───────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'VehicleFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('vehicle-inventory:registry:view'),
    ('vehicle-inventory:search:view'),
    ('crm:vehicle:view')
) AS perms(code)
WHERE mcp_tool.name = 'VehicleFacadeTool'
ON CONFLICT DO NOTHING;

-- WorkorderFacadeTool ─────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'WorkorderFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('workorder:workorder:view')
) AS perms(code)
WHERE mcp_tool.name = 'WorkorderFacadeTool'
ON CONFLICT DO NOTHING;

-- AdminFacadeTool ─────────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'AdminFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_code)
SELECT id, code
FROM mcp_tool, (VALUES
    ('security:user:view'),
    ('security:permission:view'),
    ('security:audit:view')
) AS perms(code)
WHERE mcp_tool.name = 'AdminFacadeTool'
ON CONFLICT DO NOTHING;
