-- #2249 / #2252: two columns the API constrains but the schema did not, each holding values the
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

ALTER TABLE public.travel_buffer_policies
    ADD CONSTRAINT travel_buffer_policies_buffer_type_check
    CHECK (buffer_type IS NULL OR buffer_type IN ('FLAT_MINUTES', 'PERCENTAGE_OF_TRAVEL', 'DISTANCE_MULTIPLIER'));

ALTER TABLE public.mobile_units
    ADD CONSTRAINT mobile_units_status_check CHECK (status IN ('ACTIVE', 'INACTIVE'));

ALTER TABLE public.travel_buffer_policies FORCE ROW LEVEL SECURITY;
ALTER TABLE public.mobile_units FORCE ROW LEVEL SECURITY;
