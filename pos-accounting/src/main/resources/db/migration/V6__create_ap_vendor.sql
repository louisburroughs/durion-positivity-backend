-- AP vendor directory (Issue #816).
-- Provides a searchable vendor name -> vendorId directory for the frontend
-- vendor-payment typeahead. Rows are upserted from AP flows that carry
-- vendorId + vendorName (goods-received events) and backfilled below from
-- existing vendor bills / payments.

CREATE TABLE ap_vendor (
    vendor_id uuid NOT NULL,
    name character varying(200) NOT NULL,
    vendor_number character varying(50),
    status character varying(20) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ap_vendor_pkey PRIMARY KEY (vendor_id),
    CONSTRAINT ap_vendor_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))
);

-- Plain btree on name: supports ORDER BY name and matches the JPA entity's
-- declared index (contains-style typeahead matching can't use a btree either
-- way; a trigram index can be added later if search volume warrants it).
CREATE INDEX idx_ap_vendor_name ON ap_vendor USING btree (name);

-- Backfill from existing vendor bills (most recent name per vendor wins).
INSERT INTO ap_vendor (vendor_id, name, status, created_at, updated_at)
SELECT DISTINCT ON (vendor_id) vendor_id, vendor_name, 'ACTIVE', now(), now()
FROM vendor_bill
WHERE vendor_name IS NOT NULL AND vendor_name <> ''
ORDER BY vendor_id, created_at DESC;

-- Backfill vendors that only appear on AP payments.
INSERT INTO ap_vendor (vendor_id, name, status, created_at, updated_at)
SELECT DISTINCT ON (vendor_id) vendor_id, vendor_name, 'ACTIVE', now(), now()
FROM ap_payment
WHERE vendor_name IS NOT NULL AND vendor_name <> ''
ORDER BY vendor_id, created_at DESC
ON CONFLICT (vendor_id) DO NOTHING;
