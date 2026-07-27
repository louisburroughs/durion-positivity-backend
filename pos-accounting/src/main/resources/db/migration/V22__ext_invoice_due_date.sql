-- #993 (PR #1005 review): collections-aging due date frozen at finalization by
-- pos-invoice; null on drafts and on events predating the enrichment. OLDEST_FIRST
-- ages by this, falling back to finalized_at. Added here instead of editing the
-- already-applied V5 versioned migration (Flyway checksum immutability).
ALTER TABLE ext_invoice ADD COLUMN due_date date;
