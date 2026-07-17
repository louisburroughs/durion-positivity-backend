-- Issue #925: auto-create WarrantyRegistration when road-hazard/extended plans are sold.
-- Policies opt in via auto_register; the workorder.events.v1 consumer creates one
-- registration per (policy, source invoice). H2(MODE=PostgreSQL)-compatible like V1.

ALTER TABLE warranty_policy ADD COLUMN auto_register boolean NOT NULL DEFAULT false;

-- Auto-registration dedupe scans by source invoice (existsByPolicyIdAndSourceInvoiceId).
-- Plain (non-unique) index: manual re-registration flows may legitimately create more rows
-- for the same invoice.
CREATE INDEX idx_wreg_source_invoice ON warranty_registration (source_invoice_id);
