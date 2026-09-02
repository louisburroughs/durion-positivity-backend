-- Guide-time snapshot columns on LABOR estimate items and their promoted service lines
-- (#1569 Phase 1, sourcing plan §6.3 item 2).
--
-- An adjusted quote must record BOTH numbers: quantity stays the agreed hours the customer
-- approved, and guide_hours is the book-time baseline the guide published, with the provenance
-- that makes it defensible on an invoice (source + revision + how confidently the vehicle
-- matched). Overlap metadata is snapshotted too, because the workorder-level estimated-hours
-- sum must not double-bill shared setup time and must zero lines whose operation another
-- line's time already includes — and it must keep computing the same answer after the guide
-- publishes a new revision, which is why it reads the snapshot, not the live catalog.
--
-- guide_included_op_codes is a comma-separated list of Durion operation codes; it is only ever
-- read back whole for the summation, never queried relationally.

ALTER TABLE estimate_item ADD COLUMN guide_hours numeric(5,1);
ALTER TABLE estimate_item ADD COLUMN guide_source_code varchar(32);
ALTER TABLE estimate_item ADD COLUMN guide_source_revision varchar(64);
ALTER TABLE estimate_item ADD COLUMN guide_match_grade varchar(24);
ALTER TABLE estimate_item ADD COLUMN guide_overlap_group varchar(64);
ALTER TABLE estimate_item ADD COLUMN guide_included_op_codes text;

ALTER TABLE workorder_service ADD COLUMN guide_hours numeric(5,1);
ALTER TABLE workorder_service ADD COLUMN guide_source_code varchar(32);
ALTER TABLE workorder_service ADD COLUMN guide_source_revision varchar(64);
ALTER TABLE workorder_service ADD COLUMN guide_match_grade varchar(24);
ALTER TABLE workorder_service ADD COLUMN guide_overlap_group varchar(64);
ALTER TABLE workorder_service ADD COLUMN guide_included_op_codes text;
