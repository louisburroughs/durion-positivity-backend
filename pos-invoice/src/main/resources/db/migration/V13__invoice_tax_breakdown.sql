-- Story T5a: persist the per-line x per-jurisdiction tax matrix produced by pos-tax
-- (TaxCalculationResponse.LineItemTax.jurisdictions[]) so it can be frozen at finalization,
-- propagated on invoice.invoice.updated (taxBreakdown[]), and replicated by pos-accounting.
-- Both tables are rebuilt wholesale on each DRAFT re-price; frozen once the invoice finalizes.
-- Invoice.tax_amount is retained as the denormalized rollup (invariant: tax = SUM(tax_amount)).

CREATE TABLE invoice_line_tax (
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
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT invoice_line_tax_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_invoice_line_tax_invoice ON invoice_line_tax (invoice_id);

CREATE TABLE invoice_tax_summary (
    id uuid NOT NULL,
    invoice_id uuid NOT NULL,
    jurisdiction_type varchar(32) NOT NULL,
    jurisdiction_code varchar(64) NOT NULL,
    taxable_base numeric(19, 4) NOT NULL,
    tax_amount numeric(19, 4) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT invoice_tax_summary_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_invoice_tax_summary_invoice ON invoice_tax_summary (invoice_id);
