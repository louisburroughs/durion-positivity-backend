-- TRACKB seed: pos_people_db
BEGIN;
DELETE FROM employee_location_assignment WHERE employee_id IN (SELECT id FROM employee WHERE employee_number LIKE 'TRACKB-%');
DELETE FROM employee WHERE employee_number LIKE 'TRACKB-%';
INSERT INTO employee (id, person_id, employee_number, status, hire_date, created_at, updated_at) VALUES ('5b20de83-8986-5ed0-86fd-0149a041447b', '9dc929b7-1e21-5ccc-89e0-4938905332ca', 'TRACKB-EMP-1', 'ACTIVE', '2024-01-15', '2024-08-15T12:00:00+00:00', '2024-08-15T12:00:00+00:00');
INSERT INTO employee_location_assignment (id, employee_id, location_id, role, is_primary, effective_from, status, created_at, updated_at) VALUES ('7234bbd6-10cc-55cf-ae30-065073fd51b3', '5b20de83-8986-5ed0-86fd-0149a041447b', '488cfdfb-01ed-5028-83d3-deada1a5c961', 'TECHNICIAN', TRUE, '2024-01-15', 'ACTIVE', '2024-08-15T12:00:00+00:00', '2024-08-15T12:00:00+00:00');
INSERT INTO employee (id, person_id, employee_number, status, hire_date, created_at, updated_at) VALUES ('369464dd-ad7e-5172-9dfc-a15cc5a3eaab', '343b5366-8531-5a2e-9976-b1eec230bf0e', 'TRACKB-EMP-2', 'ACTIVE', '2024-01-15', '2024-08-15T12:00:00+00:00', '2024-08-15T12:00:00+00:00');
INSERT INTO employee_location_assignment (id, employee_id, location_id, role, is_primary, effective_from, status, created_at, updated_at) VALUES ('a1a5d57c-8c1d-5aa5-9949-b3d5755f4b7e', '369464dd-ad7e-5172-9dfc-a15cc5a3eaab', '488cfdfb-01ed-5028-83d3-deada1a5c961', 'TECHNICIAN', TRUE, '2024-01-15', 'ACTIVE', '2024-08-15T12:00:00+00:00', '2024-08-15T12:00:00+00:00');
INSERT INTO employee (id, person_id, employee_number, status, hire_date, created_at, updated_at) VALUES ('baa30ae1-fc70-5744-ae11-ea308e1912ff', '8eea60f6-2807-5578-90ee-187caf666b3b', 'TRACKB-EMP-3', 'ACTIVE', '2024-01-15', '2024-08-15T12:00:00+00:00', '2024-08-15T12:00:00+00:00');
INSERT INTO employee_location_assignment (id, employee_id, location_id, role, is_primary, effective_from, status, created_at, updated_at) VALUES ('44d3790d-6c51-5ee3-98ba-ade4a4fa45ef', 'baa30ae1-fc70-5744-ae11-ea308e1912ff', '488cfdfb-01ed-5028-83d3-deada1a5c961', 'TECHNICIAN', TRUE, '2024-01-15', 'ACTIVE', '2024-08-15T12:00:00+00:00', '2024-08-15T12:00:00+00:00');
COMMIT;

-- row-count summary (printed by apply_seed.sh)
SELECT 'employee' AS seeded_table, count(*) AS seeded_rows FROM employee WHERE employee_number LIKE 'TRACKB-%'
UNION ALL
SELECT 'employee_location_assignment' AS seeded_table, count(*) AS seeded_rows FROM employee_location_assignment WHERE employee_id IN (SELECT id FROM employee WHERE employee_number LIKE 'TRACKB-%')
ORDER BY seeded_table;
