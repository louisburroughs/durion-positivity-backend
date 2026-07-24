-- odoo-parity G2 (#1049, spec §8 G2, plan Story G2 / decision D-5): shortage resolution records.
--
-- POST /v1/inventory/shortage/resolve executes the chosen computed option (BACKORDER / SUBSTITUTE /
-- TRANSFER_IN / EMERGENCY_PURCHASE / CANCEL_LINE) atomically and persists ONE row keyed by the
-- caller-supplied idempotency_key. A replayed request with the same key returns the stored result
-- unchanged instead of executing the option again; the unique index on idempotency_key is the
-- cross-instance backstop (mirrors the G1 backorder duplicate-open guard).
--
-- artifact_type/artifact_id reference the created artifact (a backorder, substitute reservation,
-- transfer order, or purchase suggestion). CANCEL_LINE creates no artifact (artifact_type = 'NONE',
-- artifact_id NULL). The ledger and the referenced artifact tables stay authoritative for state —
-- this table is the resolution's idempotency + audit document.
CREATE TABLE shortage_resolution_record (
    resolution_id     uuid NOT NULL,
    idempotency_key   character varying(255) NOT NULL,
    allocation_id     uuid NOT NULL,
    workorder_line_id uuid,
    sku               character varying(255) NOT NULL,
    short_quantity    integer NOT NULL,
    location_id       uuid,
    option_type       character varying(30) NOT NULL,
    status            character varying(30) NOT NULL,
    artifact_type     character varying(40) NOT NULL,
    artifact_id       uuid,
    resolved_at       timestamp(6) with time zone NOT NULL,
    created_at        timestamp(6) with time zone NOT NULL,
    updated_at        timestamp(6) with time zone NOT NULL,
    CONSTRAINT shortage_resolution_record_pkey PRIMARY KEY (resolution_id),
    CONSTRAINT shortage_resolution_option_type_check
        CHECK (option_type IN ('BACKORDER', 'SUBSTITUTE', 'TRANSFER_IN', 'EMERGENCY_PURCHASE', 'CANCEL_LINE')),
    CONSTRAINT shortage_resolution_short_quantity_check CHECK (short_quantity > 0)
);

-- Idempotency backstop: at most one resolution row per idempotency key. A replayed resolve loses
-- this unique-index race in the inner transaction and re-reads the winner's stored result.
CREATE UNIQUE INDEX ux_shortage_resolution_idempotency_key
    ON shortage_resolution_record (idempotency_key);

-- Lookup by the allocation / workorder line the resolution applied to.
CREATE INDEX idx_shortage_resolution_allocation ON shortage_resolution_record (allocation_id);
CREATE INDEX idx_shortage_resolution_workorder_line ON shortage_resolution_record (workorder_line_id);
