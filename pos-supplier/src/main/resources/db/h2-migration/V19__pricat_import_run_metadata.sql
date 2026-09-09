-- CAP-318 follow-up / #1637 decisions 4-5: run metadata on the PRICAT import manifest.
--
-- WHAT THIS ADDS
--
-- binding_id: which endpoint binding the run was fetched over. #1224 schedules and triggers runs
-- by binding, and a profile can carry more than one binding, so profile-only scoping cannot tell
-- two feeds' histories apart. Nullable forward-only: rows written before this migration cannot be
-- reconstructed and stay null (#1637 decision 4).
--
-- window_from / window_to: the incremental retrieval interval requested for the run, and
-- checkpoint_state / checkpoint_at: the opaque continuation state committed for the next run and
-- when it was committed (#1637 decision 5, #1224). All four are NULL for full-snapshot protocols
-- -- which is every PRICAT protocol in service today: B4.0 returns the vendor's whole catalog, so
-- there is no window to record and no checkpoint to resume from. The columns exist so an
-- incremental protocol can record real facts, not so a snapshot run can fabricate them.
--
-- error_code: stable machine-readable failure category for a FAILED run. It complements the
-- free-text failure_detail rather than replacing it: the code is for clients and alerting, the
-- text is for the operator reading one run. The CHECK is deliberately closed over the categories
-- the importer can actually distinguish today; a new category is a schema change, exactly like a
-- new import status.
--
-- H2(MODE=PostgreSQL)-compatible, matching V2/V3/V8/V9/V10/V14.
ALTER TABLE supplier_pricat_import ADD COLUMN binding_id uuid;
ALTER TABLE supplier_pricat_import ADD COLUMN window_from timestamp(6) with time zone;
ALTER TABLE supplier_pricat_import ADD COLUMN window_to timestamp(6) with time zone;
ALTER TABLE supplier_pricat_import ADD COLUMN checkpoint_state text;
ALTER TABLE supplier_pricat_import ADD COLUMN checkpoint_at timestamp(6) with time zone;
ALTER TABLE supplier_pricat_import ADD COLUMN error_code character varying(64);

ALTER TABLE supplier_pricat_import ADD CONSTRAINT ck_spricat_import_error_code
    CHECK (error_code IS NULL OR error_code IN ('FETCH_FAILED', 'DECODE_FAILED'));

-- The binding filter always rides alongside the profile scope (the profile tab narrows by
-- binding), so the index leads with the existing scope key.
CREATE INDEX idx_spricat_import_binding ON supplier_pricat_import (vendor_profile_id, binding_id);
