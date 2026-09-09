-- V15: PRICAT re-publication on consumer request (CAP-318, ADR-0044 §4)
--
-- pos-catalog publishes supplier.pricecatalog.republish.requested when it applied fewer chunks than
-- an import's completion event declared. Serving that request means re-emitting the import's chunk
-- events from the staged lines.
--
-- WHY chunk_sequence is stored rather than recomputed: the consumer deduplicates re-emitted chunks
-- on (import_manifest_id, chunk_sequence), because a re-emit carries new event ids and the ordinary
-- event-id guard cannot fire for it. A re-emit must therefore reproduce the ORIGINAL chunk
-- boundaries exactly. Recomputing them from position order would reproduce them only for an
-- ordinary vendor fetch; a re-application manifest (V12) stages lines in quarantine-query order,
-- which is not guaranteed to be position order. Where the boundaries moved, the consumer would skip
-- a sequence it had already applied — silently dropping precisely the lines the re-emit existed to
-- deliver — and re-apply lines it already held. Recording the sequence at staging time makes
-- re-emission exact by construction instead of by reconstruction.

ALTER TABLE supplier_pricat_entry ADD COLUMN chunk_sequence integer;

-- Backfill for imports staged before this migration. Position order with the entry id as tie-break
-- is how the ordinary import path chunked them, so the reconstruction is exact for every import
-- that came from a vendor fetch; for a re-application manifest it is at least deterministic and
-- stable, which is what NOT NULL below requires.
UPDATE supplier_pricat_entry e
SET chunk_sequence = (
    SELECT ordered.chunk_sequence
    FROM (
        SELECT p.entry_id,
               CAST((ROW_NUMBER() OVER (
                         PARTITION BY p.import_manifest_id
                         ORDER BY p.position_number NULLS LAST, p.entry_id) - 1)
                    / GREATEST(i.chunk_size, 1) AS integer) + 1 AS chunk_sequence
        FROM supplier_pricat_entry p
        JOIN supplier_pricat_import i ON i.import_manifest_id = p.import_manifest_id
    ) ordered
    WHERE ordered.entry_id = e.entry_id);

ALTER TABLE supplier_pricat_entry ALTER COLUMN chunk_sequence SET NOT NULL;

COMMENT ON COLUMN supplier_pricat_entry.chunk_sequence IS
    'The 1-based chunk event this line was published in. Fixed at staging so a re-emit reproduces the original chunk boundaries the consumer deduplicates on.';

-- Reads the re-publisher makes: one chunk of one import at a time, so a 50k-line catalog is
-- re-emitted without holding it in memory.
CREATE INDEX idx_spricat_entry_chunk ON supplier_pricat_entry (import_manifest_id, chunk_sequence);

-- Re-publication accounting. A consumer that stays short after a re-emit will ask again, so the
-- owner records what it has already done: without a bound, a permanently broken consumer would
-- drive an unbounded re-emit of an entire vendor catalogue on every completion event it processes.
-- One column per statement: H2, which the module's tests migrate against, does not accept a
-- multi-column ADD COLUMN even in PostgreSQL mode.
ALTER TABLE supplier_pricat_import ADD COLUMN republish_count integer NOT NULL DEFAULT 0;
ALTER TABLE supplier_pricat_import ADD COLUMN last_republished_at timestamp(6) with time zone;

COMMENT ON COLUMN supplier_pricat_import.republish_count IS
    'How many times this import has been re-emitted on consumer request; bounded by pos.supplier.pricat.republish-max-attempts.';
