-- Phase 5.3 follow-up: add remaining mapped entity columns for pos-accounting.

ALTER TABLE IF EXISTS idempotency_keys
    ADD COLUMN IF NOT EXISTS invoice_id UUID;
