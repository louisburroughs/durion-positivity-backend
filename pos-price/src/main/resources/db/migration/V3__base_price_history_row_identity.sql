-- ADR-0054 §4 / #1233: make product_base_price history-retaining.
-- Rows gain their own identity; (product_id, currency, effective window) becomes content.
-- Existing single-row-per-product data carries forward as the initial window, keeping
-- the prior product_id value as the row id (deterministic, no data loss).

ALTER TABLE product_base_price ADD COLUMN id uuid;

UPDATE product_base_price SET id = product_id WHERE id IS NULL;

ALTER TABLE product_base_price ALTER COLUMN id SET NOT NULL;

ALTER TABLE product_base_price DROP CONSTRAINT product_base_price_pkey;

ALTER TABLE product_base_price ADD CONSTRAINT product_base_price_pkey PRIMARY KEY (id);

CREATE INDEX idx_base_price_product_currency_effective
    ON product_base_price (product_id, currency, effective_from, effective_to);

-- At most one open-ended window per (product_id, currency).
CREATE UNIQUE INDEX uq_base_price_open_window
    ON product_base_price (product_id, currency)
    WHERE effective_to IS NULL;
