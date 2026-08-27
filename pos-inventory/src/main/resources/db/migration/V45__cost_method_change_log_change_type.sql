-- #1535 (SKU_CATEGORY costing/sourcing cut-over): give cost_method_change_log a change_type, so
-- that retiring a scope override is representable in the audit it is supposed to be governed by.
--
-- V26 built this table around a single event: "the method configured at this scope changed from A
-- to B". Deactivation had no representation at all. That is the defect this migration repairs, and
-- it matters most for exactly the operation #1535 asks operators to perform: deactivating a
-- SKU_CATEGORY row is the single most consequential step of the cut-over — it is how an operator
-- decides a category override is NOT wanted before the flag is flipped — and it left no audit row
-- whatsoever. Worse, a deactivate/reactivate round trip was entirely invisible: the upsert only
-- writes a row when the method changed, and on the way back the method has not changed, so the
-- override silently returned to life with nothing in the log to say so.
--
-- change_type: METHOD_SET (the V26 event), DEACTIVATED (the override was retired), REACTIVATED (a
-- retired override was brought back at its existing method).
--
-- The DEFAULT is deliberately KEPT rather than dropped after backfilling. It backfills every
-- existing row to METHOD_SET without a table rewrite, and it keeps a hand-written INSERT — the kind
-- a support engineer writes during an incident — from producing a NULL in a NOT NULL column.
--
-- to_method becomes nullable because DEACTIVATED has no destination method: the row stops
-- participating in resolution, it does not resolve to something else. The paired
-- cost_method_change_log_to_method_required constraint keeps that narrow: only DEACTIVATED may omit
-- it, so METHOD_SET and REACTIVATED cannot regress into writing a null.
--
-- Written to be H2(MODE=PostgreSQL)- and PostgreSQL-compatible: one action per ALTER statement, no
-- IF NOT EXISTS.

ALTER TABLE cost_method_change_log
  ADD COLUMN change_type character varying(32) NOT NULL DEFAULT 'METHOD_SET';

ALTER TABLE cost_method_change_log ALTER COLUMN to_method DROP NOT NULL;

ALTER TABLE cost_method_change_log DROP CONSTRAINT cost_method_change_log_to_method_check;

ALTER TABLE cost_method_change_log ADD CONSTRAINT cost_method_change_log_to_method_check
    CHECK (to_method IS NULL OR to_method IN ('STANDARD', 'AVERAGE'));

ALTER TABLE cost_method_change_log ADD CONSTRAINT cost_method_change_log_change_type_check
    CHECK (change_type IN ('METHOD_SET', 'DEACTIVATED', 'REACTIVATED'));

ALTER TABLE cost_method_change_log ADD CONSTRAINT cost_method_change_log_to_method_required
    CHECK (change_type = 'DEACTIVATED' OR to_method IS NOT NULL);
