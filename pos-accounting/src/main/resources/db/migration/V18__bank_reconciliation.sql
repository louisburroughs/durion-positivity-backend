-- Story F2 (Issue #965, decisions D-5/D-6): manual CSV bank reconciliation.
-- Import a bank statement CSV for a reconcilable GL cash account, match statement
-- lines to posted GL journal-entry lines, record adjustments (which post real JEs),
-- and finalize only when statement and GL ending balances agree (difference == 0).
--
-- Supersedes the DEAD baseline `reconciliation` table (V1 baseline): it had no
-- service/controller references and stored its children as untyped jsonb blobs on
-- the header (malformed relational model). Pre-production policy allows removing dead
-- schema — the entity + repository are deleted in this change. `reconciliation_records`
-- (CAP-251 invoice-posting retry) is a DIFFERENT, live table and is left untouched.
DROP TABLE IF EXISTS reconciliation CASCADE;

-- Reconciliation header. One row per statement import for one reconcilable cash
-- account. Single currency per reconciliation (hard invariant; header currency
-- governs). glEndingBalance is snapshotted at import from posted JE lines on the
-- account as-of statementDate.
CREATE TABLE bank_reconciliation (
    reconciliation_id uuid NOT NULL,
    gl_account_id uuid NOT NULL,
    account_code character varying(20),
    account_name character varying(100),
    period_start_date date NOT NULL,
    period_end_date date NOT NULL,
    statement_date date NOT NULL,
    currency character varying(3) NOT NULL,
    statement_ending_balance numeric(19, 4) NOT NULL,
    gl_ending_balance numeric(19, 4) NOT NULL,
    difference numeric(19, 4) NOT NULL,
    status character varying(20) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    created_by character varying(50) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    finalized_at timestamp(6) with time zone,
    finalized_by character varying(50),
    CONSTRAINT bank_reconciliation_pkey PRIMARY KEY (reconciliation_id),
    CONSTRAINT bank_reconciliation_gl_account_fk
        FOREIGN KEY (gl_account_id) REFERENCES gl_account (gl_account_id),
    CONSTRAINT bank_reconciliation_status_ck
        CHECK (status IN ('IN_PROGRESS', 'FINALIZED', 'CANCELLED'))
);

CREATE INDEX idx_bank_reconciliation_account ON bank_reconciliation (gl_account_id);
CREATE INDEX idx_bank_reconciliation_status ON bank_reconciliation (status);

-- Imported statement line. Parsed from the CSV (date, description, amount [signed],
-- reference). Starts UNMATCHED; a match sets status MATCHED and stamps match_id.
CREATE TABLE bank_reconciliation_line (
    line_id uuid NOT NULL,
    reconciliation_id uuid NOT NULL,
    line_number integer NOT NULL,
    line_date date NOT NULL,
    description character varying(500),
    amount numeric(19, 4) NOT NULL,
    reference character varying(255),
    status character varying(20) NOT NULL,
    match_id uuid,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT bank_reconciliation_line_pkey PRIMARY KEY (line_id),
    CONSTRAINT bank_reconciliation_line_recon_fk
        FOREIGN KEY (reconciliation_id) REFERENCES bank_reconciliation (reconciliation_id),
    CONSTRAINT bank_reconciliation_line_status_ck
        CHECK (status IN ('UNMATCHED', 'MATCHED'))
);

CREATE INDEX idx_bank_reconciliation_line_recon ON bank_reconciliation_line (reconciliation_id);
CREATE INDEX idx_bank_reconciliation_line_match ON bank_reconciliation_line (match_id);

-- Adjustment. Records a signed adjustment AND the id of the real balanced JE it
-- posted (Dr/Cr the reconciled cash account against the type's mapped counter
-- account). type is one of the D-6 enum values.
CREATE TABLE bank_reconciliation_adjustment (
    adjustment_id uuid NOT NULL,
    reconciliation_id uuid NOT NULL,
    adjustment_type character varying(32) NOT NULL,
    amount numeric(19, 4) NOT NULL,
    description character varying(500),
    journal_entry_id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    created_by character varying(50) NOT NULL,
    CONSTRAINT bank_reconciliation_adjustment_pkey PRIMARY KEY (adjustment_id),
    CONSTRAINT bank_reconciliation_adjustment_recon_fk
        FOREIGN KEY (reconciliation_id) REFERENCES bank_reconciliation (reconciliation_id),
    CONSTRAINT bank_reconciliation_adjustment_type_ck
        CHECK (adjustment_type IN ('BANK_FEE', 'NSF_FEE', 'INTEREST_EARNED', 'FLOAT_ADJUSTMENT', 'OTHER'))
);

CREATE INDEX idx_bank_reconciliation_adjustment_recon ON bank_reconciliation_adjustment (reconciliation_id);

-- GL lines consumed by a match set. Records which posted journal_entry_line rows a
-- match (grouped by match_id) linked to its statement lines, so a match can be
-- reversed and the GL lines released. Not a header child collection to keep the
-- aggregate small.
CREATE TABLE bank_reconciliation_gl_match (
    id uuid NOT NULL,
    reconciliation_id uuid NOT NULL,
    match_id uuid NOT NULL,
    gl_line_id uuid NOT NULL,
    signed_amount numeric(19, 4) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT bank_reconciliation_gl_match_pkey PRIMARY KEY (id),
    CONSTRAINT bank_reconciliation_gl_match_recon_fk
        FOREIGN KEY (reconciliation_id) REFERENCES bank_reconciliation (reconciliation_id),
    -- A posted GL line represents one cash movement and may be reconciled at most once
    -- across all reconciliations (backstop for the service-layer global dedup check).
    CONSTRAINT bank_reconciliation_gl_match_gl_line_uk UNIQUE (gl_line_id)
);

CREATE INDEX idx_bank_reconciliation_gl_match_recon ON bank_reconciliation_gl_match (reconciliation_id);
CREATE INDEX idx_bank_reconciliation_gl_match_match ON bank_reconciliation_gl_match (match_id);
