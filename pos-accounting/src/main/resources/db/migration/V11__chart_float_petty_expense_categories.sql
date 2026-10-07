-- #2511 (CAP:550 S15; SPEC-accounting-workspace §4.6, §7.1 "Float", "Petty-expense categories", "Seed";
-- AW9, AW16-AW18, AW30): the chart, float and petty-expense category schema.
--
--   1. CASH_ON_HAND joins the account subtypes: 1080 Register Float is cash kept in drawers, never BANK_CASH,
--      so bank reconciliation and BankCashAccounts never see it (AW9).
--   2. PETTY_EXPENSE_CATEGORY joins the accounting template's entry kinds (S37's applier, #2526).
--   3. The AW30 renumbering: 6010 -> 6100, 6015 -> 6102, 6025 -> 6105, 6115 -> 6040 Cash Short and
--      6900 -> 4940 (a revenue account leaves the expense range), each under the account's own
--      gl_account_id; the account code each statement line copies follows; 6340 becomes Shop Supplies &
--      Consumables. Nothing is posted, and no tenant row is deleted. The platform tenant holds the template,
--      not books: its rows under the old codes are removed, and R__seed_reference_accounting.sql, which
--      runs after this migration, adds them again under the new codes and the template ids those codes
--      derive.
--   4. The register float and petty-expense category tables, with the full tenancy schema of
--      TENANCY_SCHEMA.md "Adding a table" (tenant_id first, (tenant_id, <pk>) key, every unique led by
--      tenant_id, the tenant index, the tenant_isolation policy).
--
-- Tenancy: the renumbering reads and writes every tenant's rows. As V8 and V10 do, the migration lifts FORCE
-- ROW LEVEL SECURITY for its own statements and restores it; it is one transaction, so nobody observes a table
-- without it. Timestamps are literal (ADR-0024: no SQL clock).

-- 1. CASH_ON_HAND.
ALTER TABLE public.gl_account DROP CONSTRAINT gl_account_account_subtype_check;
ALTER TABLE public.gl_account ADD CONSTRAINT gl_account_account_subtype_check CHECK (((account_subtype)::text = ANY (ARRAY[
    'RECEIVABLE'::text, 'PAYABLE'::text, 'BANK_CASH'::text, 'UNDEPOSITED_FUNDS'::text, 'TAX_PAYABLE'::text,
    'CURRENT_ASSET'::text, 'FIXED_ASSET'::text, 'CURRENT_LIABILITY'::text, 'SALES'::text, 'COST_OF_SALES'::text,
    'OPERATING_EXPENSE'::text, 'OTHER'::text, 'CASH_ON_HAND'::text])));

-- 2. PETTY_EXPENSE_CATEGORY entries of the template.
ALTER TABLE public.accounting_template_entry DROP CONSTRAINT accounting_template_entry_kind_check;
ALTER TABLE public.accounting_template_entry ADD CONSTRAINT accounting_template_entry_kind_check CHECK ((kind)::text = ANY (ARRAY[
    'ACCOUNT'::text, 'CATEGORY'::text, 'MAPPING_KEY'::text, 'GL_MAPPING'::text, 'DEFAULT_GL_MAPPING'::text,
    'STATEMENT_LINE'::text, 'PETTY_EXPENSE_CATEGORY'::text]));

-- 3. The AW30 renumbering.
ALTER TABLE public.gl_account NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.gl_mapping NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.default_gl_mapping NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.statement_line_mappings NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.accounting_template_entry NO FORCE ROW LEVEL SECURITY;

-- 3a. Tenants' books: the code changes, the account (and every line posted to it) stays. A tenant that
--     already holds the new code keeps both accounts as they are; the template then reports the clash.
UPDATE public.gl_account g
SET account_code = r.new_code,
    modified_at = '2026-10-06 00:00:00+00',
    modified_by = 'aw30-renumbering'
FROM (VALUES ('6010', '6100'), ('6015', '6102'), ('6025', '6105'), ('6115', '6040'), ('6900', '4940'))
    AS r(old_code, new_code)
WHERE g.account_code = r.old_code
  AND g.tenant_id <> '01900000-0000-7000-8000-000000000000'::uuid
  AND NOT EXISTS (SELECT 1 FROM public.gl_account o
                  WHERE o.tenant_id = g.tenant_id AND o.account_code = r.new_code);

-- statement_line_mappings.account_name is a copy of the account's code (V2, the template); it follows.
UPDATE public.statement_line_mappings s
SET account_name = g.account_code
FROM public.gl_account g
WHERE g.tenant_id = s.tenant_id
  AND g.gl_account_id = s.gl_account_id
  AND s.account_name IN ('6010', '6015', '6025', '6115', '6900')
  AND g.account_code IN ('6100', '6102', '6105', '6040', '4940')
  AND s.tenant_id <> '01900000-0000-7000-8000-000000000000'::uuid;

-- 6340 is the shop-supplies category's account (§4.6 "EXISTING, renamed"). A tenant that already renamed it
-- keeps its own name.
UPDATE public.gl_account
SET account_name = 'Shop Supplies & Consumables',
    modified_at = '2026-10-06 00:00:00+00',
    modified_by = 'aw30-renumbering'
WHERE account_code = '6340'
  AND account_name = 'Retread Shop Consumables'
  AND tenant_id <> '01900000-0000-7000-8000-000000000000'::uuid;

-- What the template applier recorded for the renumbered accounts and their lines follows the codes, so the
-- next run finds the entries it already settled instead of adopting them a second time.
UPDATE public.accounting_template_entry e
SET entry_key = r.prefix || r.new_code,
    modified_at = '2026-10-06 00:00:00+00'
FROM (SELECT p.prefix, c.old_code, c.new_code
      FROM (VALUES ('ACCOUNT:'), ('STATEMENT_LINE:LABOR_OVERHEAD:')) AS p(prefix)
      CROSS JOIN (VALUES ('6010', '6100'), ('6015', '6102'), ('6025', '6105'), ('6115', '6040'), ('6900', '4940'))
          AS c(old_code, new_code)) r
WHERE e.entry_key = r.prefix || r.old_code
  AND NOT EXISTS (SELECT 1 FROM public.accounting_template_entry o
                  WHERE o.tenant_id = e.tenant_id AND o.entry_key = r.prefix || r.new_code);

-- 3b. The platform tenant's template rows under the old codes. Their ids derive from the old codes, so they
--     cannot be renumbered in place; R__ adds the accounts, the CASH_SHORT mapping and the lines again.
DELETE FROM public.statement_line_mappings s
USING public.gl_account g
WHERE s.tenant_id = '01900000-0000-7000-8000-000000000000'::uuid
  AND g.tenant_id = s.tenant_id
  AND g.gl_account_id = s.gl_account_id
  AND g.account_code IN ('6010', '6015', '6025', '6115', '6900');

DELETE FROM public.gl_mapping m
USING public.gl_account g
WHERE m.tenant_id = '01900000-0000-7000-8000-000000000000'::uuid
  AND g.tenant_id = m.tenant_id
  AND g.gl_account_id = m.gl_account_id
  AND g.account_code IN ('6010', '6015', '6025', '6115', '6900');

DELETE FROM public.default_gl_mapping d
USING public.gl_account g
WHERE d.tenant_id = '01900000-0000-7000-8000-000000000000'::uuid
  AND g.tenant_id = d.tenant_id
  AND (g.gl_account_id = d.debit_account_id OR g.gl_account_id = d.credit_account_id)
  AND g.account_code IN ('6010', '6015', '6025', '6115', '6900');

DELETE FROM public.gl_account
WHERE tenant_id = '01900000-0000-7000-8000-000000000000'::uuid
  AND account_code IN ('6010', '6015', '6025', '6115', '6900');

ALTER TABLE public.gl_account FORCE ROW LEVEL SECURITY;
ALTER TABLE public.gl_mapping FORCE ROW LEVEL SECURITY;
ALTER TABLE public.default_gl_mapping FORCE ROW LEVEL SECURITY;
ALTER TABLE public.statement_line_mappings FORCE ROW LEVEL SECURITY;
ALTER TABLE public.accounting_template_entry FORCE ROW LEVEL SECURITY;

-- 4a. Petty-expense categories (§4.6, AW18): one row per category, bound to its mapping key
--     PETTY_EXPENSE_<code> of REGISTER_CASH_MOVEMENT. Codes are permanent; a category is deactivated, never
--     deleted.
CREATE TABLE public.petty_expense_category (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    petty_expense_category_id uuid NOT NULL,
    mapping_key_id uuid NOT NULL,
    code character varying(40) NOT NULL,
    label character varying(100) NOT NULL,
    examples character varying(500),
    status character varying(10) NOT NULL,
    version integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    created_by character varying(50) NOT NULL,
    modified_at timestamp(6) with time zone NOT NULL,
    modified_by character varying(50) NOT NULL,
    CONSTRAINT petty_expense_category_status_check CHECK ((status)::text = ANY (ARRAY['ACTIVE'::text, 'INACTIVE'::text])),
    CONSTRAINT petty_expense_category_code_check CHECK ((code)::text ~ '^[A-Z0-9_]{1,40}$')
);

COMMENT ON TABLE public.petty_expense_category IS
    'A petty-expense category (#2511; SPEC-accounting-workspace §4.6): the cashier''s label and examples for a '
    'REGISTER_CASH_MOVEMENT mapping key PETTY_EXPENSE_<code>. The account is the key''s effective-dated GL mapping.';

ALTER TABLE ONLY public.petty_expense_category
    ADD CONSTRAINT petty_expense_category_pkey PRIMARY KEY (petty_expense_category_id);
ALTER TABLE ONLY public.petty_expense_category
    ADD CONSTRAINT petty_expense_category_tenant_key UNIQUE (tenant_id, petty_expense_category_id);
ALTER TABLE ONLY public.petty_expense_category
    ADD CONSTRAINT uq_petty_expense_category_code UNIQUE (tenant_id, code);
ALTER TABLE ONLY public.petty_expense_category
    ADD CONSTRAINT uq_petty_expense_category_mapping_key UNIQUE (tenant_id, mapping_key_id);
ALTER TABLE ONLY public.petty_expense_category
    ADD CONSTRAINT petty_expense_category_mapping_key_fk FOREIGN KEY (tenant_id, mapping_key_id)
        REFERENCES public.mapping_key(tenant_id, mapping_key_id);
CREATE INDEX petty_expense_category_tenant_idx ON public.petty_expense_category USING btree (tenant_id);

ALTER TABLE public.petty_expense_category ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.petty_expense_category FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.petty_expense_category
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 4b. A category's change history: one row per command (and one per row the template created).
CREATE TABLE public.petty_expense_category_change (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    change_id uuid NOT NULL,
    petty_expense_category_id uuid NOT NULL,
    code character varying(40) NOT NULL,
    change_type character varying(20) NOT NULL,
    old_value text,
    new_value text NOT NULL,
    actor character varying(50) NOT NULL,
    justification character varying(1000) NOT NULL,
    request_id uuid,
    request_hash character varying(64),
    changed_at timestamp(6) with time zone NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    modified_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT petty_expense_category_change_type_check
        CHECK ((change_type)::text = ANY (ARRAY['CREATE'::text, 'RELABEL'::text, 'DEACTIVATE'::text, 'REMAP'::text]))
);

ALTER TABLE ONLY public.petty_expense_category_change
    ADD CONSTRAINT petty_expense_category_change_pkey PRIMARY KEY (change_id);
ALTER TABLE ONLY public.petty_expense_category_change
    ADD CONSTRAINT petty_expense_category_change_tenant_key UNIQUE (tenant_id, change_id);
-- A requestId names one command: a replay finds the first result (NULL for template-created rows).
ALTER TABLE ONLY public.petty_expense_category_change
    ADD CONSTRAINT uq_petty_expense_category_change_request UNIQUE (tenant_id, request_id);
ALTER TABLE ONLY public.petty_expense_category_change
    ADD CONSTRAINT petty_expense_category_change_category_fk FOREIGN KEY (tenant_id, petty_expense_category_id)
        REFERENCES public.petty_expense_category(tenant_id, petty_expense_category_id);
CREATE INDEX petty_expense_category_change_tenant_idx ON public.petty_expense_category_change USING btree (tenant_id);
CREATE INDEX petty_expense_category_change_category_idx
    ON public.petty_expense_category_change USING btree (tenant_id, petty_expense_category_id);

ALTER TABLE public.petty_expense_category_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.petty_expense_category_change FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.petty_expense_category_change
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 4c. Register float (§4.6 "Float", AW16-AW17): the fixed change float of one register. registerId is
--     pos-order's terminalId (free text; AW31 sign-off on Spec discrepancy 1).
CREATE TABLE public.register_float (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    register_float_id uuid NOT NULL,
    register_id character varying(100) NOT NULL,
    location_id uuid NOT NULL,
    amount numeric(19,4) NOT NULL,
    go_live_journal_entry_id uuid,
    version integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    modified_at timestamp(6) with time zone NOT NULL
);

COMMENT ON TABLE public.register_float IS
    'The change float of one register (#2511; AW16): a fixed amount kept in its drawer, held on 1080 Register '
    'Float. Set and changed only by the go-live and Change float commands (or their reversal).';

ALTER TABLE ONLY public.register_float
    ADD CONSTRAINT register_float_pkey PRIMARY KEY (register_float_id);
ALTER TABLE ONLY public.register_float
    ADD CONSTRAINT register_float_tenant_key UNIQUE (tenant_id, register_float_id);
ALTER TABLE ONLY public.register_float
    ADD CONSTRAINT uq_register_float_register UNIQUE (tenant_id, register_id);
CREATE INDEX register_float_tenant_idx ON public.register_float USING btree (tenant_id);

ALTER TABLE public.register_float ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.register_float FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.register_float
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 4d. A float's history: GO_LIVE and CHANGE rows each own one journal entry; a REVERSAL row records the
--     reversal of one of them and the amount re-derived from the entries still standing.
CREATE TABLE public.register_float_change (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    change_id uuid NOT NULL,
    register_float_id uuid NOT NULL,
    register_id character varying(100) NOT NULL,
    location_id uuid NOT NULL,
    kind character varying(10) NOT NULL,
    previous_amount numeric(19,4) NOT NULL,
    new_amount numeric(19,4) NOT NULL,
    bank_gl_account_id uuid,
    journal_entry_id uuid NOT NULL,
    reversed_change_id uuid,
    reversal_journal_entry_id uuid,
    effective_date date NOT NULL,
    justification character varying(1000) NOT NULL,
    override_justification character varying(1000),
    actor character varying(50) NOT NULL,
    request_id uuid,
    request_hash character varying(64),
    created_at timestamp(6) with time zone NOT NULL,
    modified_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT register_float_change_kind_check
        CHECK ((kind)::text = ANY (ARRAY['GO_LIVE'::text, 'CHANGE'::text, 'REVERSAL'::text]))
);

ALTER TABLE ONLY public.register_float_change
    ADD CONSTRAINT register_float_change_pkey PRIMARY KEY (change_id);
ALTER TABLE ONLY public.register_float_change
    ADD CONSTRAINT register_float_change_tenant_key UNIQUE (tenant_id, change_id);
ALTER TABLE ONLY public.register_float_change
    ADD CONSTRAINT uq_register_float_change_request UNIQUE (tenant_id, request_id);
ALTER TABLE ONLY public.register_float_change
    ADD CONSTRAINT register_float_change_float_fk FOREIGN KEY (tenant_id, register_float_id)
        REFERENCES public.register_float(tenant_id, register_float_id);
CREATE INDEX register_float_change_tenant_idx ON public.register_float_change USING btree (tenant_id);
CREATE INDEX register_float_change_float_idx ON public.register_float_change USING btree (tenant_id, register_float_id);
CREATE INDEX register_float_change_entry_idx ON public.register_float_change USING btree (tenant_id, journal_entry_id);
-- Once per register (AW17): at most one go-live that has not been reversed.
CREATE UNIQUE INDEX uq_register_float_change_live_go_live ON public.register_float_change
    USING btree (tenant_id, register_float_id) WHERE ((kind)::text = 'GO_LIVE'::text AND reversal_journal_entry_id IS NULL);

ALTER TABLE public.register_float_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.register_float_change FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.register_float_change
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
