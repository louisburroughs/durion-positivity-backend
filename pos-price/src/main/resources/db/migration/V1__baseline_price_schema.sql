CREATE TABLE IF NOT EXISTS product_base_price (
    product_id UUID PRIMARY KEY,
    msrp NUMERIC(10,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_product_base_price_effective_window
    ON product_base_price (product_id, effective_from, effective_to);

CREATE TABLE IF NOT EXISTS location_price_override (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL,
    location_id UUID NOT NULL,
    override_price NUMERIC(10,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_location_override_product_location_effective
    ON location_price_override (product_id, location_id, effective_from, effective_to);

CREATE TABLE IF NOT EXISTS customer_tier_pricing_rule (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL,
    customer_tier_id UUID NOT NULL,
    discount_rate NUMERIC(5,4) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tier_rule_product_tier_effective
    ON customer_tier_pricing_rule (product_id, customer_tier_id, effective_from, effective_to);

CREATE TABLE IF NOT EXISTS pricing_snapshot (
    snapshot_id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    source_context TEXT,
    item_identifier VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL,
    prices TEXT NOT NULL,
    applied_rules TEXT,
    policy_version VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS promotion_offer (
    promotion_offer_id UUID PRIMARY KEY,
    promo_code VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    discount_type VARCHAR(255) NOT NULL,
    discount_value NUMERIC(10,2) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    usage_limit INTEGER,
    usage_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(255) NOT NULL DEFAULT 'DRAFT',
    store_code VARCHAR(255),
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS promotion_eligibility_rule (
    rule_id UUID PRIMARY KEY,
    promotion_id UUID NOT NULL,
    condition_type VARCHAR(50) NOT NULL,
    operator VARCHAR(50) NOT NULL,
    rule_value VARCHAR(255) NOT NULL,
    rule_combination VARCHAR(10),
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT fk_promotion_eligibility_rule_promotion
        FOREIGN KEY (promotion_id) REFERENCES promotion_offer(promotion_offer_id)
);

CREATE TABLE IF NOT EXISTS restriction_rule (
    rule_id UUID PRIMARY KEY,
    product_id UUID NOT NULL,
    location_tag VARCHAR(255) NOT NULL,
    service_tag VARCHAR(255) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    overrideable BOOLEAN NOT NULL DEFAULT FALSE,
    policy_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS restriction_override_audit (
    override_id UUID PRIMARY KEY,
    actor VARCHAR(255) NOT NULL,
    rule_id UUID NOT NULL,
    transaction_id UUID NOT NULL,
    product_id UUID NOT NULL,
    override_context VARCHAR(255) NOT NULL,
    reason_code VARCHAR(255) NOT NULL,
    notes VARCHAR(255),
    approved_by VARCHAR(255),
    policy_version INTEGER NOT NULL,
    status VARCHAR(255) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
