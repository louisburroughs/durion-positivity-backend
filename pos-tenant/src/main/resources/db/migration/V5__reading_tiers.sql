-- CAP:550 S45 (#2619; AW63, AW68): the reading-tier catalog and each tenant's tier. A tier names a monthly
-- document-reading allowance (an amount with its currency, ADR-0067 PC-3); every tenant is on one tier and gets its
-- own share, never a shared pool. Like every registry row the catalog belongs to the platform tenant under RLS.
CREATE TABLE public.reading_tier (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    code character varying(32) NOT NULL,
    display_name character varying(200) NOT NULL,
    monthly_allowance_amount numeric(19,4) NOT NULL,
    monthly_allowance_currency character varying(3) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    version bigint NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT reading_tier_code_shape CHECK (((code)::text ~ '^[A-Z][A-Z0-9_]{1,31}$'::text)),
    CONSTRAINT reading_tier_amount_not_negative CHECK ((monthly_allowance_amount >= (0)::numeric))
);
ALTER TABLE ONLY public.reading_tier ADD CONSTRAINT reading_tier_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.reading_tier ADD CONSTRAINT reading_tier_tenant_key UNIQUE (tenant_id, id);
ALTER TABLE ONLY public.reading_tier ADD CONSTRAINT uq_reading_tier_code UNIQUE (tenant_id, code);
CREATE INDEX reading_tier_tenant_idx ON public.reading_tier USING btree (tenant_id);
ALTER TABLE public.reading_tier ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.reading_tier FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.reading_tier
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- The default tier (AW63): STANDARD, 20.00 USD a month. Seeded under the platform tenant like V2's rows.
SELECT set_config('app.current_tenant', '01900000-0000-7000-8000-000000000000', true);
INSERT INTO public.reading_tier (id, code, display_name, monthly_allowance_amount, monthly_allowance_currency, active, version, created_at, updated_at)
VALUES ('01900000-0000-7000-8000-0000000000c1', 'STANDARD', 'Standard', 20.0000, 'USD', true, 0, now(), now());

-- Every tenant is on a tier; the existing ones start on STANDARD.
ALTER TABLE public.tenant ADD COLUMN reading_tier_code character varying(32);
UPDATE public.tenant SET reading_tier_code = 'STANDARD' WHERE reading_tier_code IS NULL;
ALTER TABLE public.tenant ALTER COLUMN reading_tier_code SET NOT NULL;
CREATE INDEX idx_tenant_reading_tier ON public.tenant USING btree (tenant_id, reading_tier_code);
