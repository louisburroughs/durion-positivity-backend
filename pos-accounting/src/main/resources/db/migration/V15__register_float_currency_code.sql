-- Register float currency (#2577; ADR-0067 R-1, R-2, R-4; SPEC-accounting-workspace §4.6 "Float").
--
-- Every row that carries money states the ISO 4217 code that governs it. register_float holds the register's
-- float and register_float_change its before and after amounts, so both gain currency_code NOT NULL.
--
-- Rows that predate this migration were booked in the ledger currency, the only currency a Stage A ledger books
-- (PC-9): they take it through the ledger_currency Flyway placeholder, bound from accounting.ledger.base-currency
-- through LedgerCurrency (FlywayConfig), never an implicit currency literal (R-2). New rows must state it, so the default is
-- dropped once the backfill is done. A versioned migration's checksum is taken before placeholders are replaced, so
-- a later change of the value does not fail validation.

ALTER TABLE public.register_float
    ADD COLUMN currency_code character varying(3) DEFAULT '${ledger_currency}' NOT NULL;
ALTER TABLE public.register_float ALTER COLUMN currency_code DROP DEFAULT;

ALTER TABLE public.register_float_change
    ADD COLUMN currency_code character varying(3) DEFAULT '${ledger_currency}' NOT NULL;
ALTER TABLE public.register_float_change ALTER COLUMN currency_code DROP DEFAULT;

COMMENT ON COLUMN public.register_float.currency_code IS
    'ISO 4217 code the float is held in: the ledger currency when the register''s first command created the row '
    '(#2577; ADR-0067 R-1). A float command in another currency is refused with CURRENCY_NOT_SUPPORTED.';
COMMENT ON COLUMN public.register_float_change.currency_code IS
    'ISO 4217 code of previous_amount and new_amount: the register float''s currency (#2577; ADR-0067 R-1).';
