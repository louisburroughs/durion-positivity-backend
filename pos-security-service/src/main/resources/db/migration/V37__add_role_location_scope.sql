-- ADR-0061 §1/§2 (#1868): location scope becomes a property of the ROLE.
--
-- location_scope     ALL       grants reach every location (today's behaviour)
--                    LOCATION  grants reach only the holder's assigned nodes (pos-people's
--                              employee_location_assignment, replicated by V36) and their
--                              descendants along location_hierarchy
-- location_hierarchy FINANCIAL evaluated along the FINANCIAL parent chain (who owns the numbers)
--                    OTHER     evaluated along the union of the seven non-financial parent types
--                              (who runs the site)
--
-- Both default so that a role created later through POST /v1/roles or the bulk loader is ALL /
-- OTHER: ALL preserves current behaviour, and LOCATION only takes effect once an endpoint checks
-- the loc_fin_bits / loc_oth_bits / loc_scope claims (#1870+). Nothing widens by omission.
ALTER TABLE roles
ADD COLUMN IF NOT EXISTS location_scope VARCHAR(16) NOT NULL DEFAULT 'ALL';

ALTER TABLE roles
ADD COLUMN IF NOT EXISTS location_hierarchy VARCHAR(16) NOT NULL DEFAULT 'OTHER';

ALTER TABLE roles
ADD CONSTRAINT chk_roles_location_scope CHECK (location_scope IN ('ALL', 'LOCATION'));

ALTER TABLE roles
ADD CONSTRAINT chk_roles_location_hierarchy CHECK (location_hierarchy IN ('FINANCIAL', 'OTHER'));

-- Seed values for every existing role (ADR-0061 §2 table and 2026-09-07 amendment). Recorded as
-- decisions, not omissions:
--
--   Role                  location_scope  location_hierarchy
--   ADMIN                 ALL             OTHER
--   SYSTEM_ADMINISTRATOR  ALL             OTHER
--   CONTROLLER            ALL             FINANCIAL
--   ACCOUNT_MANAGER       LOCATION        FINANCIAL
--   ACCOUNTANT            LOCATION        FINANCIAL   (named by the ADR; no migration creates it
--                                                      today, so this UPDATE matches nothing
--                                                      until the role exists)
--   GENERAL_MANAGER       LOCATION        FINANCIAL
--   INVENTORY_CONTROLLER  ALL             OTHER       (an inventory role, not an accounting one —
--                                                      never classify by a name match on
--                                                      "CONTROLLER")
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
-- ACCOUNTING_ASSOCIATE, INVENTORY_LEAD and CUSTOMER are not named by the ADR table and keep the
-- column defaults (ALL / OTHER). Narrowing any of them is a product decision, not a default.
--
-- Why a versioned UPDATE rather than values in the repeatable seed: R__seed_reference_security.sql
-- creates only the bootstrap floor (ADMIN, SYSTEM_ADMINISTRATOR — both ALL / OTHER, i.e. the
-- column defaults) with ON CONFLICT (name) DO NOTHING, so a reseed never touches an existing row
-- and cannot reset these columns. The versioned residue (DISPATCHER, SHOP_MANAGER from V3;
-- SELF_SERVICE_CUSTOMER from V8; CONTROLLER from V24) exists before this migration on a fresh
-- database and is set here. Roles provisioned by the bulk loader after startup receive the
-- defaults on a fresh database; see the README role section.
UPDATE roles SET location_scope = 'ALL', location_hierarchy = 'OTHER'
WHERE name IN ('ADMIN', 'SYSTEM_ADMINISTRATOR', 'INVENTORY_CONTROLLER', 'SELF_SERVICE_CUSTOMER');

UPDATE roles SET location_scope = 'ALL', location_hierarchy = 'FINANCIAL'
WHERE name IN ('CONTROLLER');

UPDATE roles SET location_scope = 'LOCATION', location_hierarchy = 'FINANCIAL'
WHERE name IN ('ACCOUNT_MANAGER', 'ACCOUNTANT', 'GENERAL_MANAGER');

UPDATE roles SET location_scope = 'LOCATION', location_hierarchy = 'OTHER'
WHERE name IN ('INVENTORY_MANAGER', 'LOCATION_MANAGER', 'SHOP_MANAGER', 'MANAGER',
               'SERVICE_ADVISOR', 'TECHNICIAN', 'DISPATCHER');
