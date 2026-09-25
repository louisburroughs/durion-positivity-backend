-- #2206: the UI needs to trace a ledger entry and a return line back to the work order (and,
-- where known, the work order line) that drove it. Both links are new — the ledger already carried
-- a free-text mention of the workorder in `notes`, but nothing queryable, and the return line
-- carried no work-order reference at all beyond the parent `inventory_return.workorder_id` header.
--
-- Both nullable rather than backfilled: a ledger row or return line posted before this column
-- existed has no link, exactly as it had none before. New postings on the paths that know a work
-- order (pick-task consumption, cross-dock receipt/issue, returns-to-stock, and any path that
-- already stamps `serialWorkorderId`) populate it going forward.
--
-- The composite index below serves InventoryLedgerEntryRepository.findByWorkorderIdAndEventType
-- (ReturnServiceImpl's consumed/returnable sums, PR #2227 review item 9): every call filters on
-- both columns together, never workorder_id alone.
ALTER TABLE inventory_ledger_entry ADD COLUMN workorder_id uuid;
ALTER TABLE inventory_ledger_entry ADD COLUMN workorder_line_id uuid;

COMMENT ON COLUMN inventory_ledger_entry.workorder_id IS
    'Work order this posting is for, when the posting path knows one (#2206). Null for postings that do not carry a work order and for rows posted before this column existed.';
COMMENT ON COLUMN inventory_ledger_entry.workorder_line_id IS
    'Work order line this posting is for, when the posting path knows one (#2206). Null when only the work order (not the line) is known, and for rows posted before this column existed.';

CREATE INDEX idx_inventory_ledger_entry_workorder_event
    ON inventory_ledger_entry (workorder_id, event_type);

ALTER TABLE inventory_return_line ADD COLUMN workorder_line_id uuid;

COMMENT ON COLUMN inventory_return_line.workorder_line_id IS
    'Work order line this return line returns stock against (#2206), used to compute how much of a line remains returnable. Null for lines posted before this column existed.';
