-- ADR-0044 §6 (#924): read-only workorder + invoice replicas fed by workorder.events.v1 and
-- invoice.events.v1. Replace the synchronous WorkorderClient detail search and
-- InvoiceClient.searchInvoiceLines read paths used by candidate-line origin search.
-- H2(MODE=PostgreSQL)-compatible like V1 (no Postgres-only syntax).

-- workorder header replica
CREATE TABLE ext_workorder (
    workorder_id uuid NOT NULL,
    workorder_number character varying(64),
    status character varying(64),
    customer_id uuid,
    vehicle_id uuid,
    invoice_id uuid,
    workorder_created_at timestamp(6) with time zone,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_workorder_pkey PRIMARY KEY (workorder_id)
);
CREATE INDEX idx_ext_workorder_customer ON ext_workorder (customer_id);
CREATE INDEX idx_ext_workorder_vehicle ON ext_workorder (vehicle_id);

-- workorder line replica (parts + service lines, full replacement set per fact)
CREATE TABLE ext_workorder_line (
    workorder_line_id uuid NOT NULL,
    workorder_id uuid NOT NULL,
    line_kind character varying(16) NOT NULL,
    product_entity_id uuid,
    description character varying(512),
    quantity numeric(19, 4),
    unit_price numeric(19, 4),
    line_total numeric(19, 4),
    photo_evidence_url character varying(1024),
    CONSTRAINT ext_workorder_line_pkey PRIMARY KEY (workorder_line_id)
);
CREATE INDEX idx_ext_workorder_line_wo ON ext_workorder_line (workorder_id);

-- invoice header replica
CREATE TABLE ext_invoice (
    invoice_id uuid NOT NULL,
    invoice_number character varying(64),
    party_id character varying(64),
    status character varying(64),
    invoice_created_at timestamp(6) with time zone,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_invoice_pkey PRIMARY KEY (invoice_id)
);
CREATE INDEX idx_ext_invoice_party ON ext_invoice (party_id);

-- invoice line replica (full replacement set per fact)
CREATE TABLE ext_invoice_line (
    invoice_item_id uuid NOT NULL,
    invoice_id uuid NOT NULL,
    description character varying(512),
    quantity numeric(19, 4),
    unit_price numeric(19, 4),
    amount numeric(19, 4),
    workorder_item_id uuid,
    item_type character varying(32),
    CONSTRAINT ext_invoice_line_pkey PRIMARY KEY (invoice_item_id)
);
CREATE INDEX idx_ext_invoice_line_inv ON ext_invoice_line (invoice_id);
