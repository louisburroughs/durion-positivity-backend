-- CAP:550 S12 (#2509; SPEC-accounting-workspace §4.3, §7.1; AW8, AW37-AW43, louisburroughs/durion#551): the
-- vendor-bill approval lifecycle and the bill's posting at approval.
--
-- * AWAITING_APPROVAL joins the status check; a bill in it carries who sent it for approval, when and why.
-- * Matching keeps what the vendor billed: each receipt line gains the billed quantity and price, and an EDI bill
--   keeps the net and tax its document states (AW39).
-- * Every match that changes a bill's status, and every candidate selection, writes one append-only evidence row.
-- * Candidate rows keep the points per criterion and the invoice they were scored against, so a selection can
--   write the same evidence and keep the billed lines.
-- * A re-issue of an APPROVED bill is an exception item linked to it, never a change to the bill (§4.3).
-- * One posting row per bill (the invoice_gl_posting pattern): its entry, posting date and rule, and the void's
--   reversal. Accounts come from the VENDOR_BILL posting category (template rows in R__seed_reference_accounting.sql).
-- * VENDOR_BILL_GL_POSTING is retired (AW40): goods-receipt bills used to publish one at creation, which the
--   posting engine always failed (NO_RULE_VERSION). Every such event not already PROCESSED is closed SKIPPED with
--   failureReasonCode RETIRED_EVENT_TYPE, which neither the retry job nor the retry endpoint selects.
--
-- Tenancy: every new table follows the ADR-0062 "Adding a table" checklist (tenant default, (tenant_id, id) unique,
-- composite foreign keys, tenant index, forced row-level security) and none is listed in tenancy-global-tables.txt.
-- Flyway runs as the owning superuser, so the event clean-up below reaches every tenant's rows. No backfill of
-- bills (#2509 "Data requirements"); timestamps are left alone (ADR-0024 forbids SQL-clock writes), except the
-- retired events' processed_at, which is null on a never-processed event and stays so.

-- 1. vendor_bill: the submission (with the classification it may propose, AW39), the stated net and tax, the new
--    status.
ALTER TABLE public.vendor_bill ADD COLUMN submitted_at timestamp(6) with time zone;
ALTER TABLE public.vendor_bill ADD COLUMN submitted_by character varying(50);
ALTER TABLE public.vendor_bill ADD COLUMN submission_justification character varying(1000);
ALTER TABLE public.vendor_bill ADD COLUMN proposed_debit_class character varying(30);
ALTER TABLE public.vendor_bill ADD COLUMN proposed_expense_mapping_key character varying(100);
ALTER TABLE public.vendor_bill ADD COLUMN net_amount numeric(19,4);
ALTER TABLE public.vendor_bill ADD COLUMN tax_amount numeric(19,4);
ALTER TABLE public.vendor_bill ADD COLUMN stated_line_count integer;
ALTER TABLE public.vendor_bill ADD COLUMN difference_class character varying(30);
ALTER TABLE public.vendor_bill ADD COLUMN difference_expense_mapping_key character varying(100);
ALTER TABLE public.vendor_bill ADD COLUMN difference_justification character varying(1000);

COMMENT ON COLUMN public.vendor_bill.net_amount IS
    'The net the vendor''s document states (EDI; AW39), signed like total_amount. Null on a bill whose source states '
    'no header amounts (a goods-receipt bill posts from its lines).';
COMMENT ON COLUMN public.vendor_bill.tax_amount IS
    'The tax the vendor''s document states, never recalculated (AW39), signed like total_amount. US tax is part of '
    'the cost when the bill posts. With only the net stated it is gross - net; with neither, 0 (AW47).';
COMMENT ON COLUMN public.vendor_bill.stated_line_count IS
    'Lines the vendor''s document states (EDI), at least 1: the rounding tolerance of gross vs net + tax is 0.01 per '
    'stated line, at most 0.05 per bill (AW47).';
COMMENT ON COLUMN public.vendor_bill.difference_class IS
    'How an unreconciled gross - (net + tax) posts, proposed at submission (AW47): FREIGHT, GOODS, EXPENSE or '
    'PRICE_DIFFERENCE.';

ALTER TABLE public.vendor_bill DROP CONSTRAINT vendor_bill_status_check;
ALTER TABLE public.vendor_bill ADD CONSTRAINT vendor_bill_status_check CHECK (((status)::text = ANY (ARRAY[
    ('PENDING_RECEIPT_MATCH'::character varying)::text,
    ('MATCH_EXCEPTION'::character varying)::text,
    ('CURRENCY_HOLD'::character varying)::text,
    ('AWAITING_APPROVAL'::character varying)::text,
    ('APPROVED'::character varying)::text,
    ('REJECTED'::character varying)::text,
    ('PAID'::character varying)::text,
    ('VOIDED'::character varying)::text])));

-- 2. vendor_bill_line: what the vendor billed for the line (AW39).
ALTER TABLE public.vendor_bill_line ADD COLUMN billed_quantity numeric(19,4);
ALTER TABLE public.vendor_bill_line ADD COLUMN billed_unit_price numeric(19,4);

COMMENT ON COLUMN public.vendor_bill_line.billed_quantity IS
    'Quantity the vendor billed, written by a match or a candidate selection; 0 for a received line the invoice did '
    'not bill. Null while the bill has not been matched (the received quantity is then what is billed).';

-- 3. vendor_bill_match_candidate: the points per criterion (P3) and the invoice the candidate was scored against.
ALTER TABLE public.vendor_bill_match_candidate ADD COLUMN amount_points integer;
ALTER TABLE public.vendor_bill_match_candidate ADD COLUMN product_points integer;
ALTER TABLE public.vendor_bill_match_candidate ADD COLUMN date_points integer;
ALTER TABLE public.vendor_bill_match_candidate ADD COLUMN purchase_order_points integer;
ALTER TABLE public.vendor_bill_match_candidate ADD COLUMN invoice_reference character varying(50);
ALTER TABLE public.vendor_bill_match_candidate ADD COLUMN invoice_date timestamp(6) without time zone;
ALTER TABLE public.vendor_bill_match_candidate ADD COLUMN invoice_due_date timestamp(6) without time zone;
ALTER TABLE public.vendor_bill_match_candidate ADD COLUMN invoice_total_amount numeric(19,4);
ALTER TABLE public.vendor_bill_match_candidate ADD COLUMN invoice_lines jsonb;

-- 4. vendor_bill_match_evidence: append-only, one row per status-changing match or candidate selection.
CREATE TABLE public.vendor_bill_match_evidence (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    match_evidence_id uuid NOT NULL,
    vendor_bill_id uuid NOT NULL,
    invoice_event_id uuid NOT NULL,
    source character varying(30) NOT NULL,
    confidence character varying(30) NOT NULL,
    score integer NOT NULL,
    amount_points integer NOT NULL,
    product_points integer NOT NULL,
    date_points integer NOT NULL,
    purchase_order_points integer NOT NULL,
    invoice_reference character varying(50) NOT NULL,
    invoice_date timestamp(6) without time zone NOT NULL,
    received_date timestamp(6) without time zone NOT NULL,
    received_total numeric(19,4) NOT NULL,
    billed_total numeric(19,4) NOT NULL,
    currency_code character varying(3) NOT NULL,
    within_tolerance boolean NOT NULL,
    line_comparison jsonb NOT NULL,
    recorded_by character varying(50) NOT NULL,
    recorded_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT vendor_bill_match_evidence_source_check
        CHECK (((source)::text = ANY (ARRAY['MATCH'::text, 'CANDIDATE_SELECTION'::text])))
);

COMMENT ON TABLE public.vendor_bill_match_evidence IS
    'Append-only match evidence of a vendor bill (#2509): the score and points per criterion, the confidence, the '
    'invoice, the received and billed totals and the line comparison the tolerance check made. Never updated.';

ALTER TABLE ONLY public.vendor_bill_match_evidence
    ADD CONSTRAINT vendor_bill_match_evidence_pkey PRIMARY KEY (match_evidence_id);
ALTER TABLE ONLY public.vendor_bill_match_evidence
    ADD CONSTRAINT vendor_bill_match_evidence_tenant_key UNIQUE (tenant_id, match_evidence_id);
ALTER TABLE ONLY public.vendor_bill_match_evidence
    ADD CONSTRAINT vendor_bill_match_evidence_bill_fk FOREIGN KEY (tenant_id, vendor_bill_id)
        REFERENCES public.vendor_bill(tenant_id, vendor_bill_id);
CREATE INDEX vendor_bill_match_evidence_tenant_idx ON public.vendor_bill_match_evidence USING btree (tenant_id);
CREATE INDEX vendor_bill_match_evidence_bill_idx
    ON public.vendor_bill_match_evidence USING btree (tenant_id, vendor_bill_id, recorded_at);

ALTER TABLE public.vendor_bill_match_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.vendor_bill_match_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.vendor_bill_match_evidence
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 5. vendor_bill_reissue: a re-issue of an APPROVED bill, recorded for a person to settle (§4.3).
CREATE TABLE public.vendor_bill_reissue (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    vendor_bill_reissue_id uuid NOT NULL,
    vendor_bill_id uuid NOT NULL,
    incoming_bill_number character varying(50) NOT NULL,
    incoming_bill_date date NOT NULL,
    incoming_amount numeric(19,4) NOT NULL,
    incoming_currency_code character varying(3) NOT NULL,
    held_amount numeric(19,4) NOT NULL,
    held_currency_code character varying(3) NOT NULL,
    source_event_id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL
);

COMMENT ON TABLE public.vendor_bill_reissue IS
    'A vendor''s re-issue, under the same number, of a bill already APPROVED (#2509): the approved bill keeps its '
    'status and approval; this row records both amounts for a person to settle by credit note or with the vendor.';

ALTER TABLE ONLY public.vendor_bill_reissue
    ADD CONSTRAINT vendor_bill_reissue_pkey PRIMARY KEY (vendor_bill_reissue_id);
ALTER TABLE ONLY public.vendor_bill_reissue
    ADD CONSTRAINT vendor_bill_reissue_tenant_key UNIQUE (tenant_id, vendor_bill_reissue_id);
ALTER TABLE ONLY public.vendor_bill_reissue
    ADD CONSTRAINT uq_vendor_bill_reissue_source_event UNIQUE (tenant_id, source_event_id);
ALTER TABLE ONLY public.vendor_bill_reissue
    ADD CONSTRAINT vendor_bill_reissue_bill_fk FOREIGN KEY (tenant_id, vendor_bill_id)
        REFERENCES public.vendor_bill(tenant_id, vendor_bill_id);
CREATE INDEX vendor_bill_reissue_tenant_idx ON public.vendor_bill_reissue USING btree (tenant_id);
CREATE INDEX vendor_bill_reissue_bill_idx ON public.vendor_bill_reissue USING btree (tenant_id, vendor_bill_id);

ALTER TABLE public.vendor_bill_reissue ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.vendor_bill_reissue FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.vendor_bill_reissue
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 6. vendor_bill_gl_posting: one row per bill, written in the approval's transaction (AW37, AW42).
CREATE TABLE public.vendor_bill_gl_posting (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    vendor_bill_gl_posting_id uuid NOT NULL,
    vendor_bill_id uuid NOT NULL,
    source_key character varying(80) NOT NULL,
    journal_entry_id uuid NOT NULL,
    posting_date date NOT NULL,
    posting_date_rule character varying(40) NOT NULL,
    debit_class character varying(30),
    expense_mapping_key character varying(100),
    gross_amount numeric(19,4) NOT NULL,
    currency_code character varying(3) NOT NULL,
    rounding_adjustment numeric(19,4) NOT NULL,
    difference_class character varying(30),
    difference_amount numeric(19,4),
    difference_justification character varying(1000),
    posted_at timestamp(6) with time zone NOT NULL,
    posted_by character varying(50) NOT NULL,
    reversal_source_key character varying(80),
    reversal_journal_entry_id uuid,
    reversal_date date,
    reversed_at timestamp(6) with time zone,
    reversed_by character varying(50),
    CONSTRAINT vendor_bill_gl_posting_rule_check CHECK (((posting_date_rule)::text = ANY (ARRAY[
        'BILL_DATE'::text, 'APPROVAL_DATE_BILL_PERIOD_NOT_OPEN'::text, 'APPROVAL_DATE_BILL_DATE_FUTURE'::text]))),
    CONSTRAINT chk_vendor_bill_gl_posting_reversal CHECK ((
        (reversal_journal_entry_id IS NULL) = (reversed_at IS NULL)
        AND (reversal_journal_entry_id IS NULL) = (reversal_source_key IS NULL)
        AND (reversal_journal_entry_id IS NULL) = (reversal_date IS NULL)))
);

COMMENT ON TABLE public.vendor_bill_gl_posting IS
    'The posting of an approved vendor bill (#2509; AW37-AW42): one row per bill, written in the approval''s '
    'transaction. source_key VENDOR_BILL:<billId> and reversal_source_key VENDOR_BILL_VOID:<billId> are the durable '
    'idempotency keys; the void of an approved bill reverses journal_entry_id on its void date.';

ALTER TABLE ONLY public.vendor_bill_gl_posting
    ADD CONSTRAINT vendor_bill_gl_posting_pkey PRIMARY KEY (vendor_bill_gl_posting_id);
ALTER TABLE ONLY public.vendor_bill_gl_posting
    ADD CONSTRAINT vendor_bill_gl_posting_tenant_key UNIQUE (tenant_id, vendor_bill_gl_posting_id);
ALTER TABLE ONLY public.vendor_bill_gl_posting
    ADD CONSTRAINT uq_vendor_bill_gl_posting_bill UNIQUE (tenant_id, vendor_bill_id);
ALTER TABLE ONLY public.vendor_bill_gl_posting
    ADD CONSTRAINT uq_vendor_bill_gl_posting_source_key UNIQUE (tenant_id, source_key);
ALTER TABLE ONLY public.vendor_bill_gl_posting
    ADD CONSTRAINT uq_vendor_bill_gl_posting_reversal_key UNIQUE (tenant_id, reversal_source_key);
ALTER TABLE ONLY public.vendor_bill_gl_posting
    ADD CONSTRAINT vendor_bill_gl_posting_bill_fk FOREIGN KEY (tenant_id, vendor_bill_id)
        REFERENCES public.vendor_bill(tenant_id, vendor_bill_id);
ALTER TABLE ONLY public.vendor_bill_gl_posting
    ADD CONSTRAINT vendor_bill_gl_posting_entry_fk FOREIGN KEY (tenant_id, journal_entry_id)
        REFERENCES public.journal_entry(tenant_id, journal_entry_id);
ALTER TABLE ONLY public.vendor_bill_gl_posting
    ADD CONSTRAINT vendor_bill_gl_posting_reversal_fk FOREIGN KEY (tenant_id, reversal_journal_entry_id)
        REFERENCES public.journal_entry(tenant_id, journal_entry_id);
CREATE INDEX vendor_bill_gl_posting_tenant_idx ON public.vendor_bill_gl_posting USING btree (tenant_id);
-- The reversal reaction finds a bill's posting by the entry being reversed, in either direction.
CREATE INDEX vendor_bill_gl_posting_entry_idx ON public.vendor_bill_gl_posting USING btree (tenant_id, journal_entry_id);
CREATE INDEX vendor_bill_gl_posting_reversal_entry_idx
    ON public.vendor_bill_gl_posting USING btree (tenant_id, reversal_journal_entry_id);

ALTER TABLE public.vendor_bill_gl_posting ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.vendor_bill_gl_posting FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.vendor_bill_gl_posting
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- 7. Retire VENDOR_BILL_GL_POSTING (AW40): close every such event not already PROCESSED, so neither the
--    scheduled retry nor POST /v1/accounting/events/{id}/retry can ever pick one up again.
UPDATE public.accounting_event
SET status = 'SKIPPED',
    failure_reason_code = 'RETIRED_EVENT_TYPE',
    error_message = 'VENDOR_BILL_GL_POSTING is retired (AW40): a vendor bill posts once, at approval, through the '
        || 'VENDOR_BILL posting category; this event is obsolete and is never retried'
WHERE event_type = 'VENDOR_BILL_GL_POSTING'
  AND status <> 'PROCESSED';
