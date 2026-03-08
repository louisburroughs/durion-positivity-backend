-- Add discrepancy tracking columns to invoice_status_views (AC4: CAP-251 Story #5)
ALTER TABLE invoice_status_views
    ADD COLUMN discrepancy_detected BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN discrepancy_reason   VARCHAR(1000);
