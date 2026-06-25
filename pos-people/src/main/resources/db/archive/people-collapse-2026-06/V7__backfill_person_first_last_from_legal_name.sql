-- Backfill firstName/lastName for persons created through the Employee path.
--
-- The Employee create/update path historically wrote only legal_name, leaving
-- first_name/last_name null. Those persons render blank in the people directory
-- and the people typeahead (which read first_name/last_name). The application now
-- keeps the two in sync on write; this migration repairs existing rows.
--
-- Split heuristic mirrors EmployeeServiceImpl.applyStructuredName: the first
-- whitespace-delimited token becomes first_name, the remainder last_name.
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
