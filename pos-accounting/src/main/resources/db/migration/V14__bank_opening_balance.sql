-- CAP:550 #2572 (SPEC-accounting-workspace OI-10; Accounting Domain ruling 2026-10-07): a bank account's opening
-- balance at cutover, posted through 3900 Opening Balance Equity with its outstanding checks and deposits in
-- transit as separate bank lines. One row per command: it owns the journal entry it posted and carries the
-- request id and body hash that make the command idempotent. A row whose entry is still POSTED is the account's
-- standing opening; reversing the entry (ADR-0047) frees the account for a new opening.
--
-- The OPENING_BALANCE posting category, its OPENING_BALANCE_EQUITY key and the mapping to 3900 are template rows
-- (R__seed_reference_accounting.sql, S37), so this file creates no reference data.
CREATE TABLE public.bank_opening_balance (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    bank_opening_balance_id uuid NOT NULL,
    gl_account_id uuid NOT NULL,
    account_code character varying(20) NOT NULL,
    as_of_date date NOT NULL,
    statement_balance numeric(19,4) NOT NULL,
    book_balance numeric(19,4) NOT NULL,
    currency_code character varying(3) NOT NULL,
    journal_entry_id uuid NOT NULL,
    journal_entry_number character varying(20) NOT NULL,
    justification character varying(1000) NOT NULL,
    actor character varying(50) NOT NULL,
    request_id uuid NOT NULL,
    request_hash character varying(64) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL
);

COMMENT ON TABLE public.bank_opening_balance IS
    'A bank account''s opening balance at cutover (#2572, OI-10): the statement balance and the outstanding items, '
    'posted against 3900 Opening Balance Equity. Written only by POST /v1/accounting/bank-accounts/{id}/opening-balance.';

ALTER TABLE ONLY public.bank_opening_balance
    ADD CONSTRAINT bank_opening_balance_pkey PRIMARY KEY (bank_opening_balance_id);
ALTER TABLE ONLY public.bank_opening_balance
    ADD CONSTRAINT bank_opening_balance_tenant_key UNIQUE (tenant_id, bank_opening_balance_id);
ALTER TABLE ONLY public.bank_opening_balance
    ADD CONSTRAINT uq_bank_opening_balance_request UNIQUE (tenant_id, request_id);
ALTER TABLE ONLY public.bank_opening_balance
    ADD CONSTRAINT bank_opening_balance_gl_account_fk FOREIGN KEY (tenant_id, gl_account_id)
        REFERENCES public.gl_account(tenant_id, gl_account_id);
ALTER TABLE ONLY public.bank_opening_balance
    ADD CONSTRAINT bank_opening_balance_journal_entry_fk FOREIGN KEY (tenant_id, journal_entry_id)
        REFERENCES public.journal_entry(tenant_id, journal_entry_id);
CREATE INDEX bank_opening_balance_tenant_idx ON public.bank_opening_balance USING btree (tenant_id);
CREATE INDEX bank_opening_balance_account_idx
    ON public.bank_opening_balance USING btree (tenant_id, gl_account_id);
-- Bank reconciliation asks whether an opening owns a line's entry before it trusts the line's own item date.
CREATE INDEX bank_opening_balance_entry_idx
    ON public.bank_opening_balance USING btree (tenant_id, journal_entry_id);

ALTER TABLE public.bank_opening_balance ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.bank_opening_balance FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.bank_opening_balance
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
