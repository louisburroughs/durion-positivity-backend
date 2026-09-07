-- Labor-rate snapshot on LABOR estimate items and their promoted service lines
-- (#1575 Tier 0 / #1569 residual R4, docs/SPEC-tier-0-durion-owned-service-data.md T0-3).
--
-- The mirror of V26. That migration recorded the guide's hours beside the agreed hours, because
-- an adjusted quote must show both; this records the rate the same way, because #1569's "book
-- time x rate would still have one input hand-typed on the line" is only closed once the price
-- half carries its own provenance too.
--
--   rate_hourly           the rate the line was priced at, after the labor matrix
--   rate_base_hourly      the rate before the matrix, so the adjustment is visible on the quote
--   rate_currency         ISO currency of both
--   rate_scope            how specific the answering rate was (LOCATION_CATEGORY ... PLATFORM_DEFAULT)
--   rate_id               the pos-price row that answered — what a re-quote pins to
--   rate_adjustment_codes comma-separated matrix codes that actually applied
--
-- rate_adjustment_codes is a comma-separated list, matching guide_included_op_codes (V26): it is
-- only ever read back whole to show a derivation, never queried relationally.
--
-- unitPrice remains the authority for what is charged. These columns record where that number
-- came from; they never override it, exactly as guide_hours never overrides quantity.

ALTER TABLE estimate_item ADD COLUMN rate_hourly numeric(10,4);
ALTER TABLE estimate_item ADD COLUMN rate_base_hourly numeric(10,4);
ALTER TABLE estimate_item ADD COLUMN rate_currency varchar(3);
ALTER TABLE estimate_item ADD COLUMN rate_scope varchar(24);
ALTER TABLE estimate_item ADD COLUMN rate_id uuid;
ALTER TABLE estimate_item ADD COLUMN rate_adjustment_codes text;

ALTER TABLE workorder_service ADD COLUMN rate_hourly numeric(10,4);
ALTER TABLE workorder_service ADD COLUMN rate_base_hourly numeric(10,4);
ALTER TABLE workorder_service ADD COLUMN rate_currency varchar(3);
ALTER TABLE workorder_service ADD COLUMN rate_scope varchar(24);
ALTER TABLE workorder_service ADD COLUMN rate_id uuid;
ALTER TABLE workorder_service ADD COLUMN rate_adjustment_codes text;
