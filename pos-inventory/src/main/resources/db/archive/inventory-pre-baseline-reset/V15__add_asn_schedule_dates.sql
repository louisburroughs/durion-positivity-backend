ALTER TABLE IF EXISTS advance_shipping_notice
    ADD COLUMN IF NOT EXISTS ship_date DATE,
    ADD COLUMN IF NOT EXISTS expected_arrival_date DATE;
