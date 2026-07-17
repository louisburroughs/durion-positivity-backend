-- ADR-0044 §6 (#900): read-only invoice replica fed by invoice.invoice.updated facts on
-- invoice.events.v1. Replaces the synchronous InvoiceClient response/read path and resolves
-- the async invoice-generation pending state by linking workorder.invoice_id.
CREATE TABLE ext_invoice (
    invoice_id uuid NOT NULL,
    workorder_id uuid,
    invoice_number varchar(64),
    status varchar(64),
    subtotal numeric(19, 4),
    tax numeric(19, 4),
    total numeric(19, 4),
    invoice_created_at timestamp(6) with time zone,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_invoice_pkey PRIMARY KEY (invoice_id)
);
CREATE INDEX idx_ext_invoice_workorder ON ext_invoice (workorder_id);
