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
--   * schemaVersion becomes 2;
--   * last_error becomes NULL (an error text may quote the payload).
-- Rows of every other event type are untouched, byte for byte. Published and unpublished rows are both
-- rewritten; published_at and attempts are kept, so nothing is re-sent by this migration.
--
-- supplier_event_outbox is a global table (db/tenancy-global-tables.txt): no row-level security applies.

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
