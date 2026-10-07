-- CAP:550 S18 (#2514; SPEC-accounting-workspace §4.5, §7.1 "Undeposited sessions", "Record / reverse deposit"; AW10,
-- AW15): bank deposits of drawer cash.
--
-- undeposited_session is the read model the close fact (order.session.closed, schema version 2, functional currency)
-- writes in the handler transaction that posts the session's drawer movements and over/short (S17): its bank drops,
-- its expected cash (the CASH tender total) and its clearing net (the signed sum of the 1095 lines those postings
-- made; debit positive). A session is UNDEPOSITED until a deposit takes it whole, DEPOSITED while that deposit
-- stands, and UNDEPOSITED again once the deposit's entry is reversed (ADR-0047).
--
-- deposit is one Record bank deposit command: the entry it posted (source type BANK_DEPOSIT), the request id and
-- body hash that make it idempotent, and, once reversed, the reversal's own entry, request id and hash.
-- deposit_session keeps what each session contributed when the deposit was recorded.
--
-- The BANK_DEPOSIT posting category and its UNDEPOSITED_FUNDS / CASH_CLEARING keys are template rows (S15), so this
-- file creates no reference data.

CREATE TABLE public.undeposited_session (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    undeposited_session_id uuid NOT NULL,
    session_id uuid NOT NULL,
    terminal_id character varying(100) NOT NULL,
    location_id uuid,
    opened_at timestamp(6) with time zone NOT NULL,
    closed_at timestamp(6) with time zone NOT NULL,
    opening_float numeric(19,4) NOT NULL,
    counted_cash numeric(19,4) NOT NULL,
    theoretical_cash numeric(19,4) NOT NULL,
    over_short numeric(19,4) NOT NULL,
    expected_cash numeric(19,4) NOT NULL,
    clearing_net numeric(19,4) NOT NULL,
    deposit_amount numeric(19,4) NOT NULL,
    currency_code character varying(3) NOT NULL,
    status character varying(20) NOT NULL,
    deposit_id uuid,
    version integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    modified_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT undeposited_session_status_check CHECK (((status)::text = ANY (ARRAY['UNDEPOSITED'::text, 'DEPOSITED'::text]))),
    CONSTRAINT undeposited_session_deposit_check CHECK ((((status)::text = 'DEPOSITED'::text) = (deposit_id IS NOT NULL)))
);

COMMENT ON TABLE public.undeposited_session IS
    'A closed register session''s drawer cash on its way to the bank (CAP:550 S18, #2514): written from the close fact '
    'v2, taken whole by one deposit, returned to UNDEPOSITED when that deposit is reversed.';

ALTER TABLE ONLY public.undeposited_session
    ADD CONSTRAINT undeposited_session_pkey PRIMARY KEY (undeposited_session_id);
ALTER TABLE ONLY public.undeposited_session
    ADD CONSTRAINT undeposited_session_tenant_key UNIQUE (tenant_id, undeposited_session_id);
ALTER TABLE ONLY public.undeposited_session
    ADD CONSTRAINT uq_undeposited_session_session UNIQUE (tenant_id, session_id);
CREATE INDEX undeposited_session_tenant_idx ON public.undeposited_session USING btree (tenant_id);
-- The read lists the undeposited sessions oldest first.
CREATE INDEX undeposited_session_status_idx
    ON public.undeposited_session USING btree (tenant_id, status, closed_at);

ALTER TABLE public.undeposited_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.undeposited_session FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.undeposited_session
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

CREATE TABLE public.undeposited_session_drop (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    drop_id uuid NOT NULL,
    undeposited_session_id uuid NOT NULL,
    movement_id uuid NOT NULL,
    bag_number character varying(100),
    amount numeric(19,4) NOT NULL,
    occurred_at timestamp(6) with time zone NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT undeposited_session_drop_amount_check CHECK ((amount > (0)::numeric))
);

COMMENT ON TABLE public.undeposited_session_drop IS
    'One BANK_DROP movement of an undeposited session (CAP:550 S18, #2514): the bag and the amount the deposit takes.';

ALTER TABLE ONLY public.undeposited_session_drop
    ADD CONSTRAINT undeposited_session_drop_pkey PRIMARY KEY (drop_id);
ALTER TABLE ONLY public.undeposited_session_drop
    ADD CONSTRAINT undeposited_session_drop_tenant_key UNIQUE (tenant_id, drop_id);
ALTER TABLE ONLY public.undeposited_session_drop
    ADD CONSTRAINT uq_undeposited_session_drop_movement UNIQUE (tenant_id, movement_id);
ALTER TABLE ONLY public.undeposited_session_drop
    ADD CONSTRAINT undeposited_session_drop_session_fk FOREIGN KEY (tenant_id, undeposited_session_id)
        REFERENCES public.undeposited_session(tenant_id, undeposited_session_id);
CREATE INDEX undeposited_session_drop_tenant_idx ON public.undeposited_session_drop USING btree (tenant_id);
CREATE INDEX undeposited_session_drop_session_idx
    ON public.undeposited_session_drop USING btree (tenant_id, undeposited_session_id);

ALTER TABLE public.undeposited_session_drop ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.undeposited_session_drop FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.undeposited_session_drop
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

CREATE TABLE public.deposit (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    deposit_id uuid NOT NULL,
    bank_gl_account_id uuid NOT NULL,
    bank_account_code character varying(20) NOT NULL,
    deposit_date date NOT NULL,
    amount numeric(19,4) NOT NULL,
    expected_cash numeric(19,4) NOT NULL,
    clearing_net numeric(19,4) NOT NULL,
    currency_code character varying(3) NOT NULL,
    deposit_slip_reference character varying(100),
    journal_entry_id uuid NOT NULL,
    journal_entry_number character varying(20) NOT NULL,
    status character varying(20) NOT NULL,
    override_justification character varying(1000),
    recorded_by character varying(50) NOT NULL,
    request_id uuid NOT NULL,
    request_hash character varying(64) NOT NULL,
    reversal_journal_entry_id uuid,
    reversal_journal_entry_number character varying(20),
    reversal_date date,
    reversal_reason character varying(1000),
    reversal_override_justification character varying(1000),
    reversed_by character varying(50),
    reversed_at timestamp(6) with time zone,
    reversal_request_id uuid,
    reversal_request_hash character varying(64),
    version integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    modified_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT deposit_status_check CHECK (((status)::text = ANY (ARRAY['RECORDED'::text, 'REVERSED'::text]))),
    CONSTRAINT deposit_reversal_check CHECK ((((status)::text = 'REVERSED'::text) = (reversal_journal_entry_id IS NOT NULL))),
    CONSTRAINT deposit_amount_check CHECK ((amount > (0)::numeric))
);

COMMENT ON TABLE public.deposit IS
    'A bank deposit of drawer cash (CAP:550 S18, #2514): one BANK_DEPOSIT entry Dr bank / Cr 1090 / Dr or Cr 1095. '
    'Never edited: a wrong deposit is reversed (ADR-0047) and recorded again.';

ALTER TABLE ONLY public.deposit
    ADD CONSTRAINT deposit_pkey PRIMARY KEY (deposit_id);
ALTER TABLE ONLY public.deposit
    ADD CONSTRAINT deposit_tenant_key UNIQUE (tenant_id, deposit_id);
ALTER TABLE ONLY public.deposit
    ADD CONSTRAINT uq_deposit_request UNIQUE (tenant_id, request_id);
ALTER TABLE ONLY public.deposit
    ADD CONSTRAINT uq_deposit_reversal_request UNIQUE (tenant_id, reversal_request_id);
ALTER TABLE ONLY public.deposit
    ADD CONSTRAINT deposit_gl_account_fk FOREIGN KEY (tenant_id, bank_gl_account_id)
        REFERENCES public.gl_account(tenant_id, gl_account_id);
ALTER TABLE ONLY public.deposit
    ADD CONSTRAINT deposit_journal_entry_fk FOREIGN KEY (tenant_id, journal_entry_id)
        REFERENCES public.journal_entry(tenant_id, journal_entry_id);
CREATE INDEX deposit_tenant_idx ON public.deposit USING btree (tenant_id);
-- A journal-entry reversal asks whether a deposit owns the reversed entry.
CREATE INDEX deposit_entry_idx ON public.deposit USING btree (tenant_id, journal_entry_id);

ALTER TABLE public.deposit ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.deposit FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.deposit
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

ALTER TABLE ONLY public.undeposited_session
    ADD CONSTRAINT undeposited_session_deposit_fk FOREIGN KEY (tenant_id, deposit_id)
        REFERENCES public.deposit(tenant_id, deposit_id);

CREATE TABLE public.deposit_session (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    deposit_session_id uuid NOT NULL,
    deposit_id uuid NOT NULL,
    session_id uuid NOT NULL,
    terminal_id character varying(100) NOT NULL,
    location_id uuid,
    closed_at timestamp(6) with time zone NOT NULL,
    deposit_amount numeric(19,4) NOT NULL,
    expected_cash numeric(19,4) NOT NULL,
    clearing_net numeric(19,4) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL
);

COMMENT ON TABLE public.deposit_session IS
    'What one session contributed to one deposit when it was recorded (CAP:550 S18, #2514).';

ALTER TABLE ONLY public.deposit_session
    ADD CONSTRAINT deposit_session_pkey PRIMARY KEY (deposit_session_id);
ALTER TABLE ONLY public.deposit_session
    ADD CONSTRAINT deposit_session_tenant_key UNIQUE (tenant_id, deposit_session_id);
ALTER TABLE ONLY public.deposit_session
    ADD CONSTRAINT uq_deposit_session UNIQUE (tenant_id, deposit_id, session_id);
ALTER TABLE ONLY public.deposit_session
    ADD CONSTRAINT deposit_session_deposit_fk FOREIGN KEY (tenant_id, deposit_id)
        REFERENCES public.deposit(tenant_id, deposit_id);
CREATE INDEX deposit_session_tenant_idx ON public.deposit_session USING btree (tenant_id);
CREATE INDEX deposit_session_deposit_idx ON public.deposit_session USING btree (tenant_id, deposit_id);

ALTER TABLE public.deposit_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.deposit_session FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.deposit_session
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
