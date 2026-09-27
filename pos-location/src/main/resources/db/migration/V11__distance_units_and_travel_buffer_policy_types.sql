-- #2266 (DECISION-LOCATION-028, supersedes DECISION-LOCATION-016; DECISION-LOCATION-015): distances
-- are configurable per location in miles or kilometres, canonical storage stays kilometres, and the
-- travel-buffer policy type set collapses to FIXED_MINUTES and DISTANCE_TIER.
--
-- location.distance_unit: the unit a location's forms show and accept for a distance value. Every
-- existing location defaults to KM (the prior implicit behaviour, DECISION-LOCATION-016); alpha's US
-- locations are reseeded MI by the bulk loader fixture (scripts/fixtures/seed/alpha/location/
-- locations.csv), not by this migration, since this migration has no notion of which tenant an alpha
-- row belongs to.
--
-- mobile_unit_coverage_rules.max_distance -> max_distance_km: a rename only. The column was already
-- documented, and treated by the API, as kilometres; only the alpha CSV fixture's authored figures
-- are reinterpreted as miles and converted at load time (mobile-unit-coverage-rules.csv gains a
-- `unit` column), not the rows already stored here.
--
-- travel_buffer_policies.buffer_type: FLAT_MINUTES is renamed to FIXED_MINUTES (DECISION-LOCATION-015
-- already named this the v1 schema; the code used FLAT_MINUTES instead). PERCENTAGE_OF_TRAVEL and
-- DISTANCE_MULTIPLIER, which no decision defines and which need routed travel time no service
-- provides, become FIXED_MINUTES with a zero-minute buffer_value, one RAISE WARNING per row naming
-- the policy so an operator can pick a real minutes value. The V6 CHECK
-- (travel_buffer_policies_buffer_type_check) is dropped before either UPDATE: it does not admit
-- FIXED_MINUTES, so on a database that holds a FLAT_MINUTES policy (every one seeded by
-- R__seed_location_1_reference before #2266, alpha included) the rename would violate it and the whole
-- migration would roll back. The new CHECK lands once the rows carry the new names.
--
-- travel_buffer_policies is under FORCE ROW LEVEL SECURITY and Flyway connects as the owner with a
-- transitional default tenant binding (postgres/init-tenancy.sh); FORCE is lifted for this
-- migration's transaction so the UPDATE reaches every tenant's rows, and restored before commit
-- (V6, V9, pos-shop-manager V11, pos-people V8/V9 pattern).
ALTER TABLE public.location ADD COLUMN distance_unit character varying(2) NOT NULL DEFAULT 'KM';
ALTER TABLE public.location
    ADD CONSTRAINT location_distance_unit_check
    CHECK (distance_unit IN ('KM', 'MI'));

ALTER TABLE public.mobile_unit_coverage_rules RENAME COLUMN max_distance TO max_distance_km;

ALTER TABLE public.travel_buffer_policies NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.travel_buffer_policies DROP CONSTRAINT travel_buffer_policies_buffer_type_check;

UPDATE public.travel_buffer_policies SET buffer_type = 'FIXED_MINUTES' WHERE buffer_type = 'FLAT_MINUTES';

DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT id, tenant_id, name, buffer_type
          FROM public.travel_buffer_policies
         WHERE buffer_type IN ('PERCENTAGE_OF_TRAVEL', 'DISTANCE_MULTIPLIER')
         ORDER BY id
    LOOP
        RAISE WARNING 'V11: travel buffer policy % (tenant %, "%") had buffer_type "%", which '
                      'DECISION-LOCATION-028 removes; it is now FIXED_MINUTES with a 0-minute buffer. '
                      'Set a real minutes value through the API.',
                      rec.id, rec.tenant_id, rec.name, rec.buffer_type;
        UPDATE public.travel_buffer_policies
           SET buffer_type = 'FIXED_MINUTES', buffer_value = 0
         WHERE id = rec.id AND tenant_id = rec.tenant_id;
    END LOOP;
END $$;

ALTER TABLE public.travel_buffer_policies
    ADD CONSTRAINT travel_buffer_policies_buffer_type_check
    CHECK (buffer_type IS NULL OR buffer_type IN ('FIXED_MINUTES', 'DISTANCE_TIER'));

ALTER TABLE public.travel_buffer_policies FORCE ROW LEVEL SECURITY;
