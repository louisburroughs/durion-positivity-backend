-- #2501 (CAP:550 S0; ADR-0070 Decision 4; SPEC-accounting-workspace §4.9): one duplicate rule for
-- vendor bills. Two bills are duplicates when they share the tenant, the vendor id as stored, the
-- normalised bill number (bill_number_key) and the calendar date of bill_date, and neither is VOIDED
-- or REJECTED. A partial unique index enforces it.
--
-- Flyway runs this file in one transaction: if the guard in step 4 raises, nothing here has
-- happened, the new column included.
--
-- Tenancy: vendor_bill is under FORCE ROW LEVEL SECURITY. Flyway runs as the owning superuser, which
-- row-level security does not restrict (V3 records the same assumption), so the backfill and the
-- guard see every tenant. Under an owner that is not a superuser the backfill would reach only the
-- bound tenant's rows and step 3 would then fail on the rest, loudly, rather than index half a table.
-- modified_at is left alone: the key is derived from bill_number, it is not an edit of the bill (and
-- ADR-0024 forbids SQL-clock writes).
--
-- Recorded limit of the backfill in step 2: the SQL expression and the Java normaliser
-- (VendorBillNumbers.normalise) agree for every bill number whose NFKC form is ASCII. For a non-ASCII
-- number the one-time backfill may give a different key than Java would: upper() may leave a
-- character such as 'ß' alone where Java writes 'SS', and [[:alnum:]] classifies non-ASCII characters
-- by the database's locale. Accepted because alpha held 0 vendor_bill rows when checked on 2026-10-05. A
-- database that holds non-ASCII bill numbers before V4 runs must have them reviewed first.

-- No sequence is created here. Goods-receipt bill numbers used to come from a database sequence,
-- bill_number_seq, that no migration of the flattened baseline created, so POST
-- /v1/accounting/vendor-bills failed at nextval on every Postgres database. By the platform owner's
-- ruling of 2026-10-05 (ADR-0062 section 9: generated accounting numbers come from per-tenant
-- sequences, not a shared database sequence) they now come from the tenant's own accounting_sequence
-- counter, scope BILL-<YYYYMM>, whose row is created on first use. That table already exists and
-- needs no change, so this migration adds nothing for numbering.

-- 1. The stored key.
ALTER TABLE public.vendor_bill ADD COLUMN bill_number_key character varying(255);

-- 2. Backfill, once. From here on the Java normaliser (VendorBillNumbers.normalise, called by
--    VendorBill.setBillNumber) is the key's only producer; this expression is its SQL twin: NFKC,
--    upper case, letters and digits only, leading zeros removed while more than one character
--    remains (the look-ahead), at most 255 characters.
UPDATE public.vendor_bill
SET bill_number_key = left(
        regexp_replace(
            regexp_replace(upper(normalize(bill_number, NFKC)), '[^[:alnum:]]', '', 'g'),
            '^0+(?=.)',
            ''),
        255);

-- 3. Every bill has a key.
ALTER TABLE public.vendor_bill ALTER COLUMN bill_number_key SET NOT NULL;

-- 4. Guard. Rows that already break the rule are a decision for a person (void the extra bill, or
--    reset a pre-production database); this migration never edits a bill. It stops instead, naming
--    how many groups there are and the first ten.
DO $$
DECLARE
    violating_groups bigint;
    first_groups text;
BEGIN
    SELECT count(*)
    INTO violating_groups
    FROM (
        SELECT 1
        FROM public.vendor_bill
        WHERE (status)::text <> ALL (ARRAY['VOIDED'::text, 'REJECTED'::text])
        GROUP BY tenant_id, vendor_id, bill_number_key, (bill_date)::date
        HAVING count(*) > 1) violations;

    IF violating_groups > 0 THEN
        SELECT string_agg(
                   format('(tenant %s, vendor %s, key "%s", date %s: %s bills)',
                          g.tenant_id, g.vendor_id, g.bill_number_key, g.bill_day, g.bills),
                   '; ' ORDER BY g.tenant_id, g.vendor_id, g.bill_number_key, g.bill_day)
        INTO first_groups
        FROM (
            SELECT tenant_id, vendor_id, bill_number_key, (bill_date)::date AS bill_day, count(*) AS bills
            FROM public.vendor_bill
            WHERE (status)::text <> ALL (ARRAY['VOIDED'::text, 'REJECTED'::text])
            GROUP BY tenant_id, vendor_id, bill_number_key, (bill_date)::date
            HAVING count(*) > 1
            ORDER BY tenant_id, vendor_id, bill_number_key, (bill_date)::date
            LIMIT 10) g;

        RAISE EXCEPTION
            'V4 vendor bill duplicate rule: % group(s) of live vendor bills share tenant, vendor, normalised bill number and bill date. First groups: %. Void the extra bills (or reset the database) and run the migration again; it never edits a bill.',
            violating_groups, first_groups;
    END IF;
END
$$;

-- 5. The rule. Leads with tenant_id (TENANCY_SCHEMA.md, "Adding a table" step 2). bill_date is
--    timestamp without time zone, so its cast to date is immutable and the expression is indexable.
--    Entering VOIDED or REJECTED releases the key, so a re-issue after a void goes through; a status
--    added later (AWAITING_APPROVAL) is covered without touching the index.
CREATE UNIQUE INDEX uq_vendor_bill_duplicate_rule ON public.vendor_bill USING btree (tenant_id, vendor_id, bill_number_key, ((bill_date)::date)) WHERE ((status)::text <> ALL (ARRAY['VOIDED'::text, 'REJECTED'::text]));
