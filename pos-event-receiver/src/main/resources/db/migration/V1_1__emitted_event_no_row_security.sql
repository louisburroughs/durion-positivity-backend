-- emitted_event is exempt from row-level security (ADR-0062 exception, decided 2026-09-10).
--
-- TimescaleDB refuses both features V2 relies on when the hypertable has row security:
--   "compression cannot be used on table with row security" (validate_hypertable_for_compression)
--   "cannot create continuous aggregate on hypertable with row security" (cagg_validate_query)
-- The telemetry stream keeps compression and the emitted_event_hourly continuous aggregate; tenant
-- isolation for this one table rests on the tenant_id data column, which the application stamps
-- from the bound request on every row and names in every query (EmittedEventRepository).
-- Runs before V2 on a fresh database and out of order on a database that already carries V2
-- (spring.flyway.out-of-order is on for this module); idempotent either way.
DROP POLICY IF EXISTS tenant_isolation ON public.emitted_event;
ALTER TABLE public.emitted_event NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.emitted_event DISABLE ROW LEVEL SECURITY;
-- With no policy, tenant_id is the leading predicate of the entity lookup (GET /v1/events): the
-- partial index gains it, replacing the tenant-less one from the baseline.
DROP INDEX IF EXISTS public.idx_emitted_event_entity_time;
CREATE INDEX IF NOT EXISTS idx_emitted_event_tenant_entity_time
    ON public.emitted_event USING btree (tenant_id, entity_id, published_at DESC)
    WHERE (entity_id IS NOT NULL);
COMMENT ON COLUMN public.emitted_event.tenant_id IS
    'Producing tenant (ADR-0062). Data column, not a policy: TimescaleDB compression and continuous aggregates exclude row security.';
