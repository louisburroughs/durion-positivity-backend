-- ADR-0044 R3: read-only replica of pos-invoice's invoice documents (issue #842).
-- Fed by invoice.events.v1 (invoice.invoice.updated); pos-invoice remains the owner.
-- Accounting derives AR balance due from this replica plus its own payment
-- applications / credit memos — the replica deliberately carries no balance column.
CREATE TABLE ext_invoice (
    invoice_id uuid NOT NULL,
    invoice_number character varying(64),
    workorder_id uuid NOT NULL,
    estimate_id uuid,
    location_id uuid,
    party_id character varying(64),
    status character varying(20) NOT NULL,
    subtotal numeric(19, 4),
    tax numeric(19, 4),
    total numeric(19, 4),
    adjustments_amount numeric(19, 4),
    invoice_created_at timestamp(6) with time zone,
    finalized_at timestamp(6) with time zone,
    aggregate_version bigint NOT NULL DEFAULT 0,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_invoice_pkey PRIMARY KEY (invoice_id)
);

CREATE INDEX idx_ext_invoice_workorder ON ext_invoice (workorder_id);
