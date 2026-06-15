-- Repeatable seed migration for pos-customer operational data.
-- Populates 50 PersonParty (individual customers), 20 CommercialParty (fleet operators),
-- 20 primary Contact rows (one per commercial party), and 20 billing Contact rows
-- (one per commercial party, linked to 01960026-* billing persons in pos-people).
--
-- UUID namespaces:
--   01960020: person_party.customer_id      (rows 001–032 hex = 1–50)
--   01960021: commercial_party.customer_id  (rows 001–014 hex = 1–20)
--   01960022: contact.contact_id            (rows 001–014 hex = 1–20, primary contacts)
--   01960023: contact.contact_id            (rows 001–014 hex = 1–20, billing contacts)
--   01960024: person_party.person_id        real Person rows in pos-people (rows 001–032 hex)
--   01960025: contact.person_id / person_party.customer_id  contact persons in pos-people (rows 001–014 hex)
--   01960026: contact.person_id             billing Person rows in pos-people (rows 001–014 hex)
--   01960027: contact_point.contact_point_id  email points for contact persons (rows 001–014 hex)
--   01960028: contact_point.contact_point_id  phone points for contact persons (rows 001–014 hex)
--   01960029: party_relationship.party_relationship_id (rows 001–014 hex = 1–20)
--
-- Enum ordinals used:
--   AccountStatus : ACTIVE=0, INACTIVE=1, ON_HOLD=2
--   AccountTier   : STANDARD=0, BRONZE=1, SILVER=2, GOLD=3, PLATINUM=4
--   PartyType     : COMMERCIAL=1

SET TIME ZONE 'UTC';

-- =========================================================================
-- SECTION 1: PERSON PARTY — 50 individual vehicle-owning customers
--            Charlotte metro area, NC
--   Status  : 45 ACTIVE, 4 INACTIVE, 1 ON_HOLD
--   Tier    : 35 STANDARD, 10 BRONZE, 3 SILVER, 2 GOLD
-- =========================================================================

INSERT INTO person_party (customer_id, person_id, customer_number,
                          first_name, last_name, primary_address,
                          status, tier, tier_manual_override, preferred_contact_method,
                          created_at, updated_at)
VALUES
    ('01960020-0000-7000-8000-000000000001'::uuid, '01960024-0000-7000-8000-000000000001'::uuid,
     'CUST-PP-001', 'Marcus', 'Patterson', '1422 Sardis Rd, Charlotte, NC 28227',
     0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000002'::uuid, '01960024-0000-7000-8000-000000000002'::uuid,
     'CUST-PP-002', 'Jennifer', 'Holloway', '4310 Glenwood Rd, Charlotte, NC 28208',
     0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000003'::uuid, '01960024-0000-7000-8000-000000000003'::uuid,
     'CUST-PP-003', 'Robert', 'Castillo', '7810 Monroe Rd, Charlotte, NC 28212',
     0, 0, false, 'PHONE_CALL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000004'::uuid, '01960024-0000-7000-8000-000000000004'::uuid,
     'CUST-PP-004', 'Angela', 'Freeman', '2390 Freedom Dr, Charlotte, NC 28208',
     0, 1, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000005'::uuid, '01960024-0000-7000-8000-000000000005'::uuid,
     'CUST-PP-005', 'Derek', 'Washington', '5601 N Tryon St, Charlotte, NC 28213',
     0, 0, false, 'SMS', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000006'::uuid, '01960024-0000-7000-8000-000000000006'::uuid,
     'CUST-PP-006', 'Patricia', 'Simmons', '1903 Beatties Ford Rd, Charlotte, NC 28216',
     0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000007'::uuid, '01960024-0000-7000-8000-000000000007'::uuid,
     'CUST-PP-007', 'Kevin', 'Thornton', '8205 Idlewild Rd, Charlotte, NC 28227',
     0, 1, false, 'PHONE_CALL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000008'::uuid, '01960024-0000-7000-8000-000000000008'::uuid,
     'CUST-PP-008', 'Linda', 'Guerrero', '3102 Nations Ford Rd, Charlotte, NC 28217',
     0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000009'::uuid, '01960024-0000-7000-8000-000000000009'::uuid,
     'CUST-PP-009', 'James', 'Caldwell', '702 N Kings Dr, Charlotte, NC 28204',
     0, 2, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-00000000000a'::uuid, '01960024-0000-7000-8000-00000000000a'::uuid,
     'CUST-PP-010', 'Tanya', 'Robinson', '4512 Central Ave, Charlotte, NC 28205',
     0, 0, false, 'SMS', NOW(), NOW()),

    ('01960020-0000-7000-8000-00000000000b'::uuid, '01960024-0000-7000-8000-00000000000b'::uuid,
     'CUST-PP-011', 'Michael', 'Owens', '2105 Wilkinson Blvd, Charlotte, NC 28208',
     0, 0, false, 'PHONE_CALL', NOW(), NOW()),

    ('01960020-0000-7000-8000-00000000000c'::uuid, '01960024-0000-7000-8000-00000000000c'::uuid,
     'CUST-PP-012', 'Cheryl', 'Hawkins', '6301 South Blvd, Charlotte, NC 28217',
     0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-00000000000d'::uuid, '01960024-0000-7000-8000-00000000000d'::uuid,
     'CUST-PP-013', 'Ronald', 'Jenkins', '9912 Albemarle Rd, Charlotte, NC 28227',
     0, 1, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-00000000000e'::uuid, '01960024-0000-7000-8000-00000000000e'::uuid,
     'CUST-PP-014', 'Denise', 'Foster', '1818 Remount Rd, Charlotte, NC 28208',
     0, 0, false, 'NONE', NOW(), NOW()),

    ('01960020-0000-7000-8000-00000000000f'::uuid, '01960024-0000-7000-8000-00000000000f'::uuid,
     'CUST-PP-015', 'Anthony', 'Bryant', '3450 Tuckaseegee Rd, Charlotte, NC 28208',
     0, 0, false, 'SMS', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000010'::uuid, '01960024-0000-7000-8000-000000000010'::uuid,
     'CUST-PP-016', 'Brenda', 'Coleman', '5020 South Tryon St, Charlotte, NC 28217',
     0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000011'::uuid, '01960024-0000-7000-8000-000000000011'::uuid,
     'CUST-PP-017', 'Steven', 'Gardner', '11220 Lawyers Rd, Charlotte, NC 28227',
     0, 1, false, 'PHONE_CALL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000012'::uuid, '01960024-0000-7000-8000-000000000012'::uuid,
     'CUST-PP-018', 'Nicole', 'Harrison', '7402 E WT Harris Blvd, Charlotte, NC 28227',
     0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000013'::uuid, '01960024-0000-7000-8000-000000000013'::uuid,
     'CUST-PP-019', 'Gary', 'Alexander', '2245 Beatties Ford Rd, Charlotte, NC 28216',
     0, 0, false, 'NONE', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000014'::uuid, '01960024-0000-7000-8000-000000000014'::uuid,
     'CUST-PP-020', 'Carolyn', 'Mitchell', '4820 Park Rd, Charlotte, NC 28209',
     0, 2, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000015'::uuid, '01960024-0000-7000-8000-000000000015'::uuid,
     'CUST-PP-021', 'Timothy', 'Dixon', '6123 Albemarle Rd, Charlotte, NC 28212',
     0, 0, false, 'PHONE_CALL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000016'::uuid, '01960024-0000-7000-8000-000000000016'::uuid,
     'CUST-PP-022', 'Sandra', 'Reeves', '1540 Westover Hills Blvd, Charlotte, NC 28217',
     0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000017'::uuid, '01960024-0000-7000-8000-000000000017'::uuid,
     'CUST-PP-023', 'Walter', 'Hughes', '3305 Freedom Dr, Charlotte, NC 28208',
     0, 1, false, 'SMS', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000018'::uuid, '01960024-0000-7000-8000-000000000018'::uuid,
     'CUST-PP-024', 'Pamela', 'Lewis', '9210 Lawyers Rd, Mint Hill, NC 28227',
     0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000019'::uuid, '01960024-0000-7000-8000-000000000019'::uuid,
     'CUST-PP-025', 'Larry', 'Peterson', '2001 N Graham St, Charlotte, NC 28206',
     0, 0, false, 'PHONE_CALL', NOW(), NOW()),

    ('01960020-0000-7000-8000-00000000001a'::uuid, '01960024-0000-7000-8000-00000000001a'::uuid,
     'CUST-PP-026', 'Deborah', 'Barnes', '5512 Pineville-Matthews Rd, Charlotte, NC 28226',
     0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-00000000001b'::uuid, '01960024-0000-7000-8000-00000000001b'::uuid,
     'CUST-PP-027', 'Frank', 'Murphy', '7808 Statesville Rd, Charlotte, NC 28269',
     0, 3, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-00000000001c'::uuid, '01960024-0000-7000-8000-00000000001c'::uuid,
     'CUST-PP-028', 'Sharon', 'Powell', '3111 Nations Ford Rd, Charlotte, NC 28217',
     0, 0, false, 'NONE', NOW(), NOW()),

    ('01960020-0000-7000-8000-00000000001d'::uuid, '01960024-0000-7000-8000-00000000001d'::uuid,
     'CUST-PP-029', 'Raymond', 'Bailey', '1001 Woodlawn Rd, Charlotte, NC 28209',
     0, 1, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-00000000001e'::uuid, '01960024-0000-7000-8000-00000000001e'::uuid,
     'CUST-PP-030', 'Cynthia', 'Ross', '4401 Brookshire Fwy, Charlotte, NC 28216',
     0, 0, false, 'SMS', NOW(), NOW()),

    ('01960020-0000-7000-8000-00000000001f'::uuid, '01960024-0000-7000-8000-00000000001f'::uuid,
     'CUST-PP-031', 'Jose', 'Rivera', '2201 W Morehead St, Charlotte, NC 28208',
     0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000020'::uuid, '01960024-0000-7000-8000-000000000020'::uuid,
     'CUST-PP-032', 'Gloria', 'Turner', '6502 Highland Creek Pkwy, Charlotte, NC 28269',
     0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000021'::uuid, '01960024-0000-7000-8000-000000000021'::uuid,
     'CUST-PP-033', 'Douglas', 'Stewart', '8001 Mallard Creek Rd, Charlotte, NC 28262',
     0, 1, false, 'PHONE_CALL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000022'::uuid, '01960024-0000-7000-8000-000000000022'::uuid,
     'CUST-PP-034', 'Shirley', 'Flores', '3304 Beatties Ford Rd, Charlotte, NC 28216',
     0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000023'::uuid, '01960024-0000-7000-8000-000000000023'::uuid,
     'CUST-PP-035', 'Henry', 'Griffin', '10010 Caldwell Rd, Huntersville, NC 28078',
     0, 0, false, 'NONE', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000024'::uuid, '01960024-0000-7000-8000-000000000024'::uuid,
     'CUST-PP-036', 'Marie', 'Evans', '5030 Harrisburg Rd, Charlotte, NC 28215',
     0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000025'::uuid, '01960024-0000-7000-8000-000000000025'::uuid,
     'CUST-PP-037', 'Bruce', 'King', '2400 Rozzelles Ferry Rd, Charlotte, NC 28208',
     0, 1, false, 'SMS', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000026'::uuid, '01960024-0000-7000-8000-000000000026'::uuid,
     'CUST-PP-038', 'Wanda', 'Sanchez', '7102 Albemarle Rd, Charlotte, NC 28212',
     0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000027'::uuid, '01960024-0000-7000-8000-000000000027'::uuid,
     'CUST-PP-039', 'Keith', 'Ward', '1612 Morningside Dr, Charlotte, NC 28205',
     0, 3, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000028'::uuid, '01960024-0000-7000-8000-000000000028'::uuid,
     'CUST-PP-040', 'Phyllis', 'Long', '4201 Monroe Rd, Charlotte, NC 28205',
     0, 0, false, 'PHONE_CALL', NOW(), NOW()),

    -- INACTIVE customers (status=1)
    ('01960020-0000-7000-8000-000000000029'::uuid, '01960024-0000-7000-8000-000000000029'::uuid,
     'CUST-PP-041', 'Carl', 'Price', '9310 Lawyers Rd, Mint Hill, NC 28227',
     1, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-00000000002a'::uuid, '01960024-0000-7000-8000-00000000002a'::uuid,
     'CUST-PP-042', 'Martha', 'Scott', '3801 Freedom Dr, Charlotte, NC 28208',
     1, 0, false, 'NONE', NOW(), NOW()),

    ('01960020-0000-7000-8000-00000000002b'::uuid, '01960024-0000-7000-8000-00000000002b'::uuid,
     'CUST-PP-043', 'Albert', 'Rogers', '6208 Wilkinson Blvd, Charlotte, NC 28214',
     1, 1, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-00000000002c'::uuid, '01960024-0000-7000-8000-00000000002c'::uuid,
     'CUST-PP-044', 'Virginia', 'Henderson', '1402 Remount Rd, Charlotte, NC 28208',
     1, 0, false, 'PHONE_CALL', NOW(), NOW()),

    -- ON_HOLD customer (status=2)
    ('01960020-0000-7000-8000-00000000002d'::uuid, '01960024-0000-7000-8000-00000000002d'::uuid,
     'CUST-PP-045', 'Harry', 'Hill', '5510 South Tryon St, Charlotte, NC 28217',
     2, 0, false, 'SMS', NOW(), NOW()),

    -- Active customers continued (46–50)
    ('01960020-0000-7000-8000-00000000002e'::uuid, '01960024-0000-7000-8000-00000000002e'::uuid,
     'CUST-PP-046', 'Doris', 'Wood', '2310 Tyvola Rd, Charlotte, NC 28217',
     0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-00000000002f'::uuid, '01960024-0000-7000-8000-00000000002f'::uuid,
     'CUST-PP-047', 'Raymond', 'James', '7503 Caldwell Rd, Concord, NC 28027',
     0, 2, false, 'EMAIL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000030'::uuid, '01960024-0000-7000-8000-000000000030'::uuid,
     'CUST-PP-048', 'Betty', 'Crawford', '4109 N Sharon Amity Rd, Charlotte, NC 28205',
     0, 0, false, 'NONE', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000031'::uuid, '01960024-0000-7000-8000-000000000031'::uuid,
     'CUST-PP-049', 'Samuel', 'Reed', '3005 Beatties Ford Rd, Charlotte, NC 28216',
     0, 1, false, 'PHONE_CALL', NOW(), NOW()),

    ('01960020-0000-7000-8000-000000000032'::uuid, '01960024-0000-7000-8000-000000000032'::uuid,
     'CUST-PP-050', 'Dorothy', 'Bell', '8411 Albemarle Rd, Charlotte, NC 28227',
     0, 0, false, 'EMAIL', NOW(), NOW())

ON CONFLICT (customer_id) DO NOTHING;

-- =========================================================================
-- SECTION 2: COMMERCIAL PARTY — 20 NC fleet operators (Charlotte metro)
--            Industries: trucking, construction, waste, landscaping,
--            logistics, HVAC/plumbing, ready-mix, electrical, aggregates,
--            industrial cleaning, delivery, crane/rigging, propane/fuels,
--            road services, produce distribution, metal recycling,
--            grading/excavation, environmental, tree service, moving/storage
--   Status  : 18 ACTIVE, 1 INACTIVE, 1 ON_HOLD
--   Tier    : 8 STANDARD, 5 BRONZE, 4 SILVER, 2 GOLD, 1 PLATINUM
--   PartyType: COMMERCIAL = 1
-- =========================================================================

INSERT INTO commercial_party (customer_id, customer_number, party_number,
                               legal_name, display_name, tax_id, billing_terms_id,
                               party_type, status, tier, tier_manual_override, parent_party_id,
                               primary_address,
                               created_at, updated_at)
VALUES
    -- STANDARD tier (rows 1–8)
    ('01960021-0000-7000-8000-000000000001'::uuid,
     'CUST-CP-001', 'PARTY-CP-001',
     'Piedmont Freight Carriers LLC', 'Piedmont Freight',
     '27-4481203', 'NET-30', 1, 0, 0, false, NULL,
     '3501 Trailer Park Dr, Charlotte, NC 28269',
     NOW(), NOW()),

    ('01960021-0000-7000-8000-000000000002'::uuid,
     'CUST-CP-002', 'PARTY-CP-002',
     'Carolina Concrete Supply Co.', 'Carolina Concrete',
     '46-3215872', 'NET-30', 1, 0, 0, false, NULL,
     '8200 Old Concord Rd, Concord, NC 28027',
     NOW(), NOW()),

    ('01960021-0000-7000-8000-000000000003'::uuid,
     'CUST-CP-003', 'PARTY-CP-003',
     'Queen City Waste Management Inc.', 'QC Waste',
     '35-2198043', 'NET-45', 1, 0, 0, false, NULL,
     '4801 Wilkinson Blvd, Charlotte, NC 28208',
     NOW(), NOW()),

    ('01960021-0000-7000-8000-000000000004'::uuid,
     'CUST-CP-004', 'PARTY-CP-004',
     'Blue Ridge Landscaping Services LLC', 'Blue Ridge Landscaping',
     '82-1563489', 'NET-30', 1, 0, 0, false, NULL,
     '201 Brawley School Rd, Mooresville, NC 28117',
     NOW(), NOW()),

    ('01960021-0000-7000-8000-000000000005'::uuid,
     'CUST-CP-005', 'PARTY-CP-005',
     'Tarheel Logistics Group LLC', 'Tarheel Logistics',
     '61-7832941', 'NET-45', 1, 0, 0, false, NULL,
     '1120 Armstrong Rd, Gastonia, NC 28052',
     NOW(), NOW()),

    ('01960021-0000-7000-8000-000000000006'::uuid,
     'CUST-CP-006', 'PARTY-CP-006',
     'Mecklenburg Plumbing & Mechanical LLC', 'Meck Plumbing',
     '47-9210583', 'NET-30', 1, 0, 0, false, NULL,
     '6120 Old Pineville Rd, Charlotte, NC 28217',
     NOW(), NOW()),

    ('01960021-0000-7000-8000-000000000007'::uuid,
     'CUST-CP-007', 'PARTY-CP-007',
     'Piedmont Ready Mix Corp.', 'Piedmont Ready Mix',
     '56-3841027', 'NET-60', 1, 0, 0, false, NULL,
     '2505 Dale Earnhardt Blvd, Kannapolis, NC 28083',
     NOW(), NOW()),

    ('01960021-0000-7000-8000-000000000008'::uuid,
     'CUST-CP-008', 'PARTY-CP-008',
     'Carolina Power & Electrical Services Inc.', 'Carolina Power',
     '29-6713854', 'NET-30', 1, 0, 0, false, NULL,
     '16345 Old Statesville Rd, Huntersville, NC 28078',
     NOW(), NOW()),

    -- BRONZE tier (rows 9–13)
    ('01960021-0000-7000-8000-000000000009'::uuid,
     'CUST-CP-009', 'PARTY-CP-009',
     'Blue Stone Aggregate & Gravel LLC', 'Blue Stone Aggregate',
     '74-5291836', 'NET-45', 1, 0, 1, false, NULL,
     '808 E Broad St, Statesville, NC 28677',
     NOW(), NOW()),

    ('01960021-0000-7000-8000-00000000000a'::uuid,
     'CUST-CP-010', 'PARTY-CP-010',
     'Cabarrus Industrial Cleaning Corp.', 'Cabarrus Cleaning',
     '31-4827653', 'NET-30', 1, 0, 1, false, NULL,
     '4540 Poplar Tent Rd, Concord, NC 28027',
     NOW(), NOW()),

    ('01960021-0000-7000-8000-00000000000b'::uuid,
     'CUST-CP-011', 'PARTY-CP-011',
     'Southeast Delivery Solutions LLC', 'SE Delivery',
     '68-9201374', 'NET-30', 1, 0, 1, false, NULL,
     '7302 Albemarle Rd, Charlotte, NC 28212',
     NOW(), NOW()),

    ('01960021-0000-7000-8000-00000000000c'::uuid,
     'CUST-CP-012', 'PARTY-CP-012',
     'Carolinas Crane & Rigging Co.', 'Carolinas Crane',
     '45-7832016', 'NET-45', 1, 0, 1, false, NULL,
     '2120 Southend Dr, Charlotte, NC 28203',
     NOW(), NOW()),

    ('01960021-0000-7000-8000-00000000000d'::uuid,
     'CUST-CP-013', 'PARTY-CP-013',
     'Lake Norman Propane & Fuels LLC', 'LKN Propane',
     '52-1938476', 'COD', 1, 0, 1, false, NULL,
     '1003 Harbor Rd, Mooresville, NC 28117',
     NOW(), NOW()),

    -- SILVER tier (rows 14–17)
    ('01960021-0000-7000-8000-00000000000e'::uuid,
     'CUST-CP-014', 'PARTY-CP-014',
     'Rowan County Road Services LLC', 'Rowan Road Services',
     '73-4826015', 'NET-45', 1, 0, 2, false, NULL,
     '505 N Main St, Salisbury, NC 28144',
     NOW(), NOW()),

    ('01960021-0000-7000-8000-00000000000f'::uuid,
     'CUST-CP-015', 'PARTY-CP-015',
     'Carolina Fresh Produce Distribution Inc.', 'Carolina Fresh',
     '28-5943016', 'NET-30', 1, 0, 2, false, NULL,
     '3900 Morningside Industrial Dr, Charlotte, NC 28215',
     NOW(), NOW()),

    ('01960021-0000-7000-8000-000000000010'::uuid,
     'CUST-CP-016', 'PARTY-CP-016',
     'Piedmont Steel & Metal Recycling LLC', 'Piedmont Metals',
     '34-8710253', 'NET-60', 1, 0, 2, false, NULL,
     '1205 Industrial Ave, Gastonia, NC 28052',
     NOW(), NOW()),

    ('01960021-0000-7000-8000-000000000011'::uuid,
     'CUST-CP-017', 'PARTY-CP-017',
     'Union County Grading & Excavation Inc.', 'Union Grading',
     '67-2194830', 'NET-45', 1, 0, 2, false, NULL,
     '2301 N Rocky River Rd, Monroe, NC 28112',
     NOW(), NOW()),

    -- GOLD tier (rows 18–19)
    ('01960021-0000-7000-8000-000000000012'::uuid,
     'CUST-CP-018', 'PARTY-CP-018',
     'Carolina Septic & Environmental LLC', 'Carolina Septic',
     '49-8023174', 'NET-30', 1, 0, 3, false, NULL,
     '5820 Poplar Tent Rd, Concord, NC 28027',
     NOW(), NOW()),

    -- INACTIVE (status=1), GOLD tier
    ('01960021-0000-7000-8000-000000000013'::uuid,
     'CUST-CP-019', 'PARTY-CP-019',
     'Mecklenburg Tree Service & Timber Inc.', 'Meck Tree',
     '83-5012967', 'NET-30', 1, 1, 3, false, NULL,
     '6401 Beatties Ford Rd, Charlotte, NC 28216',
     NOW(), NOW()),

    -- ON_HOLD (status=2), PLATINUM tier
    ('01960021-0000-7000-8000-000000000014'::uuid,
     'CUST-CP-020', 'PARTY-CP-020',
     'Highland Moving & Storage LLC', 'Highland Moving',
     '55-1874392', 'NET-60', 1, 2, 4, false, NULL,
     '1801 Cross Beam Dr, Charlotte, NC 28217',
     NOW(), NOW())

ON CONFLICT (customer_id) DO NOTHING;

-- =========================================================================
-- SECTION 3: CONTACT — 20 rows, one per commercial party
--            Each contact is a separate individual (not from person_party).
--            person_id values use placeholder namespace 01960025-*.
-- =========================================================================

INSERT INTO contact (contact_id, party_id, person_id,
                     first_name, last_name, email, phone_number,
                     active, created_at, updated_at)
VALUES
    ('01960022-0000-7000-8000-000000000001'::uuid,
     '01960021-0000-7000-8000-000000000001'::uuid,
     '01960025-0000-7000-8000-000000000001'::uuid,
     'Greg', 'Whitfield', 'g.whitfield@piedmontfreight.example.com', '704-555-3001',
     true, NOW(), NOW()),

    ('01960022-0000-7000-8000-000000000002'::uuid,
     '01960021-0000-7000-8000-000000000002'::uuid,
     '01960025-0000-7000-8000-000000000002'::uuid,
     'Teresa', 'Mullen', 't.mullen@carolinaconcrete.example.com', '704-555-3002',
     true, NOW(), NOW()),

    ('01960022-0000-7000-8000-000000000003'::uuid,
     '01960021-0000-7000-8000-000000000003'::uuid,
     '01960025-0000-7000-8000-000000000003'::uuid,
     'Darnell', 'Okafor', 'd.okafor@qcwaste.example.com', '704-555-3003',
     true, NOW(), NOW()),

    ('01960022-0000-7000-8000-000000000004'::uuid,
     '01960021-0000-7000-8000-000000000004'::uuid,
     '01960025-0000-7000-8000-000000000004'::uuid,
     'Brittany', 'Norris', 'b.norris@blueridgelandscaping.example.com', '704-555-3004',
     true, NOW(), NOW()),

    ('01960022-0000-7000-8000-000000000005'::uuid,
     '01960021-0000-7000-8000-000000000005'::uuid,
     '01960025-0000-7000-8000-000000000005'::uuid,
     'Marcus', 'Tillman', 'm.tillman@tarheellogistics.example.com', '704-555-3005',
     true, NOW(), NOW()),

    ('01960022-0000-7000-8000-000000000006'::uuid,
     '01960021-0000-7000-8000-000000000006'::uuid,
     '01960025-0000-7000-8000-000000000006'::uuid,
     'Christine', 'Walters', 'c.walters@meckplumbing.example.com', '704-555-3006',
     true, NOW(), NOW()),

    ('01960022-0000-7000-8000-000000000007'::uuid,
     '01960021-0000-7000-8000-000000000007'::uuid,
     '01960025-0000-7000-8000-000000000007'::uuid,
     'Donald', 'Frazier', 'd.frazier@piedmontreadymix.example.com', '704-555-3007',
     true, NOW(), NOW()),

    ('01960022-0000-7000-8000-000000000008'::uuid,
     '01960021-0000-7000-8000-000000000008'::uuid,
     '01960025-0000-7000-8000-000000000008'::uuid,
     'Alicia', 'Stephens', 'a.stephens@carolinapower.example.com', '704-555-3008',
     true, NOW(), NOW()),

    ('01960022-0000-7000-8000-000000000009'::uuid,
     '01960021-0000-7000-8000-000000000009'::uuid,
     '01960025-0000-7000-8000-000000000009'::uuid,
     'Keith', 'Burnham', 'k.burnham@bluestoneaggregate.example.com', '704-555-3009',
     true, NOW(), NOW()),

    ('01960022-0000-7000-8000-00000000000a'::uuid,
     '01960021-0000-7000-8000-00000000000a'::uuid,
     '01960025-0000-7000-8000-00000000000a'::uuid,
     'Tamara', 'McPherson', 't.mcpherson@cabarruscleaning.example.com', '704-555-3010',
     true, NOW(), NOW()),

    ('01960022-0000-7000-8000-00000000000b'::uuid,
     '01960021-0000-7000-8000-00000000000b'::uuid,
     '01960025-0000-7000-8000-00000000000b'::uuid,
     'Wesley', 'Parrish', 'w.parrish@sedelivery.example.com', '980-555-3011',
     true, NOW(), NOW()),

    ('01960022-0000-7000-8000-00000000000c'::uuid,
     '01960021-0000-7000-8000-00000000000c'::uuid,
     '01960025-0000-7000-8000-00000000000c'::uuid,
     'Renee', 'Holt', 'r.holt@carolinascrane.example.com', '704-555-3012',
     true, NOW(), NOW()),

    ('01960022-0000-7000-8000-00000000000d'::uuid,
     '01960021-0000-7000-8000-00000000000d'::uuid,
     '01960025-0000-7000-8000-00000000000d'::uuid,
     'Calvin', 'Dunmore', 'c.dunmore@lknpropane.example.com', '704-555-3013',
     true, NOW(), NOW()),

    ('01960022-0000-7000-8000-00000000000e'::uuid,
     '01960021-0000-7000-8000-00000000000e'::uuid,
     '01960025-0000-7000-8000-00000000000e'::uuid,
     'Latasha', 'Gooden', 'l.gooden@rowanroad.example.com', '704-555-3014',
     true, NOW(), NOW()),

    ('01960022-0000-7000-8000-00000000000f'::uuid,
     '01960021-0000-7000-8000-00000000000f'::uuid,
     '01960025-0000-7000-8000-00000000000f'::uuid,
     'Bryan', 'Cantrell', 'b.cantrell@carolinafresh.example.com', '980-555-3015',
     true, NOW(), NOW()),

    ('01960022-0000-7000-8000-000000000010'::uuid,
     '01960021-0000-7000-8000-000000000010'::uuid,
     '01960025-0000-7000-8000-000000000010'::uuid,
     'Monica', 'Byrd', 'm.byrd@piedmontmetals.example.com', '704-555-3016',
     true, NOW(), NOW()),

    ('01960022-0000-7000-8000-000000000011'::uuid,
     '01960021-0000-7000-8000-000000000011'::uuid,
     '01960025-0000-7000-8000-000000000011'::uuid,
     'Cedric', 'Blackwell', 'c.blackwell@uniongrading.example.com', '704-555-3017',
     true, NOW(), NOW()),

    ('01960022-0000-7000-8000-000000000012'::uuid,
     '01960021-0000-7000-8000-000000000012'::uuid,
     '01960025-0000-7000-8000-000000000012'::uuid,
     'Veronica', 'Pratt', 'v.pratt@carolinaseptic.example.com', '704-555-3018',
     true, NOW(), NOW()),

    ('01960022-0000-7000-8000-000000000013'::uuid,
     '01960021-0000-7000-8000-000000000013'::uuid,
     '01960025-0000-7000-8000-000000000013'::uuid,
     'Jonathon', 'Culpepper', 'j.culpepper@mecktree.example.com', '704-555-3019',
     true, NOW(), NOW()),

    ('01960022-0000-7000-8000-000000000014'::uuid,
     '01960021-0000-7000-8000-000000000014'::uuid,
     '01960025-0000-7000-8000-000000000014'::uuid,
     'Sheryl', 'Davenport', 's.davenport@highlandmoving.example.com', '980-555-3020',
     true, NOW(), NOW())

ON CONFLICT (contact_id) DO NOTHING;

-- =========================================================================
-- SECTION 4: BILLING CONTACTS — 20 rows, one per commercial party
--            Represents the org-level billing/admin contact inbox preserved
--            from the former commercial_party.email / phone_number columns.
--            person_id values use namespace 01960026-* (real Person rows
--            seeded in pos-people R__seed_people_operational_data.sql).
--            contact_id values use namespace 01960023-*.
-- =========================================================================

INSERT INTO contact (contact_id, party_id, person_id,
                     first_name, last_name, email, phone_number,
                     active, created_at, updated_at)
VALUES
    ('01960023-0000-7000-8000-000000000001'::uuid,
     '01960021-0000-7000-8000-000000000001'::uuid,
     '01960026-0000-7000-8000-000000000001'::uuid,
     'General', 'Piedmont Freight', 'info@piedmontfreight.example.com', '704-555-2001',
     true, NOW(), NOW()),

    ('01960023-0000-7000-8000-000000000002'::uuid,
     '01960021-0000-7000-8000-000000000002'::uuid,
     '01960026-0000-7000-8000-000000000002'::uuid,
     'Accounts', 'Carolina Concrete', 'accounts@carolinaconcrete.example.com', '704-555-2002',
     true, NOW(), NOW()),

    ('01960023-0000-7000-8000-000000000003'::uuid,
     '01960021-0000-7000-8000-000000000003'::uuid,
     '01960026-0000-7000-8000-000000000003'::uuid,
     'Billing', 'QC Waste', 'billing@qcwaste.example.com', '704-555-2003',
     true, NOW(), NOW()),

    ('01960023-0000-7000-8000-000000000004'::uuid,
     '01960021-0000-7000-8000-000000000004'::uuid,
     '01960026-0000-7000-8000-000000000004'::uuid,
     'Fleet', 'Blue Ridge Landscaping', 'fleet@blueridgelandscaping.example.com', '704-555-2004',
     true, NOW(), NOW()),

    ('01960023-0000-7000-8000-000000000005'::uuid,
     '01960021-0000-7000-8000-000000000005'::uuid,
     '01960026-0000-7000-8000-000000000005'::uuid,
     'Operations', 'Tarheel Logistics', 'ops@tarheellogistics.example.com', '704-555-2005',
     true, NOW(), NOW()),

    ('01960023-0000-7000-8000-000000000006'::uuid,
     '01960021-0000-7000-8000-000000000006'::uuid,
     '01960026-0000-7000-8000-000000000006'::uuid,
     'Dispatch', 'Meck Plumbing', 'dispatch@meckplumbing.example.com', '704-555-2006',
     true, NOW(), NOW()),

    ('01960023-0000-7000-8000-000000000007'::uuid,
     '01960021-0000-7000-8000-000000000007'::uuid,
     '01960026-0000-7000-8000-000000000007'::uuid,
     'Fleet', 'Piedmont Ready Mix', 'fleet@piedmontreadymix.example.com', '704-555-2007',
     true, NOW(), NOW()),

    ('01960023-0000-7000-8000-000000000008'::uuid,
     '01960021-0000-7000-8000-000000000008'::uuid,
     '01960026-0000-7000-8000-000000000008'::uuid,
     'Service', 'Carolina Power', 'service@carolinapower.example.com', '704-555-2008',
     true, NOW(), NOW()),

    ('01960023-0000-7000-8000-000000000009'::uuid,
     '01960021-0000-7000-8000-000000000009'::uuid,
     '01960026-0000-7000-8000-000000000009'::uuid,
     'Accounts', 'Blue Stone Aggregate', 'accounts@bluestoneaggregate.example.com', '704-555-2009',
     true, NOW(), NOW()),

    ('01960023-0000-7000-8000-00000000000a'::uuid,
     '01960021-0000-7000-8000-00000000000a'::uuid,
     '01960026-0000-7000-8000-00000000000a'::uuid,
     'Billing', 'Cabarrus Cleaning', 'billing@cabarruscleaning.example.com', '704-555-2010',
     true, NOW(), NOW()),

    ('01960023-0000-7000-8000-00000000000b'::uuid,
     '01960021-0000-7000-8000-00000000000b'::uuid,
     '01960026-0000-7000-8000-00000000000b'::uuid,
     'Operations', 'SE Delivery', 'ops@sedelivery.example.com', '980-555-2011',
     true, NOW(), NOW()),

    ('01960023-0000-7000-8000-00000000000c'::uuid,
     '01960021-0000-7000-8000-00000000000c'::uuid,
     '01960026-0000-7000-8000-00000000000c'::uuid,
     'Fleet', 'Carolinas Crane', 'fleet@carolinascrane.example.com', '704-555-2012',
     true, NOW(), NOW()),

    ('01960023-0000-7000-8000-00000000000d'::uuid,
     '01960021-0000-7000-8000-00000000000d'::uuid,
     '01960026-0000-7000-8000-00000000000d'::uuid,
     'Service', 'LKN Propane', 'service@lknpropane.example.com', '704-555-2013',
     true, NOW(), NOW()),

    ('01960023-0000-7000-8000-00000000000e'::uuid,
     '01960021-0000-7000-8000-00000000000e'::uuid,
     '01960026-0000-7000-8000-00000000000e'::uuid,
     'Accounts', 'Rowan Road Services', 'accounts@rowanroad.example.com', '704-555-2014',
     true, NOW(), NOW()),

    ('01960023-0000-7000-8000-00000000000f'::uuid,
     '01960021-0000-7000-8000-00000000000f'::uuid,
     '01960026-0000-7000-8000-00000000000f'::uuid,
     'Dispatch', 'Carolina Fresh', 'dispatch@carolinafresh.example.com', '980-555-2015',
     true, NOW(), NOW()),

    ('01960023-0000-7000-8000-000000000010'::uuid,
     '01960021-0000-7000-8000-000000000010'::uuid,
     '01960026-0000-7000-8000-000000000010'::uuid,
     'Billing', 'Piedmont Metals', 'billing@piedmontmetals.example.com', '704-555-2016',
     true, NOW(), NOW()),

    ('01960023-0000-7000-8000-000000000011'::uuid,
     '01960021-0000-7000-8000-000000000011'::uuid,
     '01960026-0000-7000-8000-000000000011'::uuid,
     'Operations', 'Union Grading', 'ops@uniongrading.example.com', '704-555-2017',
     true, NOW(), NOW()),

    ('01960023-0000-7000-8000-000000000012'::uuid,
     '01960021-0000-7000-8000-000000000012'::uuid,
     '01960026-0000-7000-8000-000000000012'::uuid,
     'Service', 'Carolina Septic', 'service@carolinaseptic.example.com', '704-555-2018',
     true, NOW(), NOW()),

    ('01960023-0000-7000-8000-000000000013'::uuid,
     '01960021-0000-7000-8000-000000000013'::uuid,
     '01960026-0000-7000-8000-000000000013'::uuid,
     'Fleet', 'Meck Tree', 'fleet@mecktree.example.com', '704-555-2019',
     true, NOW(), NOW()),

    ('01960023-0000-7000-8000-000000000014'::uuid,
     '01960021-0000-7000-8000-000000000014'::uuid,
     '01960026-0000-7000-8000-000000000014'::uuid,
     'Billing', 'Highland Moving', 'billing@highlandmoving.example.com', '980-555-2020',
     true, NOW(), NOW())

ON CONFLICT (contact_id) DO NOTHING;

-- =========================================================================
-- SECTION 5: CONTACT PERSON_PARTY — 20 rows, one per commercial contact
--            These persons exist in pos-people under namespace 01960025-*.
--            They must be in person_party to satisfy the FK on
--            party_relationship.to_person_id → person_party.customer_id.
-- =========================================================================

INSERT INTO person_party (customer_id, person_id, customer_number,
                          first_name, last_name, primary_address,
                          status, tier, tier_manual_override, preferred_contact_method,
                          created_at, updated_at)
VALUES
    ('01960025-0000-7000-8000-000000000001'::uuid, '01960025-0000-7000-8000-000000000001'::uuid,
     'CUST-CPC-001', 'Greg', 'Whitfield', NULL, 0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960025-0000-7000-8000-000000000002'::uuid, '01960025-0000-7000-8000-000000000002'::uuid,
     'CUST-CPC-002', 'Teresa', 'Mullen', NULL, 0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960025-0000-7000-8000-000000000003'::uuid, '01960025-0000-7000-8000-000000000003'::uuid,
     'CUST-CPC-003', 'Darnell', 'Okafor', NULL, 0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960025-0000-7000-8000-000000000004'::uuid, '01960025-0000-7000-8000-000000000004'::uuid,
     'CUST-CPC-004', 'Brittany', 'Norris', NULL, 0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960025-0000-7000-8000-000000000005'::uuid, '01960025-0000-7000-8000-000000000005'::uuid,
     'CUST-CPC-005', 'Marcus', 'Tillman', NULL, 0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960025-0000-7000-8000-000000000006'::uuid, '01960025-0000-7000-8000-000000000006'::uuid,
     'CUST-CPC-006', 'Christine', 'Walters', NULL, 0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960025-0000-7000-8000-000000000007'::uuid, '01960025-0000-7000-8000-000000000007'::uuid,
     'CUST-CPC-007', 'Donald', 'Frazier', NULL, 0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960025-0000-7000-8000-000000000008'::uuid, '01960025-0000-7000-8000-000000000008'::uuid,
     'CUST-CPC-008', 'Alicia', 'Stephens', NULL, 0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960025-0000-7000-8000-000000000009'::uuid, '01960025-0000-7000-8000-000000000009'::uuid,
     'CUST-CPC-009', 'Keith', 'Burnham', NULL, 0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960025-0000-7000-8000-00000000000a'::uuid, '01960025-0000-7000-8000-00000000000a'::uuid,
     'CUST-CPC-010', 'Tamara', 'McPherson', NULL, 0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960025-0000-7000-8000-00000000000b'::uuid, '01960025-0000-7000-8000-00000000000b'::uuid,
     'CUST-CPC-011', 'Wesley', 'Parrish', NULL, 0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960025-0000-7000-8000-00000000000c'::uuid, '01960025-0000-7000-8000-00000000000c'::uuid,
     'CUST-CPC-012', 'Renee', 'Holt', NULL, 0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960025-0000-7000-8000-00000000000d'::uuid, '01960025-0000-7000-8000-00000000000d'::uuid,
     'CUST-CPC-013', 'Calvin', 'Dunmore', NULL, 0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960025-0000-7000-8000-00000000000e'::uuid, '01960025-0000-7000-8000-00000000000e'::uuid,
     'CUST-CPC-014', 'Latasha', 'Gooden', NULL, 0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960025-0000-7000-8000-00000000000f'::uuid, '01960025-0000-7000-8000-00000000000f'::uuid,
     'CUST-CPC-015', 'Bryan', 'Cantrell', NULL, 0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960025-0000-7000-8000-000000000010'::uuid, '01960025-0000-7000-8000-000000000010'::uuid,
     'CUST-CPC-016', 'Monica', 'Byrd', NULL, 0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960025-0000-7000-8000-000000000011'::uuid, '01960025-0000-7000-8000-000000000011'::uuid,
     'CUST-CPC-017', 'Cedric', 'Blackwell', NULL, 0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960025-0000-7000-8000-000000000012'::uuid, '01960025-0000-7000-8000-000000000012'::uuid,
     'CUST-CPC-018', 'Veronica', 'Pratt', NULL, 0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960025-0000-7000-8000-000000000013'::uuid, '01960025-0000-7000-8000-000000000013'::uuid,
     'CUST-CPC-019', 'Jonathon', 'Culpepper', NULL, 0, 0, false, 'EMAIL', NOW(), NOW()),

    ('01960025-0000-7000-8000-000000000014'::uuid, '01960025-0000-7000-8000-000000000014'::uuid,
     'CUST-CPC-020', 'Sheryl', 'Davenport', NULL, 0, 0, false, 'EMAIL', NOW(), NOW())

ON CONFLICT (customer_id) DO NOTHING;

-- =========================================================================
-- SECTION 6: CONTACT_POINT — email + phone for each contact person
--            person_id FK → person_party.customer_id (namespace 01960025-*)
--            Email IDs use namespace 01960027-*, phone IDs use 01960028-*.
-- =========================================================================

INSERT INTO contact_point (contact_point_id, person_id, contact_type, value, is_primary, created_at, updated_at)
VALUES
    ('01960027-0000-7000-8000-000000000001'::uuid, '01960025-0000-7000-8000-000000000001'::uuid, 'EMAIL', 'g.whitfield@piedmontfreight.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-000000000001'::uuid, '01960025-0000-7000-8000-000000000001'::uuid, 'PHONE_WORK', '704-555-3001', true, NOW(), NOW()),
    ('01960027-0000-7000-8000-000000000002'::uuid, '01960025-0000-7000-8000-000000000002'::uuid, 'EMAIL', 't.mullen@carolinaconcrete.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-000000000002'::uuid, '01960025-0000-7000-8000-000000000002'::uuid, 'PHONE_WORK', '704-555-3002', true, NOW(), NOW()),
    ('01960027-0000-7000-8000-000000000003'::uuid, '01960025-0000-7000-8000-000000000003'::uuid, 'EMAIL', 'd.okafor@qcwaste.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-000000000003'::uuid, '01960025-0000-7000-8000-000000000003'::uuid, 'PHONE_WORK', '704-555-3003', true, NOW(), NOW()),
    ('01960027-0000-7000-8000-000000000004'::uuid, '01960025-0000-7000-8000-000000000004'::uuid, 'EMAIL', 'b.norris@blueridgelandscaping.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-000000000004'::uuid, '01960025-0000-7000-8000-000000000004'::uuid, 'PHONE_WORK', '704-555-3004', true, NOW(), NOW()),
    ('01960027-0000-7000-8000-000000000005'::uuid, '01960025-0000-7000-8000-000000000005'::uuid, 'EMAIL', 'm.tillman@tarheellogistics.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-000000000005'::uuid, '01960025-0000-7000-8000-000000000005'::uuid, 'PHONE_WORK', '704-555-3005', true, NOW(), NOW()),
    ('01960027-0000-7000-8000-000000000006'::uuid, '01960025-0000-7000-8000-000000000006'::uuid, 'EMAIL', 'c.walters@meckplumbing.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-000000000006'::uuid, '01960025-0000-7000-8000-000000000006'::uuid, 'PHONE_WORK', '704-555-3006', true, NOW(), NOW()),
    ('01960027-0000-7000-8000-000000000007'::uuid, '01960025-0000-7000-8000-000000000007'::uuid, 'EMAIL', 'd.frazier@piedmontreadymix.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-000000000007'::uuid, '01960025-0000-7000-8000-000000000007'::uuid, 'PHONE_WORK', '704-555-3007', true, NOW(), NOW()),
    ('01960027-0000-7000-8000-000000000008'::uuid, '01960025-0000-7000-8000-000000000008'::uuid, 'EMAIL', 'a.stephens@carolinapower.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-000000000008'::uuid, '01960025-0000-7000-8000-000000000008'::uuid, 'PHONE_WORK', '704-555-3008', true, NOW(), NOW()),
    ('01960027-0000-7000-8000-000000000009'::uuid, '01960025-0000-7000-8000-000000000009'::uuid, 'EMAIL', 'k.burnham@bluestoneaggregate.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-000000000009'::uuid, '01960025-0000-7000-8000-000000000009'::uuid, 'PHONE_WORK', '704-555-3009', true, NOW(), NOW()),
    ('01960027-0000-7000-8000-00000000000a'::uuid, '01960025-0000-7000-8000-00000000000a'::uuid, 'EMAIL', 't.mcpherson@cabarruscleaning.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-00000000000a'::uuid, '01960025-0000-7000-8000-00000000000a'::uuid, 'PHONE_WORK', '704-555-3010', true, NOW(), NOW()),
    ('01960027-0000-7000-8000-00000000000b'::uuid, '01960025-0000-7000-8000-00000000000b'::uuid, 'EMAIL', 'w.parrish@sedelivery.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-00000000000b'::uuid, '01960025-0000-7000-8000-00000000000b'::uuid, 'PHONE_WORK', '980-555-3011', true, NOW(), NOW()),
    ('01960027-0000-7000-8000-00000000000c'::uuid, '01960025-0000-7000-8000-00000000000c'::uuid, 'EMAIL', 'r.holt@carolinascrane.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-00000000000c'::uuid, '01960025-0000-7000-8000-00000000000c'::uuid, 'PHONE_WORK', '704-555-3012', true, NOW(), NOW()),
    ('01960027-0000-7000-8000-00000000000d'::uuid, '01960025-0000-7000-8000-00000000000d'::uuid, 'EMAIL', 'c.dunmore@lknpropane.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-00000000000d'::uuid, '01960025-0000-7000-8000-00000000000d'::uuid, 'PHONE_WORK', '704-555-3013', true, NOW(), NOW()),
    ('01960027-0000-7000-8000-00000000000e'::uuid, '01960025-0000-7000-8000-00000000000e'::uuid, 'EMAIL', 'l.gooden@rowanroad.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-00000000000e'::uuid, '01960025-0000-7000-8000-00000000000e'::uuid, 'PHONE_WORK', '704-555-3014', true, NOW(), NOW()),
    ('01960027-0000-7000-8000-00000000000f'::uuid, '01960025-0000-7000-8000-00000000000f'::uuid, 'EMAIL', 'b.cantrell@carolinafresh.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-00000000000f'::uuid, '01960025-0000-7000-8000-00000000000f'::uuid, 'PHONE_WORK', '980-555-3015', true, NOW(), NOW()),
    ('01960027-0000-7000-8000-000000000010'::uuid, '01960025-0000-7000-8000-000000000010'::uuid, 'EMAIL', 'm.byrd@piedmontmetals.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-000000000010'::uuid, '01960025-0000-7000-8000-000000000010'::uuid, 'PHONE_WORK', '704-555-3016', true, NOW(), NOW()),
    ('01960027-0000-7000-8000-000000000011'::uuid, '01960025-0000-7000-8000-000000000011'::uuid, 'EMAIL', 'c.blackwell@uniongrading.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-000000000011'::uuid, '01960025-0000-7000-8000-000000000011'::uuid, 'PHONE_WORK', '704-555-3017', true, NOW(), NOW()),
    ('01960027-0000-7000-8000-000000000012'::uuid, '01960025-0000-7000-8000-000000000012'::uuid, 'EMAIL', 'v.pratt@carolinaseptic.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-000000000012'::uuid, '01960025-0000-7000-8000-000000000012'::uuid, 'PHONE_WORK', '704-555-3018', true, NOW(), NOW()),
    ('01960027-0000-7000-8000-000000000013'::uuid, '01960025-0000-7000-8000-000000000013'::uuid, 'EMAIL', 'j.culpepper@mecktree.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-000000000013'::uuid, '01960025-0000-7000-8000-000000000013'::uuid, 'PHONE_WORK', '704-555-3019', true, NOW(), NOW()),
    ('01960027-0000-7000-8000-000000000014'::uuid, '01960025-0000-7000-8000-000000000014'::uuid, 'EMAIL', 's.davenport@highlandmoving.example.com', true, NOW(), NOW()),
    ('01960028-0000-7000-8000-000000000014'::uuid, '01960025-0000-7000-8000-000000000014'::uuid, 'PHONE_WORK', '980-555-3020', true, NOW(), NOW())

ON CONFLICT (contact_point_id) DO NOTHING;

-- =========================================================================
-- SECTION 7: PARTY_RELATIONSHIP — 20 rows, one per commercial party
--            from_party_id → commercial_party.customer_id (01960021-*)
--            to_person_id  → person_party.customer_id     (01960025-*)
--            effective_start_date = 2020-01-01 (pre-dates all test workorders)
-- =========================================================================

INSERT INTO party_relationship (party_relationship_id, from_party_id, to_person_id,
                                is_primary_billing_contact, effective_start_date, effective_end_date,
                                created_at, updated_at)
VALUES
    ('01960029-0000-7000-8000-000000000001'::uuid, '01960021-0000-7000-8000-000000000001'::uuid, '01960025-0000-7000-8000-000000000001'::uuid, false, '2020-01-01', NULL, NOW(), NOW()),
    ('01960029-0000-7000-8000-000000000002'::uuid, '01960021-0000-7000-8000-000000000002'::uuid, '01960025-0000-7000-8000-000000000002'::uuid, false, '2020-01-01', NULL, NOW(), NOW()),
    ('01960029-0000-7000-8000-000000000003'::uuid, '01960021-0000-7000-8000-000000000003'::uuid, '01960025-0000-7000-8000-000000000003'::uuid, false, '2020-01-01', NULL, NOW(), NOW()),
    ('01960029-0000-7000-8000-000000000004'::uuid, '01960021-0000-7000-8000-000000000004'::uuid, '01960025-0000-7000-8000-000000000004'::uuid, false, '2020-01-01', NULL, NOW(), NOW()),
    ('01960029-0000-7000-8000-000000000005'::uuid, '01960021-0000-7000-8000-000000000005'::uuid, '01960025-0000-7000-8000-000000000005'::uuid, false, '2020-01-01', NULL, NOW(), NOW()),
    ('01960029-0000-7000-8000-000000000006'::uuid, '01960021-0000-7000-8000-000000000006'::uuid, '01960025-0000-7000-8000-000000000006'::uuid, false, '2020-01-01', NULL, NOW(), NOW()),
    ('01960029-0000-7000-8000-000000000007'::uuid, '01960021-0000-7000-8000-000000000007'::uuid, '01960025-0000-7000-8000-000000000007'::uuid, false, '2020-01-01', NULL, NOW(), NOW()),
    ('01960029-0000-7000-8000-000000000008'::uuid, '01960021-0000-7000-8000-000000000008'::uuid, '01960025-0000-7000-8000-000000000008'::uuid, false, '2020-01-01', NULL, NOW(), NOW()),
    ('01960029-0000-7000-8000-000000000009'::uuid, '01960021-0000-7000-8000-000000000009'::uuid, '01960025-0000-7000-8000-000000000009'::uuid, false, '2020-01-01', NULL, NOW(), NOW()),
    ('01960029-0000-7000-8000-00000000000a'::uuid, '01960021-0000-7000-8000-00000000000a'::uuid, '01960025-0000-7000-8000-00000000000a'::uuid, false, '2020-01-01', NULL, NOW(), NOW()),
    ('01960029-0000-7000-8000-00000000000b'::uuid, '01960021-0000-7000-8000-00000000000b'::uuid, '01960025-0000-7000-8000-00000000000b'::uuid, false, '2020-01-01', NULL, NOW(), NOW()),
    ('01960029-0000-7000-8000-00000000000c'::uuid, '01960021-0000-7000-8000-00000000000c'::uuid, '01960025-0000-7000-8000-00000000000c'::uuid, false, '2020-01-01', NULL, NOW(), NOW()),
    ('01960029-0000-7000-8000-00000000000d'::uuid, '01960021-0000-7000-8000-00000000000d'::uuid, '01960025-0000-7000-8000-00000000000d'::uuid, false, '2020-01-01', NULL, NOW(), NOW()),
    ('01960029-0000-7000-8000-00000000000e'::uuid, '01960021-0000-7000-8000-00000000000e'::uuid, '01960025-0000-7000-8000-00000000000e'::uuid, false, '2020-01-01', NULL, NOW(), NOW()),
    ('01960029-0000-7000-8000-00000000000f'::uuid, '01960021-0000-7000-8000-00000000000f'::uuid, '01960025-0000-7000-8000-00000000000f'::uuid, false, '2020-01-01', NULL, NOW(), NOW()),
    ('01960029-0000-7000-8000-000000000010'::uuid, '01960021-0000-7000-8000-000000000010'::uuid, '01960025-0000-7000-8000-000000000010'::uuid, false, '2020-01-01', NULL, NOW(), NOW()),
    ('01960029-0000-7000-8000-000000000011'::uuid, '01960021-0000-7000-8000-000000000011'::uuid, '01960025-0000-7000-8000-000000000011'::uuid, false, '2020-01-01', NULL, NOW(), NOW()),
    ('01960029-0000-7000-8000-000000000012'::uuid, '01960021-0000-7000-8000-000000000012'::uuid, '01960025-0000-7000-8000-000000000012'::uuid, false, '2020-01-01', NULL, NOW(), NOW()),
    ('01960029-0000-7000-8000-000000000013'::uuid, '01960021-0000-7000-8000-000000000013'::uuid, '01960025-0000-7000-8000-000000000013'::uuid, false, '2020-01-01', NULL, NOW(), NOW()),
    ('01960029-0000-7000-8000-000000000014'::uuid, '01960021-0000-7000-8000-000000000014'::uuid, '01960025-0000-7000-8000-000000000014'::uuid, false, '2020-01-01', NULL, NOW(), NOW())

ON CONFLICT (party_relationship_id) DO NOTHING;

-- =========================================================================
-- SECTION 8: PARTY_RELATIONSHIP_ROLE — 20 rows, PRIMARY_CONTACT per rel
-- =========================================================================

INSERT INTO party_relationship_role (party_relationship_id, role_type)
VALUES
    ('01960029-0000-7000-8000-000000000001'::uuid, 'PRIMARY_CONTACT'),
    ('01960029-0000-7000-8000-000000000002'::uuid, 'PRIMARY_CONTACT'),
    ('01960029-0000-7000-8000-000000000003'::uuid, 'PRIMARY_CONTACT'),
    ('01960029-0000-7000-8000-000000000004'::uuid, 'PRIMARY_CONTACT'),
    ('01960029-0000-7000-8000-000000000005'::uuid, 'PRIMARY_CONTACT'),
    ('01960029-0000-7000-8000-000000000006'::uuid, 'PRIMARY_CONTACT'),
    ('01960029-0000-7000-8000-000000000007'::uuid, 'PRIMARY_CONTACT'),
    ('01960029-0000-7000-8000-000000000008'::uuid, 'PRIMARY_CONTACT'),
    ('01960029-0000-7000-8000-000000000009'::uuid, 'PRIMARY_CONTACT'),
    ('01960029-0000-7000-8000-00000000000a'::uuid, 'PRIMARY_CONTACT'),
    ('01960029-0000-7000-8000-00000000000b'::uuid, 'PRIMARY_CONTACT'),
    ('01960029-0000-7000-8000-00000000000c'::uuid, 'PRIMARY_CONTACT'),
    ('01960029-0000-7000-8000-00000000000d'::uuid, 'PRIMARY_CONTACT'),
    ('01960029-0000-7000-8000-00000000000e'::uuid, 'PRIMARY_CONTACT'),
    ('01960029-0000-7000-8000-00000000000f'::uuid, 'PRIMARY_CONTACT'),
    ('01960029-0000-7000-8000-000000000010'::uuid, 'PRIMARY_CONTACT'),
    ('01960029-0000-7000-8000-000000000011'::uuid, 'PRIMARY_CONTACT'),
    ('01960029-0000-7000-8000-000000000012'::uuid, 'PRIMARY_CONTACT'),
    ('01960029-0000-7000-8000-000000000013'::uuid, 'PRIMARY_CONTACT'),
    ('01960029-0000-7000-8000-000000000014'::uuid, 'PRIMARY_CONTACT')

ON CONFLICT DO NOTHING;
