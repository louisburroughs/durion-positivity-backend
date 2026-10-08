-- durion-positivity-backend#2621 (CAP:550; Security ruling on #2617, rulings 1, 2 and 6): every
-- supplier_event_outbox row of supplier.vendor.updated is rewritten to the schema-version-2 shape, so neither
-- a pending row nor a supplier.outbox.replay-requested window can ever publish a full tax-registration number
-- again. V4 (a Java migration: it needs the key) has already encrypted the vendor master itself.
--
-- For each such row, through payload::jsonb and back to text:
--   * every payload.taxRegistrations[*] element that has a "number" loses it and gains "last4" by the
--     ruling-2 rule, the same rule VendorTaxRegistration.last4Of applies: remove every character that is not
--     an ASCII letter or digit, keep the right 4, or null when fewer than 8 remain. An element without
--     "number" (already version 2) is left as it is;
--   * on those same elements, "scheme" and "region" are trimmed and upper-cased, as the API now stores them;
--   * schemaVersion becomes 2;
--   * last_error becomes NULL (an error text may quote the payload).
-- Rows of every other event type are untouched, byte for byte. Published and unpublished rows are both
-- rewritten; published_at and attempts are kept, so nothing is re-sent by this migration.
--
-- supplier_event_outbox is a global table (db/tenancy-global-tables.txt): no row-level security applies.
--
-- Shapes first, counts only (ADR-0072 Decision 2; Security confirmation on louisburroughs/durion#571; #2621
-- item 15). The scrub must never copy a non-conforming scheme or region onto a version 2 fact beside last4,
-- so if any element carrying a number has a scheme outside ^[A-Z][A-Z _/-]{0,15}$ or a region outside
-- ^[A-Z]{2}(-[A-Z]{1,3})?$ (after trim and upper-casing), the migration stops with the count and no value.

DO $shapes$
DECLARE
    misshapen bigint;
BEGIN
    SELECT count(*)
    INTO misshapen
    FROM public.supplier_event_outbox o,
         jsonb_array_elements(
             CASE
                 WHEN jsonb_typeof(o.payload::jsonb -> 'payload' -> 'taxRegistrations') = 'array'
                     THEN o.payload::jsonb -> 'payload' -> 'taxRegistrations'
                 ELSE '[]'::jsonb
             END) AS t(element)
    WHERE o.event_type = 'supplier.vendor.updated'
      AND t.element ? 'number'
      AND (upper(btrim(COALESCE(t.element ->> 'scheme', ''))) !~ '^[A-Z][A-Z _/-]{0,15}$'
           OR (t.element ->> 'region' IS NOT NULL
               AND upper(btrim(t.element ->> 'region')) !~ '^[A-Z]{2}(-[A-Z]{1,3})?$'));
    IF misshapen > 0 THEN
        RAISE EXCEPTION 'V5: % supplier.vendor.updated outbox registration(s) have a scheme or region that breaks the ADR-0072 Decision 2 shape; correct them before scrubbing (#2621)', misshapen;
    END IF;
END
$shapes$;

UPDATE public.supplier_event_outbox o
SET payload = jsonb_set(
        jsonb_set(o.payload::jsonb, '{schemaVersion}', '2'::jsonb),
        '{payload,taxRegistrations}',
        CASE
            WHEN jsonb_typeof(o.payload::jsonb -> 'payload' -> 'taxRegistrations') = 'array' THEN
                COALESCE(
                    (SELECT jsonb_agg(
                                CASE
                                    WHEN t.element ? 'number' THEN
                                        (t.element - 'number') || jsonb_build_object(
                                            'scheme', upper(btrim(t.element ->> 'scheme')),
                                            'region', upper(btrim(t.element ->> 'region')),
                                            'last4',
                                            CASE
                                                WHEN length(regexp_replace(
                                                        COALESCE(t.element ->> 'number', ''),
                                                        '[^A-Za-z0-9]', '', 'g')) >= 8
                                                    THEN right(regexp_replace(
                                                        t.element ->> 'number', '[^A-Za-z0-9]', '', 'g'), 4)
                                            END)
                                    ELSE t.element
                                END
                                ORDER BY t.position)
                     FROM jsonb_array_elements(o.payload::jsonb -> 'payload' -> 'taxRegistrations')
                          WITH ORDINALITY AS t(element, position)),
                    '[]'::jsonb)
            ELSE '[]'::jsonb
        END)::text,
    last_error = NULL
WHERE o.event_type = 'supplier.vendor.updated';
