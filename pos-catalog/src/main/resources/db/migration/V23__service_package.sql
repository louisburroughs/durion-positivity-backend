-- Service packages and fleet requirement sets (#1575 Tier 0 "service packages" and
-- "fleet-specific requirements", docs/SPEC-tier-0-durion-owned-service-data.md T0-4).
--
-- A package is a named set of operations a shop sells together: a four-tyre install with the
-- balance and TPMS reset, a fleet PM interval, a seasonal changeover. Nothing in the platform
-- modelled one — pos-catalog had products, services and no composition between them — so every
-- multi-operation sale was retyped line by line.
--
-- A fleet requirement set is the same shape with fleet_party_id set: "Fleet ACME's units get a
-- DOT brake check and a tread-depth record on every visit". Modelling it as a scoped package
-- rather than its own table is deliberate — it IS a package, it just belongs to one account,
-- and a parallel table would duplicate the membership shape and every query over it.
--
--   package_labor_hours   AUTHORED, not derived (spec D4). The overlap arithmetic that turns
--                         member times into a total lives in pos-workorder's
--                         EstimatedLaborService; re-implementing it here would create a second
--                         answer to one question. A shop also prices a package as a number it
--                         chose ("4-tyre install, 1.2 hr"), not as a rollup of its parts.
--   owner_scope           PLATFORM | SHOP, same pairing rule and rationale as V21.
--   required (member)     What makes a fleet requirement a requirement rather than a suggestion.

SET TIME ZONE 'UTC';

CREATE TABLE service_package (
    id                  uuid PRIMARY KEY,               -- UUID v7
    package_code        varchar(64)  NOT NULL UNIQUE,
    name                varchar(255) NOT NULL,
    description         text,
    owner_scope         varchar(16)  NOT NULL DEFAULT 'PLATFORM',
    owner_location_id   uuid,
    -- Set = this package is one fleet account's requirement set. A bare UUID, not an FK: the
    -- party is mastered in pos-customer and there are no cross-service foreign keys.
    fleet_party_id      uuid,
    package_labor_hours numeric(5,1),
    active              boolean      NOT NULL DEFAULT true,
    effective_from      date,
    effective_to        date,
    version             bigint       NOT NULL DEFAULT 0,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT ck_svc_pkg_owner_scope
        CHECK (owner_scope IN ('PLATFORM', 'SHOP')
               AND ((owner_scope = 'SHOP' AND owner_location_id IS NOT NULL)
                    OR (owner_scope = 'PLATFORM' AND owner_location_id IS NULL))),
    CONSTRAINT ck_svc_pkg_window CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from)
);

-- The two queries a workorder flow actually asks: "what does this location sell" and "what does
-- this fleet require".
CREATE INDEX ix_svc_pkg_owner ON service_package (owner_location_id) WHERE owner_location_id IS NOT NULL;
CREATE INDEX ix_svc_pkg_fleet ON service_package (fleet_party_id) WHERE fleet_party_id IS NOT NULL;

CREATE TABLE service_package_member (
    id            uuid PRIMARY KEY,                     -- UUID v7
    package_id    uuid NOT NULL REFERENCES service_package (id) ON DELETE CASCADE,
    service_id    uuid NOT NULL REFERENCES service (id),
    sequence      int  NOT NULL,
    quantity      numeric(10,2) NOT NULL DEFAULT 1,
    -- False = an upsell the package offers; true = work that is part of the package by
    -- definition, which is what makes a fleet requirement set enforceable rather than advisory.
    required      boolean NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    -- One operation appears at most once in a package. Wanting it twice is a quantity, not a
    -- second membership row, and two rows would make the required flag ambiguous.
    UNIQUE (package_id, service_id),
    CONSTRAINT ck_svc_pkg_member_qty CHECK (quantity > 0)
);

CREATE INDEX ix_svc_pkg_member_package ON service_package_member (package_id, sequence);
CREATE INDEX ix_svc_pkg_member_service ON service_package_member (service_id);
