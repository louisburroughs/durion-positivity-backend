-- odoo-parity J4 (#1054, spec §10 WS-J; ADR-0024/ADR-0048): manual cost-revaluation workflow.
-- An operator corrects a SKU's standard price (STANDARD) or running average (AVERAGE); the
-- inventory value delta gates an approval tier reusing the D1 approval_threshold_config machinery
-- with a new REVALUATION flow. On application the sku_cost_state cost is restated and a
-- ProductValueChangedV1 fact is emitted (accounting posts the revaluation JE).

-- 1. Widen the shared approval-flow discriminator to admit the REVALUATION flow (value-based
--    thresholds, like SCRAP). Existing CYCLE_COUNT/SCRAP rows are unaffected.
ALTER TABLE approval_threshold_config DROP CONSTRAINT approval_threshold_config_flow_type_check;

ALTER TABLE approval_threshold_config ADD CONSTRAINT approval_threshold_config_flow_type_check
    CHECK (((flow_type)::text = ANY ((ARRAY[
        'CYCLE_COUNT'::character varying,
        'SCRAP'::character varying,
        'REVALUATION'::character varying])::text[])));

-- Revaluation flow thresholds (value-based via |Δunit-cost| × on-hand; unit/percentage columns
-- are NOT NULL for the shared table but unused by revaluation evaluation — zeroes). A delta at or
-- above $100 needs manager approval, $1000 needs director approval; below $100 auto-applies.
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
    ('01980001-0000-7000-8000-000000000031'::uuid, 'REVALUATION', 'TIER_1_MANAGER', 0, 100, 0, TRUE, NOW(), NOW()),
    ('01980001-0000-7000-8000-000000000032'::uuid, 'REVALUATION', 'TIER_2_DIRECTOR', 0, 1000, 0, TRUE, NOW(), NOW());

-- 2. Revaluation document + who/when/old/new audit log (ADR-0024). One append-only row per
--    revaluation: workflow status plus the immutable old/new cost, on-hand basis, value delta,
--    reason, and actor. previous_unit_cost is NULL when the SKU carried no prior cost of the
--    resolved method. costing_method and cost columns share sku_cost_state scales (6 for cost,
--    4 for the money delta).
CREATE TABLE revaluation_record (
    revaluation_id uuid NOT NULL,
    stock_item_id character varying(255) NOT NULL,
    costing_method character varying(32) NOT NULL,
    previous_unit_cost numeric(19,6),
    new_unit_cost numeric(19,6) NOT NULL,
    on_hand_quantity bigint NOT NULL,
    value_delta numeric(19,4) NOT NULL,
    reason character varying(1000) NOT NULL,
    status character varying(30) NOT NULL,
    required_approval_tier character varying(30),
    created_by character varying(255) NOT NULL,
    approved_by character varying(255),
    rejected_by character varying(255),
    rejection_reason character varying(1000),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    applied_at timestamp(6) with time zone,
    rejected_at timestamp(6) with time zone,
    CONSTRAINT revaluation_record_pkey PRIMARY KEY (revaluation_id),
    CONSTRAINT revaluation_record_costing_method_check
        CHECK (((costing_method)::text = ANY ((ARRAY['STANDARD'::character varying, 'AVERAGE'::character varying])::text[]))),
    CONSTRAINT revaluation_record_status_check
        CHECK (((status)::text = ANY ((ARRAY[
            'PENDING_APPROVAL'::character varying,
            'AUTO_APPLIED'::character varying,
            'APPLIED'::character varying,
            'REJECTED'::character varying])::text[]))),
    CONSTRAINT revaluation_record_required_approval_tier_check
        CHECK ((required_approval_tier IS NULL OR (required_approval_tier)::text = ANY ((ARRAY['TIER_1_MANAGER'::character varying, 'TIER_2_DIRECTOR'::character varying])::text[])))
);

CREATE INDEX idx_revaluation_record_status ON revaluation_record (status);
CREATE INDEX idx_revaluation_record_stock_item ON revaluation_record (stock_item_id);
