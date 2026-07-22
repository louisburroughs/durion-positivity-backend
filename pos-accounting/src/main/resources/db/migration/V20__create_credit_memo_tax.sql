-- Story parity-T8 follow-on (issue #996): true per-jurisdiction credit attribution.
--
-- The T8 sales-tax liability report nets credit-memo tax reversals per jurisdiction. Until now
-- `credit_memo` stored only the scalar `tax_amount_reversed`, so the report had to *approximate*
-- the jurisdictional split at read time by pro-rating that scalar across the original invoice's
-- `ext_invoice_tax` rows (`TaxCreditAllocator`). That approximation is re-derived on every run and
-- cannot honour a per-jurisdiction residual across a sequence of partial credits.
--
-- This table freezes the attribution at credit-memo creation instead: one row per jurisdiction the
-- credit reverses tax in, summing exactly to `credit_memo.tax_amount_reversed`. T8 reads these rows
-- directly; the pro-rata allocator survives only as a fallback for credits issued before this
-- migration.
--
-- FLYWAY COLLISION NOTE: numbered V20 as the next contiguous version after V19. Unmerged
-- accounting/tax-plan branches may also claim a V2x on pos-accounting; if one merges first this file
-- must be renumbered on rebase.
CREATE TABLE credit_memo_tax (
    id uuid NOT NULL,
    credit_memo_id uuid NOT NULL,
    jurisdiction_type varchar(32) NOT NULL,
    jurisdiction_code varchar(64) NOT NULL,
    tax_amount_reversed numeric(19, 4) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT credit_memo_tax_pkey PRIMARY KEY (id),
    CONSTRAINT fk_credit_memo_tax_memo FOREIGN KEY (credit_memo_id) REFERENCES credit_memo (credit_memo_id),
    CONSTRAINT uq_credit_memo_tax_jurisdiction UNIQUE (credit_memo_id, jurisdiction_type, jurisdiction_code),
    CONSTRAINT chk_credit_memo_tax_reversed_non_negative CHECK (tax_amount_reversed >= 0)
);

CREATE INDEX idx_credit_memo_tax_memo ON credit_memo_tax (credit_memo_id);
