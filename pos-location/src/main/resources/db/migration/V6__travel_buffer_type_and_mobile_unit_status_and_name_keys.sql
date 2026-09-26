-- #2249 / #2252: three rules the API enforces but the schema did not, two of them on columns holding values the
-- API itself refuses.
--
-- travel_buffer_policies.buffer_type: R__seed_location_1_reference.sql seeded its three policies
-- as 'MINUTES', a type TravelBufferPolicyServiceImpl never accepted (FLAT_MINUTES,
-- PERCENTAGE_OF_TRAVEL, DISTANCE_MULTIPLIER). No client could select it or PATCH it back. The seed
-- now writes FLAT_MINUTES, but its ON CONFLICT DO NOTHING leaves rows already seeded untouched, so
-- they are corrected here — 'MINUTES' always meant a flat number of minutes.
--
-- mobile_units.status: PATCH upper-cased whatever it was sent, so {"status": null} stored 'NULL'
-- and any other string was stored as sent. The service now refuses anything but ACTIVE / INACTIVE.
-- A stored value outside those two was never ACTIVE (eligibility matches ACTIVE exactly), so it
-- becomes INACTIVE, with a WARNING naming the unit.
--
-- Each column then gets a CHECK, so a seed or a write path that skips the service fails loudly
-- instead of storing a value the API rejects.
--
-- mobile_units.name: the API treats a unit's name as unique at its base location ignoring case
-- (MOBILE_UNIT_NAME_TAKEN), but the only key was the case-sensitive uq_mobile_units_base_location_name,
-- so two concurrent writes of "Van 7" and "van 7" could both commit past the service's pre-check.
-- uq_mobile_unit_base_location_lower_name enforces it in the database; MobileUnitServiceImpl already
-- maps a violation of it to 409 MOBILE_UNIT_NAME_TAKEN. Rows that already collide once case-folded
-- would fail the index, so the oldest of each group keeps its name and every later one is renamed
-- with the lowest free " DUPn" suffix, with a WARNING naming it -- the rename-the-loser shape of
-- pos-people V8. Where no such collision exists, which is every seed and fixture path, nothing is
-- renamed.
--
-- Seeing every tenant: both tables are under FORCE ROW LEVEL SECURITY and Flyway connects as the
-- owner, which carries a transitional default tenant binding (postgres/init-tenancy.sh). Without
-- lifting FORCE, the updates would reach only that tenant and the CHECKs (which validate the whole
-- heap) would fail on any other tenant's rows. FORCE is dropped for this migration's transaction
-- and restored before commit, as pos-people V8/V9 do.
ALTER TABLE public.travel_buffer_policies NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.mobile_units NO FORCE ROW LEVEL SECURITY;

UPDATE public.travel_buffer_policies SET buffer_type = 'FLAT_MINUTES' WHERE buffer_type = 'MINUTES';

DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT id, tenant_id, status
          FROM public.mobile_units
         WHERE status NOT IN ('ACTIVE', 'INACTIVE')
         ORDER BY id
    LOOP
        RAISE WARNING 'V6: mobile unit % (tenant %) had status "%", which the API does not accept; '
                      'it is now INACTIVE.', rec.id, rec.tenant_id, rec.status;
        UPDATE public.mobile_units SET status = 'INACTIVE' WHERE id = rec.id AND tenant_id = rec.tenant_id;
    END LOOP;
END $$;

DO $$
DECLARE
    rec       RECORD;
    n         INTEGER;
    candidate TEXT;
BEGIN
    FOR rec IN
        SELECT id, tenant_id, base_location_id, name
          FROM (SELECT id, tenant_id, base_location_id, name,
                       row_number() OVER (PARTITION BY tenant_id, base_location_id, lower(name)
                                              ORDER BY created_at NULLS LAST, id) AS rn
                  FROM public.mobile_units
                 WHERE base_location_id IS NOT NULL) ranked
         WHERE rn > 1
         ORDER BY id
    LOOP
        n := 1;
        LOOP
            n := n + 1;
            candidate := left(rec.name, 255 - length(' DUP' || n)) || ' DUP' || n;
            EXIT WHEN NOT EXISTS (SELECT 1
                                    FROM public.mobile_units
                                   WHERE tenant_id = rec.tenant_id
                                     AND base_location_id = rec.base_location_id
                                     AND lower(name) = lower(candidate));
            IF n > 50 THEN
                RAISE EXCEPTION 'V6: could not find a free name for mobile unit % after 50 attempts', rec.id;
            END IF;
        END LOOP;
        RAISE WARNING 'V6: mobile unit % (tenant %) had the name "%", which another unit at its base location '
                      'already holds ignoring case; it is now "%". Rename it through the API.',
                      rec.id, rec.tenant_id, rec.name, candidate;
        UPDATE public.mobile_units SET name = candidate WHERE id = rec.id AND tenant_id = rec.tenant_id;
    END LOOP;
END $$;

CREATE UNIQUE INDEX uq_mobile_unit_base_location_lower_name
    ON public.mobile_units USING btree (tenant_id, base_location_id, lower((name)::text));

ALTER TABLE public.travel_buffer_policies
    ADD CONSTRAINT travel_buffer_policies_buffer_type_check
    CHECK (buffer_type IS NULL OR buffer_type IN ('FLAT_MINUTES', 'PERCENTAGE_OF_TRAVEL', 'DISTANCE_MULTIPLIER'));

ALTER TABLE public.mobile_units
    ADD CONSTRAINT mobile_units_status_check CHECK (status IN ('ACTIVE', 'INACTIVE'));

ALTER TABLE public.travel_buffer_policies FORCE ROW LEVEL SECURITY;
ALTER TABLE public.mobile_units FORCE ROW LEVEL SECURITY;
