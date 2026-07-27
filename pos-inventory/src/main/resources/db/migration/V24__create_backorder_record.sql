-- odoo-parity G1 (#1046, spec §8 G1, plan Story G1): backorder records.
--
-- A backorder_record is unfulfilled workorder-line demand held OPEN until availability covers it.
-- Opening posts the ATP-neutral BACKORDER_CREATED ledger entry (sourceTransactionId = backorder_id)
-- and a BackorderCreatedV1 fact; auto-resolution (oldest-priority-first, gated on current ATP)
-- posts BACKORDER_RESOLVED and a BackorderResolvedV1 fact. The ledger stays authoritative for
-- quantity — this table is a lifecycle document keyed by (workorder_line_id, sku).
--
-- resolution_source is set only on resolve (AVAILABILITY for the auto path; REPLENISHMENT_RECEIPT
-- and MANUAL reserved for later paths). location_id is the site matched by the availability trigger.
CREATE TABLE backorder_record (
    backorder_id             uuid NOT NULL,
    workorder_line_id        uuid NOT NULL,
    sku                      character varying(255) NOT NULL,
    location_id              uuid,
    quantity_short           integer NOT NULL,
    status                   character varying(30) NOT NULL,
    resolution_source        character varying(30),
    created_by               character varying(255),
    created_ledger_entry_id  uuid,
    resolved_ledger_entry_id uuid,
    created_at               timestamp(6) with time zone NOT NULL,
    updated_at               timestamp(6) with time zone NOT NULL,
    resolved_at              timestamp(6) with time zone,
    CONSTRAINT backorder_record_pkey PRIMARY KEY (backorder_id),
    CONSTRAINT backorder_record_status_check
        CHECK (status IN ('OPEN', 'RESOLVED', 'CANCELLED')),
    CONSTRAINT backorder_record_resolution_source_check
        CHECK (resolution_source IS NULL
               OR resolution_source IN ('AVAILABILITY', 'REPLENISHMENT_RECEIPT', 'MANUAL')),
    CONSTRAINT backorder_record_quantity_check CHECK (quantity_short > 0)
);

-- Duplicate-open guard: at most one OPEN backorder per (workorder_line_id, sku). Partial index so
-- resolved/cancelled history never blocks a fresh open.
CREATE UNIQUE INDEX ux_backorder_record_open_line_sku
    ON backorder_record (workorder_line_id, sku)
    WHERE status = 'OPEN';

-- Auto-resolution candidate scan: OPEN backorders for one SKU at one site.
CREATE INDEX idx_backorder_record_sku_location_status
    ON backorder_record (sku, location_id, status);

-- List-endpoint / lifecycle filters.
CREATE INDEX idx_backorder_record_status ON backorder_record (status);
CREATE INDEX idx_backorder_record_workorder_line ON backorder_record (workorder_line_id);
