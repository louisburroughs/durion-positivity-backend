-- CAP:550 S32d (#2639; SPEC-accounting-workspace §4.6, §4.7; AW31 as re-confirmed by Order on 2026-10-08): the tax
-- a petty-expense receipt states, per indirect-tax regime, with the supplier and pos-tax's plausibility answer.
--
--   1. cash_movement gains the supplier's name (CONFIDENTIAL), the supplier's registration number (normalised, stored
--      only after pos-tax found it well formed; INTERNAL under ADR-0072 Decision 1), pos-tax's plausibility outcome
--      and whether an evidence rule asked for the number. All are null on rows recorded before this story and on
--      every non-petty movement; there is no backfill. The well-formed flag is not stored.
--   2. cash_movement_stated_tax: one row per regime stated on a movement, a child table rather than JSON so the
--      database enforces each regime at most once per movement and a positive amount.
--   3. ext_accounting_petty_expense_category gains the category's recovery flag and share from
--      accounting.petty-expense-category.changed. pos-order decides only on tax_recoverable, to offer regimes;
--      recoverable_percent is for display (AW52). A copy written before this story is not recoverable.
--
-- The new table follows TENANCY_SCHEMA.md "Adding a table" (ADR-0062): tenant_id first, the (tenant_id, <pk>) key,
-- every unique led by tenant_id, the tenant index and the tenant_isolation policy. No SQL clock (ADR-0024); ids are
-- UUIDv7 from the application (ADR-0013).

-- 1. cash_movement.
ALTER TABLE public.cash_movement
    ADD COLUMN supplier_name character varying(200),
    ADD COLUMN supplier_registration_number character varying(32),
    ADD COLUMN tax_plausibility character varying(16),
    ADD COLUMN supplier_registration_required boolean;

ALTER TABLE public.cash_movement
    ADD CONSTRAINT ck_cash_movement_tax_plausibility
        CHECK (tax_plausibility IS NULL OR tax_plausibility IN ('PLAUSIBLE', 'RATE_UNAVAILABLE'));

-- 2. cash_movement_stated_tax.
CREATE TABLE public.cash_movement_stated_tax (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    stated_tax_id uuid NOT NULL,
    movement_id uuid NOT NULL,
    regime character varying(32) NOT NULL,
    amount numeric(19,4) NOT NULL,
    CONSTRAINT ck_cash_movement_stated_tax_amount CHECK (amount > 0)
);

ALTER TABLE ONLY public.cash_movement_stated_tax
    ADD CONSTRAINT cash_movement_stated_tax_pkey PRIMARY KEY (stated_tax_id);
ALTER TABLE ONLY public.cash_movement_stated_tax
    ADD CONSTRAINT cash_movement_stated_tax_tenant_key UNIQUE (tenant_id, stated_tax_id);
ALTER TABLE ONLY public.cash_movement_stated_tax
    ADD CONSTRAINT uq_cash_movement_stated_tax_regime UNIQUE (tenant_id, movement_id, regime);
ALTER TABLE ONLY public.cash_movement_stated_tax
    ADD CONSTRAINT fk_cash_movement_stated_tax_movement FOREIGN KEY (tenant_id, movement_id)
        REFERENCES public.cash_movement(tenant_id, movement_id);
CREATE INDEX cash_movement_stated_tax_tenant_idx ON public.cash_movement_stated_tax USING btree (tenant_id);

ALTER TABLE public.cash_movement_stated_tax ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.cash_movement_stated_tax FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.cash_movement_stated_tax
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 3. ext_accounting_petty_expense_category.
ALTER TABLE public.ext_accounting_petty_expense_category
    ADD COLUMN tax_recoverable boolean DEFAULT false NOT NULL,
    ADD COLUMN recoverable_percent numeric(5,2);
