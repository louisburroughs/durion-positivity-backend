-- #2434: every journal entry names its source (source_event_type + source_event_id). New entries
-- get both at creation; this backfills the rows written before that rule, where a safe source is
-- derivable. Rows it cannot classify keep a NULL type.
--
-- Tenancy: journal_entry and invoice_gl_posting are under FORCE ROW LEVEL SECURITY. Flyway runs
-- as the owner; a superuser owner sees every tenant, otherwise the owner's bound tenant only. No
-- tenant is bound here, and every join below matches on tenant_id, so no row ever takes a source
-- from another tenant's row. modified_at is left alone: this annotates provenance, it is not an
-- edit of the entry (and ADR-0024 forbids SQL-clock writes).

-- 1. Invoice revenue recognitions: the entry invoice_gl_posting records as the cycle's posting.
--    source_event_id already holds the deterministic INVOICE_REVENUE key it was posted with.
UPDATE journal_entry
SET source_event_type = 'INVOICE_REVENUE'
WHERE (source_event_type IS NULL OR source_event_type = '')
  AND EXISTS (
      SELECT 1
      FROM invoice_gl_posting igp
      WHERE igp.tenant_id = journal_entry.tenant_id
        AND igp.journal_entry_id = journal_entry.journal_entry_id);

-- 2. Invoice revenue reversals: the mirror entry recorded on the same invoice_gl_posting row.
UPDATE journal_entry
SET source_event_type = 'INVOICE_REVENUE_REVERSAL'
WHERE (source_event_type IS NULL OR source_event_type = '')
  AND EXISTS (
      SELECT 1
      FROM invoice_gl_posting igp
      WHERE igp.tenant_id = journal_entry.tenant_id
        AND igp.reversal_journal_entry_id = journal_entry.journal_entry_id);

-- 3. Manual entries: every automated path (posting-rule engine, direct posting services,
--    bank-reconciliation adjustments) has always set source_event_id, and a reversal copies its
--    original's. An entry with neither a source event id nor a reversed original was therefore
--    created through POST /v1/accounting/journal-entries: stamp it MANUAL with its own id, as the
--    manual create path now does.
UPDATE journal_entry
SET source_event_type = 'MANUAL',
    source_event_id = journal_entry_id
WHERE (source_event_type IS NULL OR source_event_type = '')
  AND source_event_id IS NULL
  AND reversal_journal_entry_id IS NULL
  AND posting_rule_set_id IS NULL;

-- 4. Reversals (POST .../reverse) carry the source of the entry they reverse, as new reversals do.
--    Runs after 1-3 so a reversal of an invoice or manual entry picks up the type set above.
UPDATE journal_entry
SET source_event_type = (
        SELECT original.source_event_type
        FROM journal_entry original
        WHERE original.tenant_id = journal_entry.tenant_id
          AND original.journal_entry_id = journal_entry.reversal_journal_entry_id),
    source_event_id = COALESCE(
        source_event_id,
        (SELECT original.source_event_id
         FROM journal_entry original
         WHERE original.tenant_id = journal_entry.tenant_id
           AND original.journal_entry_id = journal_entry.reversal_journal_entry_id))
WHERE (source_event_type IS NULL OR source_event_type = '')
  AND reversal_journal_entry_id IS NOT NULL
  AND EXISTS (
      SELECT 1
      FROM journal_entry original
      WHERE original.tenant_id = journal_entry.tenant_id
        AND original.journal_entry_id = journal_entry.reversal_journal_entry_id
        AND original.source_event_type IS NOT NULL
        AND original.source_event_type <> '');
