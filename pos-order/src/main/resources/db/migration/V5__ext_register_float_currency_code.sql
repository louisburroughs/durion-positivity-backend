-- The register float copy states its currency (#2577; ADR-0067 R-1, R-2, R-4, PC-8).
--
-- accounting.float.changed gains currencyCode at schema version 3. ext_accounting_register_float holds the float's
-- amount, so it holds that amount's ISO 4217 code too: a drawer opens with the configured float only when the float
-- is in the drawer's own currency, never comparing amounts across currencies.
--
-- Copies written before this migration came from facts without a currency, which means the tenant's functional
-- currency (PC-8): they take it through the functional_currency Flyway placeholder (FlywayConfig, as V4 does), never
-- an implicit currency literal. New rows must state it, so the default is dropped once the backfill is done.

ALTER TABLE public.ext_accounting_register_float
    ADD COLUMN currency_code character varying(3) DEFAULT '${functional_currency}' NOT NULL;
ALTER TABLE public.ext_accounting_register_float ALTER COLUMN currency_code DROP DEFAULT;

COMMENT ON COLUMN public.ext_accounting_register_float.currency_code IS
    'ISO 4217 code of amount, from the fact''s currencyCode; the functional currency for a fact without one (#2577).';
