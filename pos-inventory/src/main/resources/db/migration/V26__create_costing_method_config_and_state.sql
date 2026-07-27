-- odoo-parity J1 (#1048, spec §10 WS-J; ADR-0048): inventory-owned, configurable
-- costing engine. Adds the per-SKU running cost state, the per-scope costing-method
-- configuration (mirroring V17 sourcing_strategy_config), and the who/when/from/to
-- method-change history. J1 does NOT touch inventory_stock_summary (value columns are J2).

-- 1. Per-SKU running cost state. One row per stock item; the ledger posting funnel
--    updates it in the same transaction as every on-hand-affecting ledger append.
--    avg_cost: running weighted average (AVERAGE) or latest-receipt-cost memo (STANDARD),
--    scale 6 for precision across incremental AVCO recomputes (stamped to the ledger's
--    scale 4). on_hand_qty: quantity costed so far (AVCO denominator + negative-branch
--    input). standard_cost: configured standard price (STANDARD), null until set via the
--    J4 revaluation workflow.
CREATE TABLE sku_cost_state (
    cost_state_id uuid NOT NULL,
    stock_item_id character varying(255) NOT NULL,
    avg_cost numeric(19, 6),
    on_hand_qty bigint NOT NULL,
    standard_cost numeric(19, 6),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT sku_cost_state_pkey PRIMARY KEY (cost_state_id)
);

-- Natural unique key: one running cost state per stock item (ADR-0013 requires a UUIDv7
-- surrogate id, so the SKU is a unique column rather than the physical primary key).
CREATE UNIQUE INDEX uq_sku_cost_state_stock_item ON sku_cost_state (stock_item_id);

-- 2. Costing-method configuration: one row per scope. Resolution precedence is
--    SKU -> SKU_CATEGORY -> DEFAULT -> deployment default
--    pos.inventory.valuation.default-method. scope_value is the stock item id (SKU),
--    the category string (SKU_CATEGORY), or NULL (DEFAULT). Rows are deactivated, never
--    hard-deleted. v1 methods are STANDARD and AVERAGE; FIFO (v2, ADR-0048) widens the
--    check constraint when its strategy ships.
CREATE TABLE sku_cost_method_config (
    config_id uuid NOT NULL,
    scope_type character varying(32) NOT NULL,
    scope_value character varying(255),
    method character varying(32) NOT NULL,
    active boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT sku_cost_method_config_pkey PRIMARY KEY (config_id),
    CONSTRAINT sku_cost_method_config_scope_type_check
        CHECK (scope_type IN ('SKU', 'SKU_CATEGORY', 'DEFAULT')),
    CONSTRAINT sku_cost_method_config_method_check
        CHECK (method IN ('STANDARD', 'AVERAGE')),
    -- DEFAULT scope is platform-wide and must not carry a scope value.
    CONSTRAINT sku_cost_method_config_default_scope_check
        CHECK (scope_type <> 'DEFAULT' OR scope_value IS NULL)
);

-- One config row per scope (upsert target). COALESCE folds the NULL scope_value of the
-- DEFAULT scope into a single unique key.
CREATE UNIQUE INDEX uq_sku_cost_method_config_scope
    ON sku_cost_method_config (scope_type, COALESCE(scope_value, ''));

CREATE INDEX idx_sku_cost_method_config_scope
    ON sku_cost_method_config (scope_type, scope_value);

-- 3. Append-only method-change history (ADR-0048: who/when/from/to). One row per method
--    change written by the admin upsert. from_method is NULL on first configuration.
CREATE TABLE cost_method_change_log (
    change_id uuid NOT NULL,
    scope_type character varying(32) NOT NULL,
    scope_value character varying(255),
    from_method character varying(32),
    to_method character varying(32) NOT NULL,
    changed_by character varying(255) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT cost_method_change_log_pkey PRIMARY KEY (change_id),
    CONSTRAINT cost_method_change_log_scope_type_check
        CHECK (scope_type IN ('SKU', 'SKU_CATEGORY', 'DEFAULT')),
    CONSTRAINT cost_method_change_log_from_method_check
        CHECK (from_method IS NULL OR from_method IN ('STANDARD', 'AVERAGE')),
    CONSTRAINT cost_method_change_log_to_method_check
        CHECK (to_method IN ('STANDARD', 'AVERAGE'))
);

CREATE INDEX idx_cost_method_change_log_scope
    ON cost_method_change_log (scope_type, scope_value);
