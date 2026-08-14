-- CAP-318 / #1232: per-type uniqueness for EAN/UPC product codes (ADR-0053 §5).
--
-- Fails safe: if V8 recorded any duplicate, this migration aborts with a message that names the
-- offending values and points at the report table, rather than dropping, merging, or arbitrarily
-- picking a winner among the conflicting products. Cleanup is a catalog decision, not a migration
-- decision.

DO $$
DECLARE
    duplicate_count integer;
    sample text;
BEGIN
    SELECT COUNT(*) INTO duplicate_count FROM product_code_duplicate_report;

    IF duplicate_count > 0 THEN
        SELECT string_agg(code_type || ' ' || code_value || ' -> ' || product_ids, '; ')
        INTO sample
        FROM (
            SELECT code_type, code_value, product_ids
            FROM product_code_duplicate_report
            ORDER BY code_type, code_value
            LIMIT 10
        ) first_ten;

        RAISE EXCEPTION
            'Cannot apply uk_product_code_type_value: % duplicate EAN/UPC value(s) exist. Resolve them, then re-run. First offenders: %',
            duplicate_count, sample
            USING HINT = 'SELECT * FROM product_code_duplicate_report ORDER BY code_type, code_value;';
    END IF;
END
$$;

-- NULL code values remain unconstrained: PostgreSQL treats NULLs as distinct, so products without
-- a typed code are unaffected.
ALTER TABLE product
    ADD CONSTRAINT uk_product_code_type_value UNIQUE (product_code_type, product_code);
