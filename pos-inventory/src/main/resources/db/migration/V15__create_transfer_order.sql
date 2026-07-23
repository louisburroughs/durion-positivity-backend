-- odoo-parity C1 (#1035, spec §4): cross-site transfer order aggregate.
--
-- TransferOrder + TransferOrderLine carry stock between two different sites through an
-- explicit lifecycle: DRAFT → APPROVED(optional, config flag D-8) → DISPATCHED →
-- PARTIALLY_RECEIVED → RECEIVED | SHORT_CLOSED | CANCELLED. PARTIALLY_RECEIVED and
-- SHORT_CLOSED are reachable only from parity-C2/C3 flows but are declared in the CHECK
-- constraint now so those stories need no further migration. Ledger entries posted on behalf
-- of an order (parity-C2) carry source_transaction_id = transfer_order_id.
CREATE TABLE transfer_order (
    transfer_order_id uuid NOT NULL,
    source_location_id uuid NOT NULL,
    source_storage_location_id uuid,
    destination_location_id uuid NOT NULL,
    destination_storage_location_id uuid,
    status character varying(30) NOT NULL,
    notes character varying(2000),
    created_by character varying(255) NOT NULL,
    approved_by character varying(255),
    dispatched_by character varying(255),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT transfer_order_pkey PRIMARY KEY (transfer_order_id),
    CONSTRAINT transfer_order_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'APPROVED'::character varying, 'DISPATCHED'::character varying, 'PARTIALLY_RECEIVED'::character varying, 'RECEIVED'::character varying, 'SHORT_CLOSED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT transfer_order_distinct_sites_check CHECK (source_location_id <> destination_location_id)
);

CREATE INDEX idx_transfer_order_status ON transfer_order (status);
CREATE INDEX idx_transfer_order_source ON transfer_order (source_location_id);
CREATE INDEX idx_transfer_order_destination ON transfer_order (destination_location_id);

-- Quantity invariants: 0 <= received_qty <= dispatched_qty <= requested_qty. The in-transit
-- remainder of a line is dispatched_qty - received_qty while the order is
-- DISPATCHED/PARTIALLY_RECEIVED (parity-C2).
CREATE TABLE transfer_order_line (
    line_id uuid NOT NULL,
    transfer_order_id uuid NOT NULL,
    line_number integer NOT NULL,
    sku character varying(255) NOT NULL,
    requested_qty integer NOT NULL,
    dispatched_qty integer DEFAULT 0 NOT NULL,
    received_qty integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT transfer_order_line_pkey PRIMARY KEY (line_id),
    CONSTRAINT transfer_order_line_order_fkey FOREIGN KEY (transfer_order_id) REFERENCES transfer_order (transfer_order_id) ON DELETE CASCADE,
    CONSTRAINT transfer_order_line_requested_qty_check CHECK ((requested_qty > 0)),
    CONSTRAINT transfer_order_line_qty_progress_check CHECK ((received_qty >= 0 AND dispatched_qty >= received_qty AND requested_qty >= dispatched_qty))
);

CREATE INDEX idx_transfer_order_line_order ON transfer_order_line (transfer_order_id);
CREATE INDEX idx_transfer_order_line_sku ON transfer_order_line (sku);
