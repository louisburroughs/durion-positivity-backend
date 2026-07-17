-- Story H1 (Issue #934): COA metadata - reconcilable flag + account subtype.
-- reconcilable: whether journal entry lines on this account participate in
--   reconciliation (settlement/bank rec). Defaults false; existing rows keep false.
-- account_subtype: optional refinement of account_type (pragmatic subset of
--   Odoo's account_type taxonomy). Nullable so existing accounts are unaffected;
--   backfilled for seeded accounts by R__seed_reference_accounting.sql.

ALTER TABLE gl_account
    ADD COLUMN reconcilable boolean NOT NULL DEFAULT false;

ALTER TABLE gl_account
    ADD COLUMN account_subtype character varying(30);

ALTER TABLE gl_account
    ADD CONSTRAINT gl_account_account_subtype_check CHECK (
        (account_subtype)::text = ANY ((ARRAY[
            'RECEIVABLE'::character varying,
            'PAYABLE'::character varying,
            'BANK_CASH'::character varying,
            'UNDEPOSITED_FUNDS'::character varying,
            'TAX_PAYABLE'::character varying,
            'CURRENT_ASSET'::character varying,
            'FIXED_ASSET'::character varying,
            'CURRENT_LIABILITY'::character varying,
            'SALES'::character varying,
            'COST_OF_SALES'::character varying,
            'OPERATING_EXPENSE'::character varying,
            'OTHER'::character varying
        ])::text[])
    );
