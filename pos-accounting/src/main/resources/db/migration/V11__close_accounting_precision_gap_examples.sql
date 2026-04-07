-- Phase 5.3 follow-up: close remaining accounting precision-gap examples.

ALTER TABLE IF EXISTS payment_applied_events
    ADD COLUMN IF NOT EXISTS invoice_id UUID,
    ADD COLUMN IF NOT EXISTS payment_amount NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS invoice_total NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS status VARCHAR(64),
    ADD COLUMN IF NOT EXISTS "timestamp" TIMESTAMPTZ;

ALTER TABLE IF EXISTS idempotency_keys
    ADD COLUMN IF NOT EXISTS key_value VARCHAR(255),
    ADD COLUMN IF NOT EXISTS payload_hash VARCHAR(255),
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

ALTER TABLE IF EXISTS payment_application_reversal
    ALTER COLUMN reversal_id SET NOT NULL,
    ALTER COLUMN original_payment_application_id SET NOT NULL,
    ALTER COLUMN amount SET DEFAULT 0,
    ALTER COLUMN amount SET NOT NULL,
    ALTER COLUMN reason SET NOT NULL,
    ALTER COLUMN reversed_at SET DEFAULT NOW(),
    ALTER COLUMN reversed_at SET NOT NULL,
    ALTER COLUMN reversed_by SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_idempotency_keys_key_value
    ON idempotency_keys (key_value);
