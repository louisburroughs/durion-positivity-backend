-- V40: replace the flat OR-semantics permission gate with per-method AND-groups (#1606 finding 1).
--
-- ─── The defect ───────────────────────────────────────────────────────────────────────────
-- mcp_tool_permission was a flat (tool_id, permission_code) set, gated with
-- `permission_code = ANY(?)` — pure OR. V37 derived each facade's codes as the UNION of every
-- @Tool method AND every composition leg. Union + OR means the LEAST-privileged leg of a
-- composition admits the whole tool: CustomerFacadeTool picked up workorder:workorder:view from
-- getCustomerHistory's (optional) workorder leg, so a technician holding only that code was
-- offered a tool whose getCustomer requires crm:party:view and 403s downstream. Two live eval
-- fixtures pin this (ts-customerfacadetool-neg-role-technician / -dispatcher).
--
-- ─── The new semantics ────────────────────────────────────────────────────────────────────
-- Each row now carries a permission_group. A tool is offered iff the caller holds ALL codes of
-- AT LEAST ONE group (AND within a group, OR across groups). A group is one @Tool method's
-- required permission codes, named after that method.
--
-- Derivation rules (applied to facade tools only — see the scope note below):
--   R1  A top-level @Tool method's group = the codes its downstream endpoint requires (merged
--       class + method @PreAuthorize on the serving controller), exactly as derived by V37.
--   R2  A composition's group = the codes of its .require()d legs ONLY. Optional legs
--       contribute nothing: ToolComposition degrades them individually (the section reports
--       its own not_authorized status while the rest still answer), so the caller does not
--       need their permissions to use the tool.
--   R3  A method with no required codes contributes NO group at all. An empty group would make
--       bool_and() vacuously true and admit every caller — never insert one.
--   R4  A tool whose only guard is the AUTHENTICATED sentinel keeps a single group holding it
--       (group name 'AUTHENTICATED' — no single method owns it; it is a class-level fallback).
--
-- ─── Scope ────────────────────────────────────────────────────────────────────────────────
-- AND-group semantics apply to FACADE tools only (source <> 'openapi'), i.e. the two queries
-- ToolMetadataRepositoryImpl.findEnabledByPermissionsAndWorkflow /
-- .findTopKByEmbeddingForPermissions. The ~228 discovered OpenAPI operations
-- (findDiscoveredCandidatesForPermissions) keep OR semantics: their x-required-permissions
-- meaning has not been analysed and silently tightening it is out of scope. That is achieved
-- structurally rather than with a second query shape — every discovered-op row gets its OWN
-- group (permission_group = permission_code), for which "AND within group" is identical to OR.
-- The same convention is used by ToolMetadataRepositoryImpl.addToolPermission (the Gate 3
-- admin grant API): a code granted there forms its own singleton group.
--
-- ─── Backfill ─────────────────────────────────────────────────────────────────────────────
-- Every pre-existing row is backfilled to its OWN group (permission_group = permission_code),
-- which reproduces today's OR behaviour EXACTLY. A literal shared default ('default') would
-- have ANDed every existing tool's codes together and broken every multi-code tool the moment
-- the new query shipped.
--
-- ─── Constraint change ────────────────────────────────────────────────────────────────────
-- The V17 primary key (tool_id, permission_code) cannot express the same code in two groups
-- (crm:party:view belongs to both getCustomer and searchCustomers). It is replaced by
-- (tool_id, permission_group, permission_code). No later migration altered it; V18/V29/V35/
-- V36/V37/V38/V39 only INSERT/DELETE rows. idx_mcp_tool_permission_code (V17) is unchanged.
--
-- ─── H2 twin ──────────────────────────────────────────────────────────────────────────────
-- None, matching V38/V39: the tool-registry tables exist only in the Postgres chain (see the
-- H2 V27 header). Nothing to mirror.
--
-- ═════════════════════════════════════════════════════════════════════════════════════════
-- Group table (tool → group → codes). Endpoint→code evidence is V37's per-method derivation
-- table (folding in V38: TaxFacadeTool.getTaxRate → tax:rates:view; and V39:
-- AccountingFacadeTool.getAgedReceivables/getAgedPayables → reporting:view:financial-statements).
-- Method→endpoint evidence is pos-mcp-server/src/test/resources/facade-contract.yaml.
-- Required-vs-optional leg evidence is the .require(...) calls in each *FacadeTool.java.
-- ═════════════════════════════════════════════════════════════════════════════════════════
--
-- AccountingFacadeTool
--   getAccountBalance    accounting:coa:view
--   getGeneralLedger     reporting:view:financial-statements
--   getFinancialSummary  reporting:view:financial-statements
--                        COMPOSE incomeStatement (.require) + balanceSheet + trialBalance
--                        (optional); all three legs share the same code, so the required leg
--                        alone yields it.                    AccountingFacadeTool:92-118
--   getAgedReceivables   reporting:view:financial-statements  (V39)
--   getAgedPayables      reporting:view:financial-statements  (V39)
--
-- ReportingFacadeTool
--   getSalesReport       reporting:view:financial-statements
--   getInventoryReport   inventory:on_hand:view
--   getRevenueReport     reporting:view:financial-statements
--                        COMPOSE incomeStatement (.require) + agedReceivables (optional);
--                        both legs share the code.            ReportingFacadeTool:73-91
--
-- CatalogFacadeTool
--   getProduct           catalog:product:view
--   searchCatalog        catalog:product:view
--   getCatalogByCategory catalog:product:view
--
-- CustomerFacadeTool   ← the #1606 finding-1 regression
--   getCustomer          crm:party:view
--   searchCustomers      crm:party:view
--   getCustomerHistory   — NO GROUP (R3). ToolComposition "customerHistory" issues four legs
--                        (snapshot, interactions, invoices, workorders) and calls .require()
--                        on NONE of them (CustomerFacadeTool:78-107); every section degrades
--                        individually, as the tool's own description states. So the method
--                        imposes no permission precondition of its own, and V37's union codes
--                        crm:interaction:view / invoice:manage / workorder:workorder:view
--                        drop off the tool entirely — they never gated it IN legitimately,
--                        they only widened it.
--
-- EventsFacadeTool
--   AUTHENTICATED        AUTHENTICATED  (R4 — getEventTypes / getEventSummary / getEventHistory
--                        all hit endpoints with no @PreAuthorize; the class has zero coded
--                        guards. See V37 and V38 headers.)
--
-- HrFacadeTool
--   getEmployee          people:employee:view
--   getEmployeeSchedule  people:availability:view
--   searchEmployees      people:employee:view                 (V38 header: GET /v1/people/employees)
--
-- InventoryFacadeTool
--   checkStock           inventory:availability:read
--   searchInventory      inventory:availability:read
--   getLocationStock     inventory:on_hand:view
--
-- InvoiceFacadeTool
--   getInvoice           invoice:manage
--   searchInvoices       invoice:manage
--   getInvoicesByCustomer invoice:manage
--
-- LocationFacadeTool
--   getLocation          location:read
--   searchLocations      location:read
--   getLocationInventory inventory:on_hand:view
--
-- OrderFacadeTool
--   getOrder             order:order:view
--   listOrders           order:order:view
--
-- PricingFacadeTool
--   getPriceForSku       catalog:product:view
--                        COMPOSE product (.require) + effectivePrice (optional AND only issued
--                        when a locationId argument is supplied) — PricingFacadeTool:70-111.
--                        catalog:location_price_override:read therefore leaves the tool's
--                        gate entirely (R2); it is still enforced downstream on the leg.
--   getPromotionByCode   pricing:promotion:view
--   listPriceRestrictions pricing:rule:view
--   getPriceList         catalog:price_book:read
--
-- ShopManagerFacadeTool
--   getShopStatus        location:read
--                        COMPOSE location (.require) + schedule + openWorkorders (optional)
--                                                              ShopManagerFacadeTool:56-80
--   getShopQueue         workorder:wip:view
--                        COMPOSE openWorkorders (.require) + schedule (optional)
--                                                              ShopManagerFacadeTool:91-108
--   searchShops          location:read
--                        shop:schedule:view leaves the gate (R2): the schedule leg is optional
--                        in both compositions and no top-level method calls it.
--
-- TaxFacadeTool
--   calculateTax         location:read + tax:calculate
--                        COMPOSE location (.require) + tax (.require) — TaxFacadeTool:85-116.
--                        The first true multi-code group: BOTH are needed.
--   getTaxRate           location:read + tax:rates:view
--                        COMPOSE location (.require) + rates (.require) — TaxFacadeTool:136-181
--                        (V38: tax:rates:view, TaxController:239).
--   getTaxSummary        reporting:view:financial-statements
--
-- VehicleFacadeTool
--   getVehicle           vehicle-inventory:registry:view
--   searchVehicles       vehicle-inventory:search:view
--   getVehiclesByCustomer crm:vehicle:view
--
-- WorkorderFacadeTool
--   getWorkorder         workorder:workorder:view
--   searchWorkorders     workorder:workorder:view
--   getWorkorderStatus   workorder:workorder:view
--
-- AdminFacadeTool
--   getSystemStatus      — NO GROUP (R3): local constant response, no HTTP call, no guard.
--   listUsers            security:user:view
--   getUserPermissions   security:permission:view
--   getMyPermissions     security:permission:view
--   getAuditLog          security:audit:view
--
-- Net union change vs. the V37/V38/V39 state (codes that disappear because they only ever
-- reached the tool through an OPTIONAL composition leg — R2):
--   CustomerFacadeTool     − crm:interaction:view, invoice:manage, workorder:workorder:view
--   PricingFacadeTool      − catalog:location_price_override:read
--   ShopManagerFacadeTool  − shop:schedule:view
-- Every other tool's union is unchanged; only its partitioning into groups is new.
--
-- Shape: per tool, DELETE the old rows then INSERT the derived groups — idempotent
-- (ON CONFLICT DO NOTHING) and a no-op for any tool name absent from mcp_tool. V18/V29/V35/
-- V36/V37/V38/V39 are applied migrations and are never edited (README rule); this migration
-- supersedes their net facade state.
-- ═════════════════════════════════════════════════════════════════════════════════════════

-- 1) Schema ───────────────────────────────────────────────────────────────────
ALTER TABLE mcp_tool_permission ADD COLUMN permission_group TEXT;

-- Backfill every existing row to its OWN group: behaviour-preserving (AND-within-a-
-- singleton-group == OR). This is what keeps the ~228 discovered openapi ops on OR semantics.
UPDATE mcp_tool_permission SET permission_group = permission_code WHERE permission_group IS NULL;

ALTER TABLE mcp_tool_permission ALTER COLUMN permission_group SET NOT NULL;

-- The V17 PK (tool_id, permission_code) forbids the same code in two groups. Widen it.
ALTER TABLE mcp_tool_permission DROP CONSTRAINT IF EXISTS mcp_tool_permission_pkey;
ALTER TABLE mcp_tool_permission
    ADD CONSTRAINT mcp_tool_permission_pkey PRIMARY KEY (tool_id, permission_group, permission_code);

-- 2) Facade group seeds ───────────────────────────────────────────────────────

-- AccountingFacadeTool ────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'AccountingFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('getAccountBalance',   'accounting:coa:view'),
    ('getGeneralLedger',    'reporting:view:financial-statements'),
    ('getFinancialSummary', 'reporting:view:financial-statements'),
    ('getAgedReceivables',  'reporting:view:financial-statements'),
    ('getAgedPayables',     'reporting:view:financial-statements')
) AS perms(grp, code)
WHERE mcp_tool.name = 'AccountingFacadeTool'
ON CONFLICT DO NOTHING;

-- ReportingFacadeTool ─────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'ReportingFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('getSalesReport',     'reporting:view:financial-statements'),
    ('getInventoryReport', 'inventory:on_hand:view'),
    ('getRevenueReport',   'reporting:view:financial-statements')
) AS perms(grp, code)
WHERE mcp_tool.name = 'ReportingFacadeTool'
ON CONFLICT DO NOTHING;

-- CatalogFacadeTool ───────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'CatalogFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('getProduct',           'catalog:product:view'),
    ('searchCatalog',        'catalog:product:view'),
    ('getCatalogByCategory', 'catalog:product:view')
) AS perms(grp, code)
WHERE mcp_tool.name = 'CatalogFacadeTool'
ON CONFLICT DO NOTHING;

-- CustomerFacadeTool ──────────────────────────────────────────────────────────
-- getCustomerHistory contributes no group (R3): it .require()s no leg.
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'CustomerFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('getCustomer',     'crm:party:view'),
    ('searchCustomers', 'crm:party:view')
) AS perms(grp, code)
WHERE mcp_tool.name = 'CustomerFacadeTool'
ON CONFLICT DO NOTHING;

-- EventsFacadeTool ────────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'EventsFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('AUTHENTICATED', 'AUTHENTICATED')
) AS perms(grp, code)
WHERE mcp_tool.name = 'EventsFacadeTool'
ON CONFLICT DO NOTHING;

-- HrFacadeTool ────────────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'HrFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('getEmployee',         'people:employee:view'),
    ('getEmployeeSchedule', 'people:availability:view'),
    ('searchEmployees',     'people:employee:view')
) AS perms(grp, code)
WHERE mcp_tool.name = 'HrFacadeTool'
ON CONFLICT DO NOTHING;

-- InventoryFacadeTool ─────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'InventoryFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('checkStock',       'inventory:availability:read'),
    ('searchInventory',  'inventory:availability:read'),
    ('getLocationStock', 'inventory:on_hand:view')
) AS perms(grp, code)
WHERE mcp_tool.name = 'InventoryFacadeTool'
ON CONFLICT DO NOTHING;

-- InvoiceFacadeTool ───────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'InvoiceFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('getInvoice',            'invoice:manage'),
    ('searchInvoices',        'invoice:manage'),
    ('getInvoicesByCustomer', 'invoice:manage')
) AS perms(grp, code)
WHERE mcp_tool.name = 'InvoiceFacadeTool'
ON CONFLICT DO NOTHING;

-- LocationFacadeTool ──────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'LocationFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('getLocation',          'location:read'),
    ('searchLocations',      'location:read'),
    ('getLocationInventory', 'inventory:on_hand:view')
) AS perms(grp, code)
WHERE mcp_tool.name = 'LocationFacadeTool'
ON CONFLICT DO NOTHING;

-- OrderFacadeTool ─────────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'OrderFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('getOrder',   'order:order:view'),
    ('listOrders', 'order:order:view')
) AS perms(grp, code)
WHERE mcp_tool.name = 'OrderFacadeTool'
ON CONFLICT DO NOTHING;

-- PricingFacadeTool ───────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'PricingFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('getPriceForSku',        'catalog:product:view'),
    ('getPromotionByCode',    'pricing:promotion:view'),
    ('listPriceRestrictions', 'pricing:rule:view'),
    ('getPriceList',          'catalog:price_book:read')
) AS perms(grp, code)
WHERE mcp_tool.name = 'PricingFacadeTool'
ON CONFLICT DO NOTHING;

-- ShopManagerFacadeTool ───────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'ShopManagerFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('getShopStatus', 'location:read'),
    ('getShopQueue',  'workorder:wip:view'),
    ('searchShops',   'location:read')
) AS perms(grp, code)
WHERE mcp_tool.name = 'ShopManagerFacadeTool'
ON CONFLICT DO NOTHING;

-- TaxFacadeTool ───────────────────────────────────────────────────────────────
-- calculateTax / getTaxRate are genuine multi-code groups: BOTH legs are .require()d.
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'TaxFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('calculateTax',  'location:read'),
    ('calculateTax',  'tax:calculate'),
    ('getTaxRate',    'location:read'),
    ('getTaxRate',    'tax:rates:view'),
    ('getTaxSummary', 'reporting:view:financial-statements')
) AS perms(grp, code)
WHERE mcp_tool.name = 'TaxFacadeTool'
ON CONFLICT DO NOTHING;

-- VehicleFacadeTool ───────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'VehicleFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('getVehicle',            'vehicle-inventory:registry:view'),
    ('searchVehicles',        'vehicle-inventory:search:view'),
    ('getVehiclesByCustomer', 'crm:vehicle:view')
) AS perms(grp, code)
WHERE mcp_tool.name = 'VehicleFacadeTool'
ON CONFLICT DO NOTHING;

-- WorkorderFacadeTool ─────────────────────────────────────────────────────────
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'WorkorderFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('getWorkorder',       'workorder:workorder:view'),
    ('searchWorkorders',   'workorder:workorder:view'),
    ('getWorkorderStatus', 'workorder:workorder:view')
) AS perms(grp, code)
WHERE mcp_tool.name = 'WorkorderFacadeTool'
ON CONFLICT DO NOTHING;

-- AdminFacadeTool ─────────────────────────────────────────────────────────────
-- getSystemStatus contributes no group (R3): no HTTP call, no guard.
DELETE FROM mcp_tool_permission
WHERE tool_id IN (SELECT id FROM mcp_tool WHERE name = 'AdminFacadeTool');
INSERT INTO mcp_tool_permission (tool_id, permission_group, permission_code)
SELECT id, grp, code
FROM mcp_tool, (VALUES
    ('listUsers',          'security:user:view'),
    ('getUserPermissions', 'security:permission:view'),
    ('getMyPermissions',   'security:permission:view'),
    ('getAuditLog',        'security:audit:view')
) AS perms(grp, code)
WHERE mcp_tool.name = 'AdminFacadeTool'
ON CONFLICT DO NOTHING;
