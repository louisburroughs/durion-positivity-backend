-- V3: Repair commercial-account contact persons.
--
-- These rows (namespace 01960026) were seeded with department+company names
-- (e.g. 'General Piedmont Freight') and status='ACTIVE', which made them read as
-- companies/employees in the People Directory. They are real, non-employee
-- contacts. Rename to the matching CUST-CPC individuals (as held in pos-customer
-- person_party) and clear status so the directory categorizes them as
-- non-employee persons (status IS NULL).
--
-- The repeatable seed uses ON CONFLICT (id) DO NOTHING, so existing rows are not
-- updated by the seed; this versioned migration repairs them once. On a fresh DB
-- the seed already carries the corrected values and these UPDATEs are no-ops.

UPDATE person SET first_name = 'Greg',      last_name = 'Whitfield',  status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-000000000001'::uuid;
UPDATE person SET first_name = 'Teresa',    last_name = 'Mullen',     status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-000000000002'::uuid;
UPDATE person SET first_name = 'Darnell',   last_name = 'Okafor',     status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-000000000003'::uuid;
UPDATE person SET first_name = 'Brittany',  last_name = 'Norris',     status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-000000000004'::uuid;
UPDATE person SET first_name = 'Marcus',    last_name = 'Tillman',    status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-000000000005'::uuid;
UPDATE person SET first_name = 'Christine', last_name = 'Walters',    status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-000000000006'::uuid;
UPDATE person SET first_name = 'Donald',    last_name = 'Frazier',    status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-000000000007'::uuid;
UPDATE person SET first_name = 'Alicia',    last_name = 'Stephens',   status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-000000000008'::uuid;
UPDATE person SET first_name = 'Keith',     last_name = 'Burnham',    status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-000000000009'::uuid;
UPDATE person SET first_name = 'Tamara',    last_name = 'McPherson',  status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-00000000000a'::uuid;
UPDATE person SET first_name = 'Wesley',    last_name = 'Parrish',    status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-00000000000b'::uuid;
UPDATE person SET first_name = 'Renee',     last_name = 'Holt',       status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-00000000000c'::uuid;
UPDATE person SET first_name = 'Calvin',    last_name = 'Dunmore',    status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-00000000000d'::uuid;
UPDATE person SET first_name = 'Latasha',   last_name = 'Gooden',     status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-00000000000e'::uuid;
UPDATE person SET first_name = 'Bryan',     last_name = 'Cantrell',   status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-00000000000f'::uuid;
UPDATE person SET first_name = 'Monica',    last_name = 'Byrd',       status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-000000000010'::uuid;
UPDATE person SET first_name = 'Cedric',    last_name = 'Blackwell',  status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-000000000011'::uuid;
UPDATE person SET first_name = 'Veronica',  last_name = 'Pratt',      status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-000000000012'::uuid;
UPDATE person SET first_name = 'Jonathon',  last_name = 'Culpepper',  status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-000000000013'::uuid;
UPDATE person SET first_name = 'Sheryl',    last_name = 'Davenport',  status = NULL, updated_at = NOW() WHERE id = '01960026-0000-7000-8000-000000000014'::uuid;
