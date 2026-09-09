-- CAP-318 / #1310: re-application of quarantined PRICAT lines (ADR-0053 §5).
--
-- V10 and V11 are reserved for the stock-report wave (#1228) which is in flight; this migration
-- deliberately takes V12 so the two can merge in either order without a version collision.
--
-- A re-application produces its OWN import manifest rather than mutating the original's counters.
-- The original import's numbers are a record of what the vendor sent and how much of it we could
-- match AT THE TIME, and rewriting them later would destroy that record while also contradicting
-- the chunk sequencing consumers already applied: chunk 4 of a 3-chunk import is not a thing a
-- completeness check can accept. The new manifest points back at the one it healed.
ALTER TABLE supplier_pricat_import ADD COLUMN reapplied_from_import_id uuid;

COMMENT ON COLUMN supplier_pricat_import.reapplied_from_import_id IS
    'Set when this manifest re-applied quarantined lines of an earlier import (#1310); null for a vendor fetch.';

CREATE INDEX idx_spricat_import_reapplied_from ON supplier_pricat_import (reapplied_from_import_id);
