-- pos-tenant: Flyway baseline (module born 2026-09-10 under ADR-0062 section 7, plan WS2a) with the
-- tenancy schema of docs/TENANCY_SCHEMA.md. Every registry row belongs to the platform tenant
-- (pos-tenancy-common PlatformTenant.ID), so RLS protects accounts, contacts, billing profiles and
-- the tenant table itself by the same mechanism as every other module's tables. Global (unscoped)
-- tables: src/main/resources/db/tenancy-global-tables.txt.
--
-- Tenant context: app_current_tenant() reads the session setting app.current_tenant, which the
-- TenantAwareDataSource (pos-tenancy-common) binds per checkout. With nothing bound: NULL -> every
-- scoped table reads as empty and refuses inserts (fail closed).

SET check_function_bodies = false;

CREATE OR REPLACE FUNCTION public.app_current_tenant() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE
    AS $$ SELECT NULLIF(current_setting('app.current_tenant', true), '')::uuid $$;

CREATE TABLE public.account (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    legal_name character varying(200) NOT NULL,
    trading_name character varying(200),
    status character varying(16) NOT NULL,
    tax_id character varying(64),
    home_country character varying(2) NOT NULL,
    home_currency character varying(3) NOT NULL,
    version bigint NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);

CREATE TABLE public.account_contact (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    account_id uuid NOT NULL,
    name character varying(200) NOT NULL,
    role character varying(16) NOT NULL,
    email character varying(320) NOT NULL,
    phone character varying(32),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);

CREATE TABLE public.billing_profile (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    account_id uuid NOT NULL,
    address_line1 character varying(200) NOT NULL,
    address_line2 character varying(200),
    city character varying(100) NOT NULL,
    region character varying(100),
    postal_code character varying(20),
    country character varying(2) NOT NULL,
    payment_terms character varying(32) NOT NULL,
    invoicing_email character varying(320) NOT NULL,
    -- opaque token issued by the payment processor; never a card number (ADR-0062 section 7)
    payment_processor_customer_token character varying(128),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);

-- The master tenant registry. id is the tenant id every other module's tenant_id refers to;
-- tenant_id is the platform tenant that owns the registry row.
CREATE TABLE public.tenant (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    slug character varying(63) NOT NULL,
    display_name character varying(200) NOT NULL,
    status character varying(16) NOT NULL,
    account_id uuid NOT NULL,
    cell character varying(64),
    initial_admin_email character varying(320) NOT NULL,
    version bigint NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    activated_at timestamp(6) with time zone,
    suspended_at timestamp(6) with time zone,
    decommissioned_at timestamp(6) with time zone
);

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
    attempts integer NOT NULL,
    last_error text
);

CREATE TABLE public.processed_events (
    event_id character varying(36) NOT NULL,
    owner character varying(64) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL
);

ALTER TABLE ONLY public.account
    ADD CONSTRAINT account_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.account
    ADD CONSTRAINT account_tenant_key UNIQUE (tenant_id, id);
ALTER TABLE ONLY public.account
    ADD CONSTRAINT account_legal_name_key UNIQUE (tenant_id, legal_name);

ALTER TABLE ONLY public.account_contact
    ADD CONSTRAINT account_contact_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.account_contact
    ADD CONSTRAINT account_contact_tenant_key UNIQUE (tenant_id, id);

ALTER TABLE ONLY public.billing_profile
    ADD CONSTRAINT billing_profile_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.billing_profile
    ADD CONSTRAINT billing_profile_tenant_key UNIQUE (tenant_id, id);
ALTER TABLE ONLY public.billing_profile
    ADD CONSTRAINT billing_profile_account_key UNIQUE (tenant_id, account_id);

ALTER TABLE ONLY public.tenant
    ADD CONSTRAINT tenant_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.tenant
    ADD CONSTRAINT tenant_tenant_key UNIQUE (tenant_id, id);
ALTER TABLE ONLY public.tenant
    ADD CONSTRAINT tenant_slug_key UNIQUE (tenant_id, slug);

ALTER TABLE ONLY public.event_outbox
    ADD CONSTRAINT event_outbox_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.processed_events
    ADD CONSTRAINT processed_events_pkey PRIMARY KEY (event_id);

ALTER TABLE ONLY public.account_contact
    ADD CONSTRAINT fk_account_contact_account FOREIGN KEY (tenant_id, account_id) REFERENCES public.account(tenant_id, id);
ALTER TABLE ONLY public.billing_profile
    ADD CONSTRAINT fk_billing_profile_account FOREIGN KEY (tenant_id, account_id) REFERENCES public.account(tenant_id, id);
ALTER TABLE ONLY public.tenant
    ADD CONSTRAINT fk_tenant_account FOREIGN KEY (tenant_id, account_id) REFERENCES public.account(tenant_id, id);

CREATE INDEX account_tenant_idx ON public.account USING btree (tenant_id);
CREATE INDEX account_contact_tenant_idx ON public.account_contact USING btree (tenant_id);
CREATE INDEX idx_account_contact_account ON public.account_contact USING btree (tenant_id, account_id);
CREATE INDEX billing_profile_tenant_idx ON public.billing_profile USING btree (tenant_id);
CREATE INDEX tenant_tenant_idx ON public.tenant USING btree (tenant_id);
CREATE INDEX idx_tenant_account ON public.tenant USING btree (tenant_id, account_id);
CREATE INDEX idx_tenant_status ON public.tenant USING btree (tenant_id, status);
CREATE INDEX idx_event_outbox_unpublished ON public.event_outbox USING btree (id) WHERE (published_at IS NULL);
CREATE INDEX idx_event_outbox_published_window ON public.event_outbox USING btree (topic, created_at) WHERE (published_at IS NOT NULL);
CREATE INDEX idx_processed_events_owner ON public.processed_events USING btree (owner, event_id);

ALTER TABLE public.account ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.account FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.account
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

ALTER TABLE public.account_contact ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.account_contact FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.account_contact
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

ALTER TABLE public.billing_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.billing_profile FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.billing_profile
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

ALTER TABLE public.tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.tenant
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
