ALTER TABLE IF EXISTS approval_threshold_config
    ADD COLUMN IF NOT EXISTS approval_tier VARCHAR(32),
    ADD COLUMN IF NOT EXISTS unit_variance_threshold INTEGER,
    ADD COLUMN IF NOT EXISTS value_variance_threshold NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS percentage_variance_threshold NUMERIC(5, 2),
    ADD COLUMN IF NOT EXISTS active BOOLEAN DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;
