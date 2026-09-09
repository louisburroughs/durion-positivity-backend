-- CAP-321 #1227: vendor AR documents fetched over EDIWheel Invoice B3.3.
--
-- The vendor's own figures, stored as sent. Nothing here is recalculated: the totals a vendor
-- states are what it will expect to be paid, and an integration that quietly corrects them produces
-- a number nobody can trace back to a document.

CREATE TABLE supplier_invoice (
    supplier_invoice_id   uuid PRIMARY KEY,
    vendor_profile_id     uuid NOT NULL,
    supplier_ref          varchar(100) NOT NULL,

    -- The vendor's natural identity for the document. Fetch windows are allowed to overlap on
    -- re-run (ADR-0052 §5), so the same invoice arrives more than once by design; this triple is
    -- what makes the second arrival a no-op rather than a duplicate debt.
    vendor_invoice_number varchar(70) NOT NULL,
    invoice_date          date NOT NULL,

    -- INVOICE or CREDIT_NOTE. Which way the money runs, decided from the vendor's document type
    -- code and never defaulted: importing a credit as a debt reaches a payment run.
    invoice_type          varchar(16) NOT NULL,

    currency              varchar(3) NOT NULL,
    total_net_amount      numeric(19, 4),
    total_tax_amount      numeric(19, 4),
    total_gross_amount    numeric(19, 4),

    -- The vendor's order reference where it gave one; the link to a purchase order is resolved by
    -- the consumer, not here.
    vendor_order_reference varchar(70),

    fetched_at            timestamp(6) with time zone NOT NULL,
    created_at            timestamp(6) with time zone NOT NULL,
    updated_at            timestamp(6) with time zone NOT NULL,

    CONSTRAINT supplier_invoice_identity
        UNIQUE (vendor_profile_id, vendor_invoice_number, invoice_date),
    CONSTRAINT supplier_invoice_type_check
        CHECK (invoice_type IN ('INVOICE', 'CREDIT_NOTE'))
);

CREATE INDEX idx_supplier_invoice_profile_date ON supplier_invoice (vendor_profile_id, invoice_date);

CREATE TABLE supplier_invoice_line (
    supplier_invoice_line_id uuid PRIMARY KEY,
    supplier_invoice_id      uuid NOT NULL REFERENCES supplier_invoice (supplier_invoice_id),
    line_number              integer NOT NULL,
    article_ean              varchar(64),
    supplier_article_code    varchar(64),
    quantity                 integer,
    unit_price               numeric(19, 4),
    line_amount              numeric(19, 4),
    vendor_order_reference   varchar(70),
    created_at               timestamp(6) with time zone NOT NULL
);

CREATE INDEX idx_supplier_invoice_line_invoice ON supplier_invoice_line (supplier_invoice_id);
