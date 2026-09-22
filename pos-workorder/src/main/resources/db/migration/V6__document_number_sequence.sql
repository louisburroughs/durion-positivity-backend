-- #2150: counter rows for estimate and workorder numbers.
--
-- Both numbers were assigned by probing EST-YYYY-1000, -1001, ... with an exists query until one
-- was free, then inserting. Nothing held the number between the probe and the insert, so two
-- creates at the same location read the same free number and the second failed the unique
-- constraint (estimate_location_id_estimate_number_key / ux_workorder_workorder_number) as an
-- unmapped 500. The probe also cost one query per number already issued in the scope.
--
-- A number is now handed out from one row per scope, read under a FOR UPDATE lock and advanced
-- inside the transaction that inserts the numbered row, so concurrent creates in a scope
-- serialize on the lock until the winner commits. Scope keys:
--   EST-{year}-{locationId}  estimate numbers, unique per location (as the estimate constraint is)
--   WO-{year}                workorder numbers, unique per tenant
--
-- Rows are created on first use at next_value = 1000, the first number either series ever issued.
-- No backfill: the first allocation in a scope skips past numbers already taken (one pass, under
-- the lock), then records where it stopped.
--
-- ADR-0062: tenant-scoped, under row-level security, every unique constraint leads with tenant_id.

CREATE TABLE public.document_number_sequence (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    scope_key character varying(64) NOT NULL,
    next_value bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT document_number_sequence_next_value_check CHECK (next_value > 0)
);

COMMENT ON TABLE public.document_number_sequence IS
    'Next estimate / workorder number per scope (#2150). Read FOR UPDATE and advanced in the '
    'transaction that inserts the numbered row, so concurrent creates cannot pick the same number.';

ALTER TABLE ONLY public.document_number_sequence
    ADD CONSTRAINT document_number_sequence_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.document_number_sequence
    ADD CONSTRAINT document_number_sequence_tenant_key UNIQUE (tenant_id, id);

ALTER TABLE ONLY public.document_number_sequence
    ADD CONSTRAINT document_number_sequence_scope_key UNIQUE (tenant_id, scope_key);

CREATE INDEX document_number_sequence_tenant_idx
    ON public.document_number_sequence USING btree (tenant_id);

ALTER TABLE public.document_number_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.document_number_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.document_number_sequence
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
