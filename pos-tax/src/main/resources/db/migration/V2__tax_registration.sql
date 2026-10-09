-- CAP:550 S32c (#2638; ADR-0071 §6-7, ADR-0044 §1 as amended 2026-10-08, AW58/AW59): pos-tax's tenant tax
-- registrations, their history, and pos-tax's first transactional outbox.
--
--   1. tax_registration: one row per registration a tenant holds for one country's regime (S32a profiles). The
--      number is stored only in the normalised form S32b's shape check accepted (at most 32 characters). The
--      jurisdiction is derived from the regime's configuration, never input. A row is never deleted; it is ended by
--      effective_to (inclusive). At most one registration per tenant, country and regime is in effect on any date:
--      the service refuses an overlap with 409 TAX_REGISTRATION_OVERLAP, and the exclusion constraint below is the
--      backstop two concurrent writes cannot pass (SQLSTATE 23P01, translated to the same 409). tenant_id leads it,
--      so one tenant's write can never be refused by, and so learn of, another tenant's row (precedent: pos-shop-
--      manager V8, pos-people's ex_time_period_tenant_no_overlap). Its range is '[]': both ends are inclusive.
--   2. tax_registration_history: old -> new per change, with the actor (the forwarded X-User-Id), the
--      justification and the request id. (tenant_id, request_id) is unique: a replayed request returns the first
--      result instead of writing again.
--   3. event_outbox: tax.registration.changed rows written in the business transaction and drained to
--      tax.events.v1 (ADR-0044 §4). Global like every module's outbox: the unbound poller publishes every tenant's
--      rows and reads the tenant from the row.
--
-- Every scoped table follows TENANCY_SCHEMA.md "Adding a table" (ADR-0062): tenant_id first, the (tenant_id, id)
-- key, every unique led by tenant_id, the tenant index and the tenant_isolation policy. No SQL clock (ADR-0024).

CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

-- 1. tax_registration.
CREATE TABLE public.tax_registration (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    country_code character varying(2) NOT NULL,
    regime character varying(32) NOT NULL,
    registration_number character varying(32) NOT NULL,
    jurisdiction_code character varying(32) NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    version bigint NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    created_by character varying(255) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    updated_by character varying(255) NOT NULL,
    CONSTRAINT tax_registration_dates_check CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

ALTER TABLE ONLY public.tax_registration
    ADD CONSTRAINT tax_registration_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.tax_registration
    ADD CONSTRAINT tax_registration_tenant_key UNIQUE (tenant_id, id);
ALTER TABLE public.tax_registration
    ADD CONSTRAINT tax_registration_no_overlap
    EXCLUDE USING gist (
        tenant_id WITH =,
        country_code WITH =,
        regime WITH =,
        daterange(effective_from, effective_to, '[]') WITH &&
    );
CREATE INDEX tax_registration_tenant_idx ON public.tax_registration USING btree (tenant_id);

COMMENT ON CONSTRAINT tax_registration_no_overlap ON public.tax_registration IS
    'CAP:550 S32c: one registration per tenant, country and regime in effect on any date. Refused with 23P01; the'
    ' service answers 409 TAX_REGISTRATION_OVERLAP.';

ALTER TABLE public.tax_registration ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tax_registration FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.tax_registration
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 2. tax_registration_history.
CREATE TABLE public.tax_registration_history (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    registration_id uuid NOT NULL,
    request_id uuid NOT NULL,
    change_type character varying(16) NOT NULL,
    old_state text,
    new_state text NOT NULL,
    actor character varying(255) NOT NULL,
    justification character varying(1000) NOT NULL,
    recorded_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT tax_registration_history_change_type_check
        CHECK ((change_type)::text = ANY (ARRAY['CREATE'::text, 'UPDATE'::text]))
);

ALTER TABLE ONLY public.tax_registration_history
    ADD CONSTRAINT tax_registration_history_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.tax_registration_history
    ADD CONSTRAINT tax_registration_history_tenant_key UNIQUE (tenant_id, id);
ALTER TABLE ONLY public.tax_registration_history
    ADD CONSTRAINT uq_tax_registration_history_request UNIQUE (tenant_id, request_id);
ALTER TABLE ONLY public.tax_registration_history
    ADD CONSTRAINT fk_tax_registration_history_registration FOREIGN KEY (tenant_id, registration_id)
    REFERENCES public.tax_registration (tenant_id, id);
CREATE INDEX tax_registration_history_tenant_idx ON public.tax_registration_history USING btree (tenant_id);
CREATE INDEX idx_tax_registration_history_registration
    ON public.tax_registration_history USING btree (tenant_id, registration_id);

ALTER TABLE public.tax_registration_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tax_registration_history FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.tax_registration_history
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 3. event_outbox (global; db/tenancy-global-tables.txt).
CREATE TABLE public.event_outbox (
    id uuid NOT NULL,
    -- global table: tenant_id is data, not a discriminator (no policy); the outbox poller runs
    -- unbound and stamps it on the Kafka record header (ADR-0062 section 3)
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    topic character varying(255) NOT NULL,
    record_key character varying(255) NOT NULL,
    payload text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    published_at timestamp(6) with time zone,
    attempts integer DEFAULT 0 NOT NULL,
    last_error text
);

ALTER TABLE ONLY public.event_outbox
    ADD CONSTRAINT event_outbox_pkey PRIMARY KEY (id);
CREATE INDEX idx_event_outbox_unpublished ON public.event_outbox USING btree (id) WHERE (published_at IS NULL);
CREATE INDEX idx_event_outbox_published_window ON public.event_outbox USING btree (topic, created_at)
    WHERE (published_at IS NOT NULL);
