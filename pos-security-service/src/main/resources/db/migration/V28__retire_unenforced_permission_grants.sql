-- Task 5 of docs/rbac-permission-role-audit-2026-08.md (§3/§5/§7): retire the
-- grants for 34 permission codes that are seeded but enforced by no endpoint
-- or capability check anywhere in the codebase (verified by investigation,
-- not just script output). This is the retirement wave, not the enforcement
-- wave: only the *grants* retire. The permission-definition rows, their
-- PermissionCode bit indexes and their permissions.yaml manifest entries all
-- stay untouched -- bit indexes are permanent per the PermissionCode javadoc
-- and the §4 retirement convention; V25 is the precedent for a versioned,
-- name-resolved grant-only revoke against the additive repeatable seed.
--
-- Live successor for each family (see R__seed_role_permissions.sql's matching
-- POLICY bullet and §3 of the audit doc for the full narrative):
--   inventory:purchase_order:view/create/approve -> order:purchase_order:*
--     (pos-order PurchaseOrderController)
--   inventory:purchase_order:receive             -> inventory:goods_receipt:create /
--                                                    inventory:receiving:complete
--   inventory:on_hand:search                     -> inventory:availability:read (ADR-0057)
--   workorder:invoice:create                     -> workorder:workorder:generate_invoice
--   order:line:view                              -> order:order:view (lines are
--                                                    embedded in the order response)
--   crm:contact:create/edit/delete                -> people-contact:person:edit
--                                                    (contact points live in
--                                                    pos-people-contact)
--   crm:contact_role:view/revoke                  -> crm:contact:view /
--                                                    crm:contact_role:assign (roles
--                                                    are inline + full-set replace)
--   crm:vehicle:search                            -> vehicle-inventory search
--                                                    (pos-vehicle-inventory
--                                                    VehicleSearchController;
--                                                    enforcement itself pending)
--   crm:vehicle_party_association:*               -> event-driven only
--                                                    (VehicleEventsListener,
--                                                    ADR-0044 §6; no API)
--   crm:vehicle_preference:*                       -> vehicle-inventory:preferences:manage
--   catalog:category:* / catalog:variant:*         -> no such resource exists in
--                                                    pos-catalog (Category is
--                                                    internal validation data;
--                                                    variants/tread designs are
--                                                    Kafka-written)
--   catalog:supplier_cost:write                    -> Kafka-ingest only (the read
--                                                    side catalog:supplier_cost:read
--                                                    stays enforced and granted)
--   pricing:price_book:*                           -> no PriceBook resource exists
--                                                    in pos-price
--   pricing:rule:create/delete + pricing:restrictions:edit
--                                                   -> pricing:restriction:manage
--                                                    (singular)
--   pricing:rule:edit                              -> no edit endpoint exists
--
-- Not scoped to a single role: several of these codes are held beyond ADMIN
-- (GENERAL_MANAGER, INVENTORY_CONTROLLER, INVENTORY_LEAD, INVENTORY_MANAGER,
-- LOCATION_MANAGER, SERVICE_ADVISOR), so the DELETE below removes every
-- role_permissions row for these 34 codes, for any role that holds one --
-- mirroring V27's shape. The corresponding grant rows and section-4
-- referenced-names entries are removed from R__seed_role_permissions.sql in
-- the same change so re-seeding a fresh database matches.
DELETE FROM role_permissions
WHERE permission_id IN (
    SELECT id FROM permissions WHERE name IN (
        'inventory:on_hand:search',
        'inventory:purchase_order:view',
        'inventory:purchase_order:create',
        'inventory:purchase_order:approve',
        'inventory:purchase_order:receive',
        'workorder:invoice:create',
        'order:line:view',
        'crm:contact:create',
        'crm:contact:edit',
        'crm:contact:delete',
        'crm:contact_role:view',
        'crm:contact_role:revoke',
        'crm:vehicle:search',
        'crm:vehicle_party_association:view',
        'crm:vehicle_party_association:create',
        'crm:vehicle_party_association:edit',
        'crm:vehicle_preference:view',
        'crm:vehicle_preference:edit',
        'catalog:category:view',
        'catalog:category:create',
        'catalog:category:edit',
        'catalog:category:delete',
        'catalog:variant:view',
        'catalog:variant:create',
        'catalog:variant:edit',
        'catalog:supplier_cost:write',
        'pricing:price_book:view',
        'pricing:price_book:create',
        'pricing:price_book:edit',
        'pricing:price_book:delete',
        'pricing:rule:create',
        'pricing:rule:edit',
        'pricing:rule:delete',
        'pricing:restrictions:edit'
    )
);
