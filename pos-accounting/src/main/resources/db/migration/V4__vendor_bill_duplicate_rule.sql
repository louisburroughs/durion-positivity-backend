-- #2501 (CAP:550 S0; ADR-0070 Decision 4; SPEC-accounting-workspace §4.9): one duplicate rule for
-- vendor bills. Two bills are duplicates when they share the tenant, the vendor id as stored, the
-- normalised bill number (bill_number_key) and the calendar date of bill_date, and neither is VOIDED
-- or REJECTED. A partial unique index enforces it.
--
-- Flyway runs this file in one transaction: if the guard in step 5 raises, nothing here has
-- happened, the new column included.
--
-- Tenancy: vendor_bill is under FORCE ROW LEVEL SECURITY. Flyway runs as the owning superuser, which
-- row-level security does not restrict (V3 records the same assumption), so the backfill and the
-- guard see every tenant. Under an owner that is not a superuser the backfill would reach only the
-- bound tenant's rows and step 4 would then fail on the rest, loudly, rather than index half a table.
-- modified_at is left alone: the key is derived from bill_number, it is not an edit of the bill (and
-- ADR-0024 forbids SQL-clock writes).

-- 1. The sequence the goods-receipt path draws bill numbers from. The flattened baseline never
--    created it, so POST /v1/accounting/vendor-bills failed at nextval on every Postgres database.
--    Global, not per tenant: it carries no tenant data. pos_app gets USAGE through the default
--    privileges postgres/init-tenancy.sh sets for the owner's sequences.
CREATE SEQUENCE IF NOT EXISTS bill_number_seq START WITH 1 INCREMENT BY 1 NO CYCLE;

-- 2. The stored key.
ALTER TABLE public.vendor_bill ADD COLUMN bill_number_key character varying(255);

-- 3. Backfill, once. From here on the Java normaliser (VendorBillNumbers.normalise, called by
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

-- 4. Every bill has a key.
ALTER TABLE public.vendor_bill ALTER COLUMN bill_number_key SET NOT NULL;

-- 5. Guard. Rows that already break the rule are a decision for a person (void the extra bill, or
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

-- 6. The rule. Leads with tenant_id (TENANCY_SCHEMA.md, "Adding a table" step 2). bill_date is
--    timestamp without time zone, so its cast to date is immutable and the expression is indexable.
--    Entering VOIDED or REJECTED releases the key, so a re-issue after a void goes through; a status
--    added later (AWAITING_APPROVAL) is covered without touching the index.
CREATE UNIQUE INDEX uq_vendor_bill_duplicate_rule ON public.vendor_bill USING btree (tenant_id, vendor_id, bill_number_key, ((bill_date)::date)) WHERE ((status)::text <> ALL (ARRAY['VOIDED'::text, 'REJECTED'::text]));
