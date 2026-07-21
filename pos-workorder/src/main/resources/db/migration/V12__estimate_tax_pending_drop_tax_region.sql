-- Story T9 (decision D-T5): estimates flag-and-continue on tax-service failure.
-- Add estimate.tax_pending so a degraded estimate is surfaced honestly (no invented rate,
-- no silent testMode flip); a successful recalculation clears it.
ALTER TABLE estimate ADD COLUMN tax_pending boolean NOT NULL DEFAULT false;

-- Drop the dead estimate.tax_region_id member. The destination (shop-location) address is
-- the jurisdiction driver for tax calculation; tax_region_id was never used to resolve
-- tax and pre-production policy removes dead members (plan T9 "recommend delete").
ALTER TABLE estimate DROP COLUMN tax_region_id;
