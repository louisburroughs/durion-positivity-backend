-- odoo-parity H1/H2 (#1037, spec §9 WS-H): configurable removal/sourcing
-- strategy engine (Odoo _get_removal_strategy analog).
--
-- 1. Strategy configuration: one row per scope. Resolution precedence is
--    SKU_CATEGORY -> SITE -> DEFAULT -> platform default FIFO. scope_value is
--    the category string (SKU_CATEGORY), the site UUID as text (SITE), or
--    NULL (DEFAULT). Rows are deactivated, never hard-deleted.
CREATE TABLE sourcing_strategy_config (
    config_id uuid NOT NULL,
    scope_type character varying(32) NOT NULL,
    scope_value character varying(255),
    strategy character varying(32) NOT NULL,
    active boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT sourcing_strategy_config_pkey PRIMARY KEY (config_id),
    CONSTRAINT sourcing_strategy_config_scope_type_check
        CHECK (scope_type IN ('SKU_CATEGORY', 'SITE', 'DEFAULT')),
    CONSTRAINT sourcing_strategy_config_strategy_check
        CHECK (strategy IN ('FIFO', 'FEFO', 'PROXIMITY', 'HIGHEST_STOCK')),
    -- DEFAULT scope is platform-wide and must not carry a scope value.
    CONSTRAINT sourcing_strategy_config_default_scope_check
        CHECK (scope_type <> 'DEFAULT' OR scope_value IS NULL)
);

-- One config row per scope (upsert target). COALESCE folds the NULL
-- scope_value of the DEFAULT scope into a single unique key.
CREATE UNIQUE INDEX uq_sourcing_strategy_config_scope
    ON sourcing_strategy_config (scope_type, COALESCE(scope_value, ''));

-- 2. Strategy decision audit on pick tasks (spec H2, "why this bin"): the
--    effective strategy that produced suggested_location_id. Replenishment
--    tasks already carry sourcing_reason; pick tasks did not.
ALTER TABLE inventory_pick_task ADD COLUMN sourcing_reason character varying(32);

ALTER TABLE inventory_pick_task ADD CONSTRAINT inventory_pick_task_sourcing_reason_check
    CHECK (sourcing_reason IS NULL OR sourcing_reason IN ('FIFO', 'FEFO', 'PROXIMITY', 'HIGHEST_STOCK'));
