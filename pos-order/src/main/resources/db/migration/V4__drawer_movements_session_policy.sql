-- CAP:550 S16 (#2512; SPEC-accounting-workspace §4.6, §6.5, §7.2; AW15, AW16, AW19, AW31): drawer
-- movements with fixed reasons, the per-tenant session policy and its history, single-use manager
-- approvals, and pos-order's copies of accounting's petty-expense categories and register floats.
--
--   1. cash_movement gains its fixed reason and the reason's detail, the register's idempotency key
--      (request_id, unique per tenant), the cashier's user id, and the manager approval it used (the
--      approver's user id and the approval). register_session counts failed approvals. The free-text reason becomes
--      the optional note; rows recorded before this story keep their text there with a null reason_code,
--      and every row recorded from now on carries a request_id and a reason_code.
--   2. session_policy: one row per tenant (the defaults apply while there is none); session_policy_change:
--      one history row per changed setting.
--   3. cash_movement_approval: the step-up's single-use token, stored only as its SHA-256 hash, bound to
--      the session, reason, amount (with its currency) and category or vendor, with the approver's user id.
--   Every new money column states its ISO 4217 currency (ADR-0067 R-1): the functional currency.
--   4. ext_accounting_petty_expense_category and ext_accounting_register_float: written only by
--      accounting.petty-expense-category.changed and accounting.float.changed (S15), each keyed by the
--      fact's aggregate (the accounting row id) and guarded by aggregate_version (ADR-0044 R3).
--
-- Every new table follows TENANCY_SCHEMA.md "Adding a table" (ADR-0062): tenant_id first, (tenant_id, <pk>)
-- key, every unique led by tenant_id, the tenant index and the tenant_isolation policy. No SQL clock (ADR-0024).

-- 1. cash_movement.
ALTER TABLE public.cash_movement RENAME COLUMN reason TO note;
ALTER TABLE public.cash_movement ALTER COLUMN note DROP NOT NULL;
ALTER TABLE public.cash_movement
    ADD COLUMN request_id uuid,
    ADD COLUMN reason_code character varying(32),
    ADD COLUMN currency_code character varying(3),
    ADD COLUMN category_code character varying(64),
    ADD COLUMN vendor_id uuid,
    ADD COLUMN bag_number character varying(64),
    ADD COLUMN receipt_reference character varying(128),
    ADD COLUMN clerk_user_id uuid,
    ADD COLUMN approved_by uuid,
    ADD COLUMN approval_id uuid;
ALTER TABLE public.cash_movement
    ADD CONSTRAINT cash_movement_reason_code_check CHECK (reason_code IS NULL OR (reason_code)::text = ANY (ARRAY[
        'PETTY_EXPENSE'::text, 'VENDOR_COD'::text, 'BANK_DROP'::text, 'FLOAT_INCREASE'::text,
        'FLOAT_DECREASE'::text]));
-- A movement recorded through the register's request carries its reason; only pre-S16 rows have neither.
ALTER TABLE public.cash_movement
    ADD CONSTRAINT cash_movement_reason_required_check
        CHECK (request_id IS NULL OR (reason_code IS NOT NULL AND currency_code IS NOT NULL));
ALTER TABLE ONLY public.cash_movement
    ADD CONSTRAINT uq_cash_movement_request UNIQUE (tenant_id, request_id);
CREATE INDEX ix_cash_movement_session_reason ON public.cash_movement USING btree (tenant_id, session_id, reason_code);

-- Failed manager approvals per drawer session: the step-up stops asking pos-security-service after a
-- small number, so one drawer cannot be used to lock managers out (review l2).
ALTER TABLE public.register_session
    ADD COLUMN step_up_denials integer DEFAULT 0 NOT NULL;

-- 2. Session policy and its history.
CREATE TABLE public.session_policy (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    session_policy_id uuid NOT NULL,
    version bigint NOT NULL,
    petty_expense_allowed boolean NOT NULL,
    petty_expense_limit numeric(19,4),
    vendor_cod_allowed boolean NOT NULL,
    vendor_cod_limit numeric(19,4),
    over_short_tolerance numeric(19,4) NOT NULL,
    currency_code character varying(3) NOT NULL,
    updated_by character varying(255) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT session_policy_limits_check CHECK (
        (petty_expense_limit IS NULL OR petty_expense_limit >= 0)
        AND (vendor_cod_limit IS NULL OR vendor_cod_limit >= 0)
        AND over_short_tolerance >= 0),
    CONSTRAINT session_policy_cod_limit_check CHECK (NOT vendor_cod_allowed OR vendor_cod_limit IS NOT NULL)
);

COMMENT ON TABLE public.session_policy IS
    'The drawer policy of one tenant (#2512; AW19): allowed and cashier limit per movement type, in the '
    'functional currency, and the over/short tolerance. No row means the defaults.';

ALTER TABLE ONLY public.session_policy
    ADD CONSTRAINT session_policy_pkey PRIMARY KEY (session_policy_id);
ALTER TABLE ONLY public.session_policy
    ADD CONSTRAINT session_policy_tenant_key UNIQUE (tenant_id, session_policy_id);
ALTER TABLE ONLY public.session_policy
    ADD CONSTRAINT uq_session_policy_tenant UNIQUE (tenant_id);
CREATE INDEX session_policy_tenant_idx ON public.session_policy USING btree (tenant_id);

ALTER TABLE public.session_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.session_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.session_policy
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

CREATE TABLE public.session_policy_change (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    change_id uuid NOT NULL,
    setting character varying(32) NOT NULL,
    old_value character varying(32),
    new_value character varying(32),
    actor character varying(255) NOT NULL,
    justification character varying(1000) NOT NULL,
    policy_version bigint NOT NULL,
    changed_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone NOT NULL
);

ALTER TABLE ONLY public.session_policy_change
    ADD CONSTRAINT session_policy_change_pkey PRIMARY KEY (change_id);
ALTER TABLE ONLY public.session_policy_change
    ADD CONSTRAINT session_policy_change_tenant_key UNIQUE (tenant_id, change_id);
CREATE INDEX session_policy_change_tenant_idx ON public.session_policy_change USING btree (tenant_id);
CREATE INDEX session_policy_change_changed_idx ON public.session_policy_change USING btree (tenant_id, changed_at);

ALTER TABLE public.session_policy_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.session_policy_change FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.session_policy_change
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 3. Single-use manager approvals.
CREATE TABLE public.cash_movement_approval (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    approval_id uuid NOT NULL,
    version bigint NOT NULL,
    session_id uuid NOT NULL,
    reason_code character varying(32) NOT NULL,
    amount numeric(19,4) NOT NULL,
    currency_code character varying(3) NOT NULL,
    category_code character varying(64),
    vendor_id uuid,
    token_hash character varying(64) NOT NULL,
    approver_user_id uuid NOT NULL,
    requested_by character varying(255) NOT NULL,
    status character varying(16) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    used_at timestamp with time zone,
    used_by_movement_id uuid,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT cash_movement_approval_status_check
        CHECK ((status)::text = ANY (ARRAY['ISSUED'::text, 'USED'::text, 'EXPIRED'::text]))
);

ALTER TABLE ONLY public.cash_movement_approval
    ADD CONSTRAINT cash_movement_approval_pkey PRIMARY KEY (approval_id);
ALTER TABLE ONLY public.cash_movement_approval
    ADD CONSTRAINT cash_movement_approval_tenant_key UNIQUE (tenant_id, approval_id);
ALTER TABLE ONLY public.cash_movement_approval
    ADD CONSTRAINT uq_cash_movement_approval_token UNIQUE (tenant_id, token_hash);
ALTER TABLE ONLY public.cash_movement_approval
    ADD CONSTRAINT fk_cash_movement_approval_session FOREIGN KEY (tenant_id, session_id)
        REFERENCES public.register_session(tenant_id, session_id) ON DELETE CASCADE;
CREATE INDEX cash_movement_approval_tenant_idx ON public.cash_movement_approval USING btree (tenant_id);
CREATE INDEX cash_movement_approval_session_idx ON public.cash_movement_approval USING btree (tenant_id, session_id);

ALTER TABLE public.cash_movement_approval ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.cash_movement_approval FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.cash_movement_approval
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 4. Copies of accounting's facts.
CREATE TABLE public.ext_accounting_petty_expense_category (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    petty_expense_category_id uuid NOT NULL,
    code character varying(64) NOT NULL,
    label character varying(255) NOT NULL,
    examples character varying(1000),
    status character varying(16) NOT NULL,
    account_code character varying(32),
    account_name character varying(255),
    aggregate_version bigint NOT NULL,
    synced_at timestamp with time zone NOT NULL
);

ALTER TABLE ONLY public.ext_accounting_petty_expense_category
    ADD CONSTRAINT ext_accounting_petty_expense_category_pkey PRIMARY KEY (petty_expense_category_id);
ALTER TABLE ONLY public.ext_accounting_petty_expense_category
    ADD CONSTRAINT ext_accounting_petty_expense_category_tenant_key UNIQUE (tenant_id, petty_expense_category_id);
ALTER TABLE ONLY public.ext_accounting_petty_expense_category
    ADD CONSTRAINT uq_ext_accounting_petty_expense_category_code UNIQUE (tenant_id, code);
CREATE INDEX ext_accounting_petty_expense_category_tenant_idx
    ON public.ext_accounting_petty_expense_category USING btree (tenant_id);

ALTER TABLE public.ext_accounting_petty_expense_category ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_accounting_petty_expense_category FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_accounting_petty_expense_category
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

CREATE TABLE public.ext_accounting_register_float (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    register_float_id uuid NOT NULL,
    register_id character varying(255) NOT NULL,
    location_id uuid NOT NULL,
    amount numeric(19,4) NOT NULL,
    effective_date date NOT NULL,
    aggregate_version bigint NOT NULL,
    synced_at timestamp with time zone NOT NULL
);

ALTER TABLE ONLY public.ext_accounting_register_float
    ADD CONSTRAINT ext_accounting_register_float_pkey PRIMARY KEY (register_float_id);
ALTER TABLE ONLY public.ext_accounting_register_float
    ADD CONSTRAINT ext_accounting_register_float_tenant_key UNIQUE (tenant_id, register_float_id);
ALTER TABLE ONLY public.ext_accounting_register_float
    ADD CONSTRAINT uq_ext_accounting_register_float_register UNIQUE (tenant_id, register_id);
CREATE INDEX ext_accounting_register_float_tenant_idx
    ON public.ext_accounting_register_float USING btree (tenant_id);

ALTER TABLE public.ext_accounting_register_float ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_accounting_register_float FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_accounting_register_float
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
