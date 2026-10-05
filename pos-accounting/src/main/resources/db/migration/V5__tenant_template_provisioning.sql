-- #2526 (CAP:550 S37; ADR-0062 §6-§7; SPEC-accounting-workspace §7.1 "Seed", §10 AW30): the record
-- pos-accounting keeps of what it applied to each tenant from the accounting template.
--
-- The template itself is data in the platform tenant (R__seed_reference_accounting.sql). These two
-- tables are per tenant: one state row, and one row per template entry saying what happened to it
-- in that tenant. AccountingTemplateApplier is their only writer.
--
-- Both carry the full tenancy schema of TENANCY_SCHEMA.md "Adding a table": tenant_id first, the
-- tenant_isolation policy, the tenant index, the (tenant_id, <pk>) key, and every unique constraint
-- led by tenant_id. pos_app receives its privileges through the default privileges
-- postgres/init-tenancy.sh sets for tables the owner creates later.

-- 1. One row per tenant. The applier locks it (SELECT ... FOR UPDATE) for the length of a run, so
--    the tenant.created listener, the startup sweep and a second instance never apply at once.
CREATE TABLE public.accounting_template_state (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    state_id uuid NOT NULL,
    template_fingerprint character varying(64),
    last_applied_at timestamp(6) with time zone,
    created_count integer DEFAULT 0 NOT NULL,
    adopted_count integer DEFAULT 0 NOT NULL,
    refreshed_count integer DEFAULT 0 NOT NULL,
    conflict_count integer DEFAULT 0 NOT NULL,
    withheld_count integer DEFAULT 0 NOT NULL,
    version integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    modified_at timestamp(6) with time zone NOT NULL
);

COMMENT ON TABLE public.accounting_template_state IS
    'What the accounting template applier last did for a tenant (#2526): the fingerprint of the '
    'template it applied, when, and how many entries ended in each outcome. One row per tenant; '
    'the applier locks it for the length of a run.';

ALTER TABLE ONLY public.accounting_template_state
    ADD CONSTRAINT accounting_template_state_pkey PRIMARY KEY (state_id);

ALTER TABLE ONLY public.accounting_template_state
    ADD CONSTRAINT accounting_template_state_tenant_key UNIQUE (tenant_id, state_id);

ALTER TABLE ONLY public.accounting_template_state
    ADD CONSTRAINT uq_accounting_template_state_tenant UNIQUE (tenant_id);

CREATE INDEX accounting_template_state_tenant_idx ON public.accounting_template_state USING btree (tenant_id);

ALTER TABLE public.accounting_template_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.accounting_template_state FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.accounting_template_state
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 2. One row per template entry per tenant. entry_key is the applier's natural key
--    (ACCOUNT:<code>, GL_MAPPING:<category>/<key>, ...). template_value and tenant_value are
--    business text for the status read, never ids. target_row_id is the tenant row the entry was
--    created as or adopted to; it is a plain uuid, not a foreign key, because it points into six
--    different tables and a tenant may later delete the row it names.
CREATE TABLE public.accounting_template_entry (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    entry_id uuid NOT NULL,
    entry_key character varying(300) NOT NULL,
    kind character varying(30) NOT NULL,
    outcome character varying(20) NOT NULL,
    reason character varying(30),
    entry_fingerprint character varying(64) NOT NULL,
    target_row_id uuid,
    template_value text NOT NULL,
    tenant_value text,
    version integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    modified_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT accounting_template_entry_kind_check
        CHECK ((kind)::text = ANY (ARRAY['ACCOUNT'::text, 'CATEGORY'::text, 'MAPPING_KEY'::text, 'GL_MAPPING'::text, 'DEFAULT_GL_MAPPING'::text, 'STATEMENT_LINE'::text])),
    CONSTRAINT accounting_template_entry_outcome_check
        CHECK ((outcome)::text = ANY (ARRAY['CREATED'::text, 'ADOPTED'::text, 'REFRESHED'::text, 'CONFLICT'::text, 'WITHHELD'::text])),
    CONSTRAINT accounting_template_entry_reason_check
        CHECK (reason IS NULL OR (reason)::text = ANY (ARRAY['ACCOUNT_DIFFERS'::text, 'ACCOUNT_INACTIVE'::text, 'ACCOUNT_MISSING'::text, 'DEPENDS_ON_CONFLICT'::text]))
);

COMMENT ON TABLE public.accounting_template_entry IS
    'What happened to one accounting template entry in one tenant (#2526): created, adopted, '
    'refreshed, in conflict or withheld. A CREATED or ADOPTED entry is never applied again; a '
    'CONFLICT or WITHHELD entry is looked at again on every run.';

ALTER TABLE ONLY public.accounting_template_entry
    ADD CONSTRAINT accounting_template_entry_pkey PRIMARY KEY (entry_id);

ALTER TABLE ONLY public.accounting_template_entry
    ADD CONSTRAINT accounting_template_entry_tenant_key UNIQUE (tenant_id, entry_id);

ALTER TABLE ONLY public.accounting_template_entry
    ADD CONSTRAINT uq_accounting_template_entry_key UNIQUE (tenant_id, entry_key);

CREATE INDEX accounting_template_entry_tenant_idx ON public.accounting_template_entry USING btree (tenant_id);

-- The status read lists the entries that need attention.
CREATE INDEX accounting_template_entry_outcome_idx ON public.accounting_template_entry USING btree (tenant_id, outcome);

ALTER TABLE public.accounting_template_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.accounting_template_entry FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.accounting_template_entry
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 3. The alpha default tenant keeps the retread-plant add-on (AW30): V2__seed_accounting.sql gave
--    it those accounts and Labor & Overhead lines, so its choice is recorded as made. Every other
--    tenant starts without the row, and absent means off. Bound to the default tenant as V2 is; the
--    binding is transaction-local. Literal id and timestamps, as in V2 (ADR-0024: no SQL clock).
SELECT set_config('app.current_tenant', '01900000-0000-7000-8000-000000000001', true);

INSERT INTO public.accounting_configuration (config_id, config_key, config_value, created_at, created_by, modified_at, modified_by)
VALUES ('01999999-2526-7000-8000-000000000001', 'RETREAD_PLANT_ADD_ON', 'true', '2026-10-05 00:00:00+00', 'tenant-template', '2026-10-05 00:00:00+00', 'tenant-template')
ON CONFLICT (tenant_id, config_key) DO NOTHING;
