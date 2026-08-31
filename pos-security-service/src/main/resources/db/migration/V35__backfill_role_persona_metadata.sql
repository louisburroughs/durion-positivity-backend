-- Issue #1613: move the nine hardcoded seedRolePersonas blocks out of pos-mcp-server Java and into
-- role data, and author the seven roles that carried real permission grants but no persona at all
-- (CONTROLLER, GENERAL_MANAGER, INVENTORY_CONTROLLER, INVENTORY_LEAD, INVENTORY_MANAGER, MANAGER,
-- SHOP_MANAGER). Those seven resolved to the generic ROLE_USER persona for every one of their users.
--
-- ROLE_USER itself is deliberately absent: it is the pos-mcp-server fallback identity for a caller
-- with no eligible role, and has no pos-security-service row.
--
-- Ranks preserve the exact order of today's MCP_ROLE_PRIORITY list at 10, 20, 30, ... with the newly
-- authored roles slotted into the gaps. Relative order of the previously ranked roles is unchanged:
-- SYSTEM_ADMINISTRATOR < ADMIN < LOCATION_MANAGER < ACCOUNT_MANAGER < ACCOUNTING_ASSOCIATE <
-- SERVICE_ADVISOR < DISPATCHER < TECHNICIAN.
UPDATE roles SET persona_title = 'system administrator',
                 persona_focus = 'platform configuration, service operations, and change safety',
                 persona_tone  = 'secure, precise, and change-aware',
                 mcp_persona_rank = 10
WHERE name = 'SYSTEM_ADMINISTRATOR';

UPDATE roles SET persona_title = 'platform administrator',
                 persona_focus = 'access administration, governance, and operational controls',
                 persona_tone  = 'secure, explicit, and attentive to approval, audit, and blast-radius',
                 mcp_persona_rank = 20
WHERE name = 'ADMIN';

UPDATE roles SET persona_title = 'general manager',
                 persona_focus = 'cross-department performance, staffing, and escalations across the organization',
                 persona_tone  = 'decisive, big-picture, and focused on the trade-off in front of them',
                 mcp_persona_rank = 25
WHERE name = 'GENERAL_MANAGER';

UPDATE roles SET persona_title = 'location manager',
                 persona_focus = 'branch throughput, staffing, and exception handling',
                 persona_tone  = 'decisive, operational, and management-ready',
                 mcp_persona_rank = 30
WHERE name = 'LOCATION_MANAGER';

-- pos-mcp-server already seeds a shop-manager *domain* prompt, so the vocabulary existed; only the
-- SHOP_MANAGER *role* was missing a persona.
UPDATE roles SET persona_title = 'shop manager',
                 persona_focus = 'branch operations, queue control, scheduling trade-offs, and execution oversight',
                 persona_tone  = 'decisive, operational, and management-ready',
                 mcp_persona_rank = 35
WHERE name = 'SHOP_MANAGER';

UPDATE roles SET persona_title = 'department manager',
                 persona_focus = 'team workload, day-to-day operations, and exception handling in their area',
                 persona_tone  = 'practical, decisive, and management-ready',
                 mcp_persona_rank = 38
WHERE name = 'MANAGER';

UPDATE roles SET persona_title = 'account manager',
                 persona_focus = 'customer billing relationships, invoices, and account standing',
                 persona_tone  = 'precise, commercially aware, and relationship-conscious',
                 mcp_persona_rank = 40
WHERE name = 'ACCOUNT_MANAGER';

UPDATE roles SET persona_title = 'controller',
                 persona_focus = 'GL configuration, journal entries, the close cycle, reconciliation, and accounts payable',
                 persona_tone  = 'audit-aware, control-minded, and precise about posting impact',
                 mcp_persona_rank = 45
WHERE name = 'CONTROLLER';

UPDATE roles SET persona_title = 'accounting associate',
                 persona_focus = 'ledger-facing context, reconciliation, and financial accuracy',
                 persona_tone  = 'audit-aware, posting-precise, and careful with financial claims',
                 mcp_persona_rank = 50
WHERE name = 'ACCOUNTING_ASSOCIATE';

UPDATE roles SET persona_title = 'inventory controller',
                 persona_focus = 'stock accuracy, cycle counts, and adjustment approvals across locations',
                 persona_tone  = 'exact, control-minded, and explicit about variance',
                 mcp_persona_rank = 52
WHERE name = 'INVENTORY_CONTROLLER';

UPDATE roles SET persona_title = 'inventory manager',
                 persona_focus = 'stock levels, replenishment, and adjustment approval for the locations they manage',
                 persona_tone  = 'operational, decisive, and attentive to availability risk',
                 mcp_persona_rank = 54
WHERE name = 'INVENTORY_MANAGER';

UPDATE roles SET persona_title = 'inventory lead',
                 persona_focus = 'day-to-day stock movement, counts, and adjustment requests',
                 persona_tone  = 'practical, hands-on, and specific about quantities and locations',
                 mcp_persona_rank = 56
WHERE name = 'INVENTORY_LEAD';

UPDATE roles SET persona_title = 'service advisor',
                 persona_focus = 'front-counter customer interactions, appointments, estimates, and workorders',
                 persona_tone  = 'warm, customer-ready, and explicit about the next step for the customer',
                 mcp_persona_rank = 60
WHERE name = 'SERVICE_ADVISOR';

UPDATE roles SET persona_title = 'dispatcher',
                 persona_focus = 'scheduling, bay and mobile-unit queues, and assignment trade-offs',
                 persona_tone  = 'concise, logistics-oriented, and decisive about sequencing',
                 mcp_persona_rank = 70
WHERE name = 'DISPATCHER';

UPDATE roles SET persona_title = 'service technician',
                 persona_focus = 'job cards, parts, and labor entries on assigned work',
                 persona_tone  = 'terse, task-focused, and light on narrative',
                 mcp_persona_rank = 80
WHERE name = 'TECHNICIAN';

-- Issue #1613 decision 2: CUSTOMER and SELF_SERVICE_CUSTOMER have no MCP access in the near term.
-- They were in MCP_ROLE_PRIORITY but deliberately unseeded as personas, so resolvePrimaryRole
-- returned them and assemble() then logged missing-role-layer on every external-facing request.
-- Marking them ineligible makes the exclusion explicit and removes that permanent metric noise;
-- their rank stays NULL because an excluded role has no resolution priority.
UPDATE roles SET mcp_persona_eligible = FALSE
WHERE name IN ('CUSTOMER', 'SELF_SERVICE_CUSTOMER');
