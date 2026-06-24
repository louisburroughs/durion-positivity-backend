-- Add a human-readable workorder number (WO-YYYY-NNNN), analogous to estimate_number.
-- Going forward, new workorders take the linked estimate's number with the prefix swapped
-- (EST-2026-1001 -> WO-2026-1001) when that value is globally free, otherwise an independent
-- WO-YYYY-NNNN sequence. Backfill here assigns existing rows deterministically and uniquely
-- per year (sequential), so the value is non-null on every row and the unique index holds.

ALTER TABLE workorder ADD COLUMN workorder_number varchar(64);

-- Deterministic sequential backfill, per creation-year, starting at 1000 (mirrors
-- generateEstimateNumber's starting sequence). row_number() guarantees uniqueness.
WITH numbered AS (
    SELECT id,
           to_char(created_at, 'YYYY') AS yr,
           row_number() OVER (
               PARTITION BY to_char(created_at, 'YYYY')
               ORDER BY created_at, id
           ) + 999 AS seq
    FROM workorder
)
UPDATE workorder w
SET workorder_number = 'WO-' || n.yr || '-' || n.seq
FROM numbered n
WHERE w.id = n.id;

ALTER TABLE workorder ALTER COLUMN workorder_number SET NOT NULL;

CREATE UNIQUE INDEX ux_workorder_workorder_number ON workorder (workorder_number);
