-- GET /v1/people/me/primary-location now requires an assignment flagged
-- is_primary (it previously fell back to any active assignment). Backfill the
-- unambiguous case so existing users keep resolving a location: an employee
-- whose ONLY active assignment is unflagged gets that assignment promoted to
-- primary. Employees with several active assignments and no primary are left
-- untouched (genuinely ambiguous — resolved via the staffing assignment API).
UPDATE employee_location_assignment a
SET is_primary = TRUE
WHERE a.status = 'ACTIVE'
  AND a.is_primary = FALSE
  AND NOT EXISTS (
      SELECT 1
      FROM employee_location_assignment p
      WHERE p.employee_id = a.employee_id
        AND p.status = 'ACTIVE'
        AND p.is_primary = TRUE)
  AND (SELECT COUNT(*)
       FROM employee_location_assignment c
       WHERE c.employee_id = a.employee_id
         AND c.status = 'ACTIVE') = 1;
