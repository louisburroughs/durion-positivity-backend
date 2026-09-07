-- Shop ownership on labor standards (#1575 Tier 0 "dealer-created labor operations" /
-- "shop-specific pricing rules", SPEC-tier-0-durion-owned-service-data.md T0-2 / D1).
--
-- A shop's own number for an operation is the same shape as a guide's number for it: hours,
-- for a vehicle key, from a source, at a revision. It differs only in who owns it and who may
-- see it, so ownership is two columns here rather than a parallel table.
--
--   owner_scope        PLATFORM (every location resolves it) | SHOP (one location only)
--   owner_location_id  required when SHOP, forbidden when PLATFORM
--
-- Only the *time* is scoped. The operation taxonomy stays global because it is the shared
-- vocabulary vendor codes map onto (ADR-0059 §3), and because scoping the service row would
-- ripple through the broadcast catalog.service.updated fact and two replicas that have no way
-- to filter by location (spec D2).
--
-- ux_sls_active_key MUST gain the owner, or a shop's row for an operation collides with the
-- platform row on the same vehicle key and the shop can never author one. The zero UUID stands
-- in for "platform" the way '' stands in for a wildcard string in the existing key.

ALTER TABLE service_labor_standard ADD COLUMN owner_scope varchar(16) NOT NULL DEFAULT 'PLATFORM';
ALTER TABLE service_labor_standard ADD COLUMN owner_location_id uuid;

-- Same rationale as ck_sls_time_type (V18): seeds and imports write these columns directly, and
-- a value the @Enumerated(STRING) mapping cannot hydrate would 500 every read of the row. The
-- scope/location pairing is enforced here too because a SHOP row with no location resolves for
-- nobody and a PLATFORM row with one is a lie about its reach.
ALTER TABLE service_labor_standard ADD CONSTRAINT ck_sls_owner_scope
    CHECK (owner_scope IN ('PLATFORM', 'SHOP')
           AND ((owner_scope = 'SHOP' AND owner_location_id IS NOT NULL)
                OR (owner_scope = 'PLATFORM' AND owner_location_id IS NULL)));

DROP INDEX ux_sls_active_key;
CREATE UNIQUE INDEX ux_sls_active_key ON service_labor_standard (
    service_id,
    time_type,
    COALESCE(owner_location_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(vehicle_year, ''),
    COALESCE(make, ''),
    COALESCE(model, ''),
    COALESCE(submodel, ''),
    COALESCE(engine_code, '')
) WHERE superseded_at IS NULL;

-- Resolution loads a service's active rows and then filters by owner; this keeps the
-- shop-owned subset cheap to reach as shop authoring grows relative to imported volume.
CREATE INDEX ix_sls_owner ON service_labor_standard (owner_location_id)
    WHERE superseded_at IS NULL AND owner_location_id IS NOT NULL;
