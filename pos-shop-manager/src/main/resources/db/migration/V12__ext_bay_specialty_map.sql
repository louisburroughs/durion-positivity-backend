-- #2261 (DECISION-LOCATION-025): a tenant-scoped replica of pos-location's bay-type specialty map,
-- fed by location.bay-specialty-map.updated (full replace per tenant, never a delta), so pos-shop-
-- manager can tell "no bay claims this specialty" apart from "this is general work"
-- (DECISION-SHOPMGMT-021 rule 4) without a synchronous cross-domain read (ADR-0044 §6).
--
-- Two tables, mirroring pos-location's own bay_specialty_operation / bay_specialty_map_version
-- split, folded into one master row per bay type since the map is only ever replaced atomically:
--   - ext_bay_type: one row per (tenant, bay_type), carrying accepts_general_work and the
--     aggregate_version the tenant's whole map was last applied at. Every row from one emission
--     carries the same version, which is what the stale guard reads back.
--   - ext_bay_specialty_map: one row per (tenant, bay_type, operation_code) a bay type is the only
--     one able to perform (CAP-325 D14).
--
-- Both start empty and stay empty until the map arrives for a tenant; an absent row must never be
-- read as "not specialty" versus "not yet published" being conflated in the wrong direction — the
-- replica is silent on the difference by design, same as ext_bay/ext_location before their owner
-- facts arrived (#1658 AC11), and consumers must keep behaving exactly as today (no operation is
-- specialty) until it fills.

CREATE TABLE public.ext_bay_type (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    bay_type character varying(50) NOT NULL,
    accepts_general_work boolean NOT NULL,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_bay_type_pkey PRIMARY KEY (id),
    CONSTRAINT ext_bay_type_tenant_key UNIQUE (tenant_id, id),
    CONSTRAINT uq_ext_bay_type_tenant_bay_type UNIQUE (tenant_id, bay_type)
);

CREATE INDEX ext_bay_type_tenant_idx ON public.ext_bay_type USING btree (tenant_id);

ALTER TABLE public.ext_bay_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_bay_type FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_bay_type
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

CREATE TABLE public.ext_bay_specialty_map (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    bay_type character varying(50) NOT NULL,
    operation_code character varying(64) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_bay_specialty_map_pkey PRIMARY KEY (id),
    CONSTRAINT ext_bay_specialty_map_tenant_key UNIQUE (tenant_id, id),
    CONSTRAINT uq_ext_bay_specialty_map_tenant_bay_op UNIQUE (tenant_id, bay_type, operation_code)
);

CREATE INDEX ext_bay_specialty_map_tenant_idx ON public.ext_bay_specialty_map USING btree (tenant_id);
CREATE INDEX ext_bay_specialty_map_op_idx ON public.ext_bay_specialty_map USING btree (operation_code);

ALTER TABLE public.ext_bay_specialty_map ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_bay_specialty_map FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_bay_specialty_map
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- ext_bay gains accepts_general_work (DECISION-LOCATION-025), read from BayUpdatedV1's additive
-- acceptsGeneralWork field. Defaults true (general bays take general work); an absent/null field on
-- the fact means the publisher predates it, so the consumer keeps whatever is already replicated
-- (LocationEventsListener.mergeAcceptsGeneralWork) rather than reading null as "no".
ALTER TABLE public.ext_bay ADD COLUMN accepts_general_work boolean DEFAULT true NOT NULL;

COMMENT ON COLUMN public.ext_bay.accepts_general_work IS
    'Whether this bay''s type takes general work by default (DECISION-LOCATION-025, #2261); false only for WASH_DETAIL. Mapped from BayUpdatedV1.acceptsGeneralWork; null on the fact keeps the already-replicated value.';

-- The DEFAULT true above applied to every pre-existing row, including WASH_DETAIL bays (bay_type
-- arrived in V2, still present today) -- the one type DECISION-LOCATION-025 says does not take
-- general work. LocationEventsListener.mergeAcceptsGeneralWork keeps whatever is already
-- replicated whenever an older location.bay.updated fact lacks the field, so a wash bay defaulted
-- to true here has no guaranteed future fact to correct it. Backfill it directly, across every
-- tenant: ext_bay is under FORCE ROW LEVEL SECURITY (V1) and Flyway connects as the owner with a
-- transitional default tenant binding, so FORCE is lifted for this transaction's duration and
-- restored before commit, same as V11__staffing_replica_role_canonical.sql.
ALTER TABLE public.ext_bay NO FORCE ROW LEVEL SECURITY;

UPDATE public.ext_bay SET accepts_general_work = false WHERE bay_type = 'WASH_DETAIL';

ALTER TABLE public.ext_bay FORCE ROW LEVEL SECURITY;
