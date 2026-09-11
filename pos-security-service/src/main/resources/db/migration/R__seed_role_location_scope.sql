-- Per-role location_scope / location_hierarchy (ADR-0061 §2 table and its 2026-09-07 amendment,
-- #1868). Recorded as decisions, not omissions. Carried over from the retired V37 when the
-- migration history was flattened (2026-09-09); repeatable so it applies to whichever of these
-- roles exist when it runs, and re-applies when this file changes. Roles provisioned by the bulk
-- loader after startup still receive the column defaults (ALL / OTHER) on a fresh database: the
-- loader carries no scope columns (see the README role section).
--
--   Role                  location_scope  location_hierarchy
--   ADMIN                 ALL             OTHER
--   SYSTEM_ADMINISTRATOR  ALL             OTHER
--   CONTROLLER            ALL             FINANCIAL
--   ACCOUNT_MANAGER       LOCATION        FINANCIAL
--   ACCOUNTANT            LOCATION        FINANCIAL
--   GENERAL_MANAGER       LOCATION        FINANCIAL
--   INVENTORY_CONTROLLER  ALL             OTHER       (an inventory role, never classified by a
--                                                      name match on "CONTROLLER")
--   INVENTORY_MANAGER     LOCATION        OTHER       (the #1373 pair: identical grants, differ
--                                                      by reach)
--   LOCATION_MANAGER      LOCATION        OTHER
--   SHOP_MANAGER          LOCATION        OTHER
--   MANAGER               LOCATION        OTHER
--   SERVICE_ADVISOR       LOCATION        OTHER
--   TECHNICIAN            LOCATION        OTHER
--   DISPATCHER            LOCATION        OTHER
--   SELF_SERVICE_CUSTOMER ALL             OTHER
--
-- ACCOUNTING_ASSOCIATE, INVENTORY_LEAD, CUSTOMER and SUPPORT (the read-only role an operator
-- impersonation token carries, ADR-0062 section 7 / WS2b-4: an impersonation token reads the whole
-- tenant) are not named by the ADR table and keep the column defaults (ALL / OTHER), which
-- R__seed_reference_security.sql sets explicitly for SUPPORT. Narrowing any of them is a product
-- decision, not a default.
SELECT set_config('app.current_tenant', '01900000-0000-7000-8000-000000000001', true);

UPDATE roles SET location_scope = 'ALL', location_hierarchy = 'OTHER'
WHERE name IN ('ADMIN', 'SYSTEM_ADMINISTRATOR', 'INVENTORY_CONTROLLER', 'SELF_SERVICE_CUSTOMER');

UPDATE roles SET location_scope = 'ALL', location_hierarchy = 'FINANCIAL'
WHERE name IN ('CONTROLLER');

UPDATE roles SET location_scope = 'LOCATION', location_hierarchy = 'FINANCIAL'
WHERE name IN ('ACCOUNT_MANAGER', 'ACCOUNTANT', 'GENERAL_MANAGER');

UPDATE roles SET location_scope = 'LOCATION', location_hierarchy = 'OTHER'
WHERE name IN ('INVENTORY_MANAGER', 'LOCATION_MANAGER', 'SHOP_MANAGER', 'MANAGER',
               'SERVICE_ADVISOR', 'TECHNICIAN', 'DISPATCHER');
