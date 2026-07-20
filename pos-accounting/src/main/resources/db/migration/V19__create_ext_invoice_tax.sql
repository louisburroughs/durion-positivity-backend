-- Story T5c (ADR-0044 R3, owner = invoice): read-only replica of pos-invoice's per-line x
-- per-jurisdiction tax breakdown, materialized from invoice.invoice.updated (taxBreakdown[]).
-- Detail rows carry no owner-assigned id, so a local UUIDv7 PK is generated per row. Rows are
-- replaced wholesale for an invoice on each non-stale event that carries a breakdown; ext_invoice
-- keeps the scalar tax rollup. Accounting plan D1 / T8 read this replica.
--
-- FLYWAY COLLISION NOTE: numbered V19 as the next contiguous version after V18. Unmerged
-- accounting-plan branches may also claim a V1x on pos-accounting; if they merge first this file
-- must be renumbered on rebase.
CREATE TABLE ext_invoice_tax (
    id uuid NOT NULL,
    invoice_id uuid NOT NULL,
    line_item_id varchar(64),
    jurisdiction_type varchar(32) NOT NULL,
    jurisdiction_code varchar(64) NOT NULL,
    rate numeric(9, 6) NOT NULL,
    taxable_base numeric(19, 4) NOT NULL,
    tax_amount numeric(19, 4) NOT NULL,
    exempt boolean NOT NULL DEFAULT false,
    exemption_reason_code varchar(32),
    aggregate_version bigint NOT NULL DEFAULT 0,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_invoice_tax_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_ext_invoice_tax_invoice ON ext_invoice_tax (invoice_id);
