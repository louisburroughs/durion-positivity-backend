-- odoo-parity D1 (#1030, spec §5): scrap/write-off workflow.
--
-- 1. approval_threshold_config gains a flow discriminator: the table was keyed
--    by tier alone while cycle-count adjustments were its only consumer. Scrap
--    documents reuse the tier mechanism with scrap-specific, value-based
--    thresholds, so uniqueness becomes (flow_type, approval_tier). Existing
--    rows are cycle-count rows.
ALTER TABLE approval_threshold_config ADD COLUMN flow_type character varying(30) DEFAULT 'CYCLE_COUNT' NOT NULL;

ALTER TABLE approval_threshold_config ADD CONSTRAINT approval_threshold_config_flow_type_check CHECK (((flow_type)::text = ANY ((ARRAY['CYCLE_COUNT'::character varying, 'SCRAP'::character varying])::text[])));

ALTER TABLE approval_threshold_config DROP CONSTRAINT approval_threshold_config_approval_tier_key;

ALTER TABLE approval_threshold_config ADD CONSTRAINT approval_threshold_config_flow_tier_key UNIQUE (flow_type, approval_tier);

-- Scrap flow thresholds (value-based via cost snapshot; unit/percentage columns
-- are NOT NULL for the shared table but unused by scrap evaluation — zeroes).
INSERT INTO approval_threshold_config (
    config_id,
    flow_type,
    approval_tier,
    unit_variance_threshold,
    value_variance_threshold,
    percentage_variance_threshold,
    active,
    created_at,
    updated_at
)
VALUES
    ('01980001-0000-7000-8000-000000000001'::uuid, 'SCRAP', 'TIER_1_MANAGER', 0, 100, 0, TRUE, NOW(), NOW()),
    ('01980001-0000-7000-8000-000000000002'::uuid, 'SCRAP', 'TIER_2_DIRECTOR', 0, 1000, 0, TRUE, NOW(), NOW());

-- 2. Scrap document (Odoo stock.scrap analog): lifecycle from proposal through
--    value-threshold approval to the SCRAP_OUT ledger posting. The posted
--    ledger entry carries source_transaction_id = scrap_id. lot_id and
--    serial_number are reserved columns wired by the WS-E stories.
CREATE TABLE scrap_record (
    scrap_id uuid NOT NULL,
    stock_item_id character varying(255) NOT NULL,
    quantity integer NOT NULL,
    location_id uuid NOT NULL,
    storage_location_id uuid,
    reason_code character varying(50) NOT NULL,
    notes character varying(2000),
    workorder_id uuid,
    lot_id uuid,
    serial_number character varying(100),
    attachment_reference character varying(500),
    unit_cost_snapshot numeric(19,4),
    cost_source character varying(30) NOT NULL,
    should_replenish boolean NOT NULL,
    status character varying(30) NOT NULL,
    required_approval_tier character varying(30),
    created_by character varying(255) NOT NULL,
    approved_by character varying(255),
    rejected_by character varying(255),
    rejection_reason character varying(1000),
    ledger_entry_id uuid,
    error_message character varying(2000),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    approved_at timestamp(6) with time zone,
    rejected_at timestamp(6) with time zone,
    posted_at timestamp(6) with time zone,
    CONSTRAINT scrap_record_pkey PRIMARY KEY (scrap_id),
    CONSTRAINT scrap_record_quantity_check CHECK ((quantity > 0)),
    CONSTRAINT scrap_record_reason_code_check CHECK (((reason_code)::text = ANY ((ARRAY['DAMAGED'::character varying, 'EXPIRED'::character varying, 'LOST'::character varying, 'RECALLED'::character varying, 'CONTAMINATED'::character varying, 'WARRANTY_DESTROYED'::character varying, 'OTHER'::character varying])::text[]))),
    CONSTRAINT scrap_record_cost_source_check CHECK (((cost_source)::text = ANY ((ARRAY['LATEST_RECEIPT'::character varying, 'NONE'::character varying])::text[]))),
    CONSTRAINT scrap_record_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING_APPROVAL'::character varying, 'AUTO_APPROVED'::character varying, 'APPROVED'::character varying, 'POSTED'::character varying, 'REJECTED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT scrap_record_required_approval_tier_check CHECK ((required_approval_tier IS NULL OR (required_approval_tier)::text = ANY ((ARRAY['TIER_1_MANAGER'::character varying, 'TIER_2_DIRECTOR'::character varying])::text[])))
);

CREATE INDEX idx_scrap_record_status ON scrap_record (status);
CREATE INDEX idx_scrap_record_stock_item ON scrap_record (stock_item_id);
CREATE INDEX idx_scrap_record_location ON scrap_record (location_id);
