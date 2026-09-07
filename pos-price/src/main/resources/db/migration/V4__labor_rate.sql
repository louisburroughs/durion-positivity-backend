-- Shop labor rates and the labor matrix (#1575 Tier 0 "shop labor rates" / "shop-specific
-- pricing rules", docs/SPEC-tier-0-durion-owned-service-data.md T0-3 / D7).
--
-- #1569 flagged that pos-price models no hourly rate at all — ProductBasePrice, tier rules,
-- promotions and price books are all per-product — so book time x rate still had one operand
-- hand-typed on every LABOR line. Tier 0 names shop labor rates as Durion-owned data, which is
-- what brings the item in scope.
--
-- The split follows ADR-0054: pos-catalog owns HOW LONG (service_labor_standard), pos-price owns
-- HOW MUCH PER HOUR, and pos-workorder multiplies them. Neither module needs the other's table.
--
--   labor_rate             the hourly rate, resolved most-specific-first:
--                            (location, category) -> (location, NULL) -> (NULL, category) -> (NULL, NULL)
--                          NULL location = platform default; NULL category = every category.
--   labor_rate_adjustment  the shop labor matrix: ordered percentage/fixed steps a caller opts
--                          into by naming their codes (corrosion, after-hours, fleet contract).
--                          Ordered because compounding is not commutative — +15% then -10% is
--                          not -10% then +15% — so `sequence` is part of the answer, not a
--                          display hint.
--
-- Effective dating is [effective_from, effective_to) with NULL meaning open-ended, matching
-- location_price_override and customer_tier_pricing_rule in this module. Rates are corrected by
-- closing the old row and opening a new one, never by editing in place: an invoice quoted at
-- last month's rate has to stay explainable, the same reason service_labor_standard supersedes.

SET TIME ZONE 'UTC';

CREATE TABLE labor_rate (
    id                 uuid PRIMARY KEY,                 -- UUID v7
    location_id        uuid,                             -- NULL = platform default
    operation_category varchar(32),                      -- NULL = every category
    currency           char(3)      NOT NULL,
    hourly_rate        numeric(10,4) NOT NULL,
    effective_from     timestamptz  NOT NULL,
    effective_to       timestamptz,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    -- The category vocabulary is pos-catalog's operation_category (ADR-0059 §3). Checked here as
    -- well as in the app layer because seeds write the column directly and a value the
    -- @Enumerated(STRING) mapping cannot hydrate would 500 every read of the row.
    CONSTRAINT ck_labor_rate_category CHECK (
        operation_category IS NULL
        OR operation_category IN ('REPAIR', 'DIAGNOSTIC', 'MAINTENANCE', 'TIRE_SERVICE')),
    CONSTRAINT ck_labor_rate_positive CHECK (hourly_rate > 0),
    CONSTRAINT ck_labor_rate_window CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- Resolution reads by scope and asks "which row is in force now", so the index leads with the
-- scope pair and carries the window.
CREATE INDEX ix_labor_rate_scope ON labor_rate (location_id, operation_category, effective_from, effective_to);

-- One rate per scope per start instant. Two rows opening the same scope at the same moment is
-- ambiguous rather than expressive; a change closes the old window and opens a new one.
-- NULLS NOT DISTINCT so the platform-default and all-category rows are covered by the key too.
CREATE UNIQUE INDEX ux_labor_rate_scope_start ON labor_rate (location_id, operation_category, effective_from)
    NULLS NOT DISTINCT;

CREATE TABLE labor_rate_adjustment (
    id                 uuid PRIMARY KEY,                 -- UUID v7
    location_id        uuid,                             -- NULL = platform default
    operation_category varchar(32),                      -- NULL = every category
    adjustment_code    varchar(64)  NOT NULL,            -- CORROSION | AFTER_HOURS | FLEET_CONTRACT | ...
    description        varchar(255),
    adjustment_type    varchar(16)  NOT NULL,            -- PERCENT | FIXED
    adjustment_value   numeric(10,4) NOT NULL,
    sequence           int          NOT NULL,
    effective_from     timestamptz  NOT NULL,
    effective_to       timestamptz,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    CONSTRAINT ck_lra_category CHECK (
        operation_category IS NULL
        OR operation_category IN ('REPAIR', 'DIAGNOSTIC', 'MAINTENANCE', 'TIRE_SERVICE')),
    CONSTRAINT ck_lra_type CHECK (adjustment_type IN ('PERCENT', 'FIXED')),
    CONSTRAINT ck_lra_window CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX ix_lra_scope ON labor_rate_adjustment (location_id, operation_category, effective_from, effective_to);

CREATE UNIQUE INDEX ux_lra_scope_code_start
    ON labor_rate_adjustment (location_id, operation_category, adjustment_code, effective_from)
    NULLS NOT DISTINCT;
