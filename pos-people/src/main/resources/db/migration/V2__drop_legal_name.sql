-- Reconcile the person name model to a single authoritative representation (issue #726).
--
-- The Employee path historically wrote legal_name while the directory / typeahead and most
-- readers use first_name/last_name. first_name/last_name is now canonical; legal_name is removed.
-- This migration backfills any row that still carries legal_name but no first_name (Employee-path
-- rows created before the unification), then drops the redundant column.
--
-- Split heuristic (first whitespace-delimited token -> first_name, remainder -> last_name) is a
-- one-time data repair for legacy rows only; new writes set first_name/last_name directly.
UPDATE person
SET first_name = split_part(trim(legal_name), ' ', 1),
    last_name = CASE
        WHEN position(' ' IN trim(legal_name)) > 0
            THEN trim(substring(trim(legal_name) FROM position(' ' IN trim(legal_name)) + 1))
        ELSE ''
    END,
    updated_at = NOW()
WHERE (first_name IS NULL OR first_name = '')
  AND legal_name IS NOT NULL
  AND trim(legal_name) <> '';

ALTER TABLE person DROP COLUMN legal_name;
