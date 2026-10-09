-- CAP:550 S32d (#2639; SPEC-accounting-workspace §4.6, §4.7, §9.5a; AW20, AW49-AW54): input-tax recovery, typed
-- output tax and the currency-conditional template data.
--
-- Multi-national first (platform owner, 2026-10-08): recovery is keyed by regime, output tax by tax type, and the
-- accounts come from mapping keys a currency's template data provisions. No country, regime, tax type or account
-- number is named here except as template DATA in R__seed_reference_accounting.sql. Nothing is backfilled: a row
-- written before S32d keeps its meaning (an untyped invoice tax row, a bill without tax by type).
--
-- New tables follow TENANCY_SCHEMA.md "Adding a table" (ADR-0062): tenant_id first, the (tenant_id, <pk>) key, the
-- tenant index and the tenant_isolation policy. No SQL clock (ADR-0024).

-- 1. A recoverable-tax account subtype (1250, 1260 in the CAD data), beside S15's CASH_ON_HAND.
ALTER TABLE public.gl_account DROP CONSTRAINT gl_account_account_subtype_check;
ALTER TABLE public.gl_account ADD CONSTRAINT gl_account_account_subtype_check CHECK (((account_subtype)::text = ANY (ARRAY[
    'RECEIVABLE'::text, 'PAYABLE'::text, 'BANK_CASH'::text, 'UNDEPOSITED_FUNDS'::text, 'TAX_PAYABLE'::text,
    'CURRENT_ASSET'::text, 'FIXED_ASSET'::text, 'CURRENT_LIABILITY'::text, 'SALES'::text, 'COST_OF_SALES'::text,
    'OPERATING_EXPENSE'::text, 'OTHER'::text, 'CASH_ON_HAND'::text, 'TAX_RECOVERABLE'::text])));

-- 2. A category's tax recovery joins the template's entry kinds.
ALTER TABLE public.accounting_template_entry DROP CONSTRAINT accounting_template_entry_kind_check;
ALTER TABLE public.accounting_template_entry ADD CONSTRAINT accounting_template_entry_kind_check CHECK ((kind)::text = ANY (ARRAY[
    'ACCOUNT'::text, 'CATEGORY'::text, 'MAPPING_KEY'::text, 'GL_MAPPING'::text, 'DEFAULT_GL_MAPPING'::text,
    'STATEMENT_LINE'::text, 'PETTY_EXPENSE_CATEGORY'::text, 'PETTY_EXPENSE_TAX_RECOVERY'::text]));

-- 3. Currency-conditional template data (item 3): which template entries a tenant receives only when its functional
--    currency is this one. Rows exist in the platform tenant only, written by R__seed_reference_accounting.sql beside
--    the entries they name; CurrencyTemplateSource reads them. An entry named here belongs to no other source.
CREATE TABLE public.accounting_template_currency_entry (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    currency_entry_id uuid NOT NULL,
    entry_key character varying(200) NOT NULL,
    currency_code character varying(3) NOT NULL,
    CONSTRAINT accounting_template_currency_entry_currency_check CHECK ((currency_code)::text ~ '^[A-Z]{3}$')
);

COMMENT ON TABLE public.accounting_template_currency_entry IS
    'Template data (platform tenant only): a template entry key a tenant receives only when its functional currency '
    'is currency_code (CAP:550 S32d item 3).';

ALTER TABLE ONLY public.accounting_template_currency_entry
    ADD CONSTRAINT accounting_template_currency_entry_pkey PRIMARY KEY (currency_entry_id);
ALTER TABLE ONLY public.accounting_template_currency_entry
    ADD CONSTRAINT accounting_template_currency_entry_tenant_key UNIQUE (tenant_id, currency_entry_id);
ALTER TABLE ONLY public.accounting_template_currency_entry
    ADD CONSTRAINT uq_accounting_template_currency_entry_key UNIQUE (tenant_id, entry_key);
CREATE INDEX accounting_template_currency_entry_tenant_idx
    ON public.accounting_template_currency_entry USING btree (tenant_id);

ALTER TABLE public.accounting_template_currency_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.accounting_template_currency_entry FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.accounting_template_currency_entry
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 4a. A petty-expense category's tax recovery (item 4): whether the tax stated on its receipts is recovered, and
--     which share. One row per category; a category without a row is not recoverable. The current value; the share
--     a posting uses is the one in force when the movement was recorded, from 4b (AW52).
CREATE TABLE public.petty_expense_category_tax_setting (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    tax_setting_id uuid NOT NULL,
    petty_expense_category_id uuid NOT NULL,
    code character varying(40) NOT NULL,
    tax_recoverable boolean NOT NULL,
    recoverable_percent numeric(5,2),
    version integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    created_by character varying(50) NOT NULL,
    modified_at timestamp(6) with time zone NOT NULL,
    modified_by character varying(50) NOT NULL,
    CONSTRAINT petty_expense_category_tax_setting_percent_check CHECK (
        (tax_recoverable AND recoverable_percent > 0 AND recoverable_percent <= 100)
        OR (NOT tax_recoverable AND recoverable_percent IS NULL))
);

ALTER TABLE ONLY public.petty_expense_category_tax_setting
    ADD CONSTRAINT petty_expense_category_tax_setting_pkey PRIMARY KEY (tax_setting_id);
ALTER TABLE ONLY public.petty_expense_category_tax_setting
    ADD CONSTRAINT petty_expense_category_tax_setting_tenant_key UNIQUE (tenant_id, tax_setting_id);
ALTER TABLE ONLY public.petty_expense_category_tax_setting
    ADD CONSTRAINT uq_petty_expense_category_tax_setting_code UNIQUE (tenant_id, code);
ALTER TABLE ONLY public.petty_expense_category_tax_setting
    ADD CONSTRAINT petty_expense_category_tax_setting_category_fk FOREIGN KEY (tenant_id, petty_expense_category_id)
        REFERENCES public.petty_expense_category(tenant_id, petty_expense_category_id);
CREATE INDEX petty_expense_category_tax_setting_tenant_idx
    ON public.petty_expense_category_tax_setting USING btree (tenant_id);

ALTER TABLE public.petty_expense_category_tax_setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.petty_expense_category_tax_setting FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.petty_expense_category_tax_setting
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 4b. Its history: one row per change, in force from effective_from (the template's row from 2020-01-01, so it
--     covers every movement), never updated or deleted. A requestId names one command: a replay finds the first
--     result.
CREATE TABLE public.petty_expense_category_tax_setting_change (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    change_id uuid NOT NULL,
    petty_expense_category_id uuid NOT NULL,
    code character varying(40) NOT NULL,
    effective_from timestamp(6) with time zone NOT NULL,
    old_tax_recoverable boolean,
    old_recoverable_percent numeric(5,2),
    new_tax_recoverable boolean NOT NULL,
    new_recoverable_percent numeric(5,2),
    actor character varying(50) NOT NULL,
    actor_role character varying(50),
    justification character varying(1000) NOT NULL,
    request_id uuid,
    request_hash character varying(64),
    response_json text,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT petty_expense_category_tax_setting_change_percent_check CHECK (
        (new_tax_recoverable AND new_recoverable_percent > 0 AND new_recoverable_percent <= 100)
        OR (NOT new_tax_recoverable AND new_recoverable_percent IS NULL))
);

ALTER TABLE ONLY public.petty_expense_category_tax_setting_change
    ADD CONSTRAINT petty_expense_category_tax_setting_change_pkey PRIMARY KEY (change_id);
ALTER TABLE ONLY public.petty_expense_category_tax_setting_change
    ADD CONSTRAINT petty_expense_category_tax_setting_change_tenant_key UNIQUE (tenant_id, change_id);
ALTER TABLE ONLY public.petty_expense_category_tax_setting_change
    ADD CONSTRAINT uq_petty_expense_category_tax_setting_change_request UNIQUE (tenant_id, request_id);
ALTER TABLE ONLY public.petty_expense_category_tax_setting_change
    ADD CONSTRAINT petty_expense_category_tax_setting_change_category_fk
        FOREIGN KEY (tenant_id, petty_expense_category_id)
        REFERENCES public.petty_expense_category(tenant_id, petty_expense_category_id);
CREATE INDEX petty_expense_category_tax_setting_change_tenant_idx
    ON public.petty_expense_category_tax_setting_change USING btree (tenant_id);
CREATE INDEX petty_expense_category_tax_setting_change_code_idx
    ON public.petty_expense_category_tax_setting_change USING btree (tenant_id, code, effective_from);

ALTER TABLE public.petty_expense_category_tax_setting_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.petty_expense_category_tax_setting_change FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.petty_expense_category_tax_setting_change
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 5. Per-regime recovery of a petty expense posted at close (item 9): what was stated, what was recovered and why
--    nothing was, with the supplier's number as the claim's evidence (INTERNAL under ADR-0072 Decision 1; never
--    logged). One row per movement and stated regime, written with the movement's entry.
CREATE TABLE public.register_cash_movement_tax_recovery (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    tax_recovery_id uuid NOT NULL,
    movement_id uuid NOT NULL,
    session_id uuid NOT NULL,
    journal_entry_id uuid NOT NULL,
    regime character varying(32) NOT NULL,
    stated_amount numeric(19,4) NOT NULL,
    recoverable_percent numeric(5,2),
    recovered_amount numeric(19,4) NOT NULL,
    recovery_withheld_reason character varying(40),
    supplier_registration_number character varying(32),
    currency_code character varying(3) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT register_cash_movement_tax_recovery_amounts_check CHECK (
        stated_amount > 0 AND recovered_amount >= 0 AND recovered_amount <= stated_amount),
    CONSTRAINT register_cash_movement_tax_recovery_reason_check CHECK (
        (recovery_withheld_reason IS NULL AND recovered_amount > 0)
        OR ((recovery_withheld_reason)::text = ANY (ARRAY['NOT_REGISTERED'::text, 'RATE_UNAVAILABLE'::text,
            'EVIDENCE_MISSING'::text, 'SUPPLIER_REGISTRATION_MISSING'::text]) AND recovered_amount = 0))
);

ALTER TABLE ONLY public.register_cash_movement_tax_recovery
    ADD CONSTRAINT register_cash_movement_tax_recovery_pkey PRIMARY KEY (tax_recovery_id);
ALTER TABLE ONLY public.register_cash_movement_tax_recovery
    ADD CONSTRAINT register_cash_movement_tax_recovery_tenant_key UNIQUE (tenant_id, tax_recovery_id);
ALTER TABLE ONLY public.register_cash_movement_tax_recovery
    ADD CONSTRAINT uq_register_cash_movement_tax_recovery UNIQUE (tenant_id, movement_id, regime);
CREATE INDEX register_cash_movement_tax_recovery_tenant_idx
    ON public.register_cash_movement_tax_recovery USING btree (tenant_id);

ALTER TABLE public.register_cash_movement_tax_recovery ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.register_cash_movement_tax_recovery FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.register_cash_movement_tax_recovery
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 6. Output tax by type (item 11): pos-tax's tax type on each invoice tax row (S32a TaxBreakdownLine.taxType). Null
--    on a row written before S32d or on a fact without it.
ALTER TABLE public.ext_invoice_tax ADD COLUMN tax_type character varying(32);
