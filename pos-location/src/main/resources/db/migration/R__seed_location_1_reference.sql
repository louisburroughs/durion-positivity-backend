-- Tenant binding for the seed rows below (ADR-0062); transaction-local.
SELECT set_config('app.current_tenant', '01900000-0000-7000-8000-000000000001', true);

-- Repeatable seed migration for location reference data.
-- Source: durion/scripts/seed-generator/generated-seed-sql/003_locations_people.sql
-- Notes:
-- - Intentionally limited to tables owned by pos-location Flyway schema.
-- - Kept idempotent via ON CONFLICT clauses.
SET TIME ZONE 'UTC';

-- Service areas
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('42fdb342-ffba-9670-584b-6030b450a178'::uuid, 'Mecklenburg County', 'charlotte-core service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('633291b1-5487-1bfe-d183-182be094d44f'::uuid, 'South Mecklenburg / Ballantyne / Pineville', 'south-charlotte service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('df412fda-4a6a-f06d-4537-7b6dc351be47'::uuid, 'Huntersville / Cornelius / Davidson corridor', 'north-charlotte service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('bd77c0c8-fd05-72d3-22f4-ff324d34d4a1'::uuid, 'Mooresville / south Iredell', 'iredell-south service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('1dd42dc9-d985-499e-aad6-fd6f84ee102b'::uuid, 'Statesville / north Iredell', 'iredell-north service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('ca3fb3cd-9c02-2fb2-4b5d-a57d47b2657c'::uuid, 'Lincoln County', 'lincoln service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('d425f491-e26f-6642-8a63-144e794f2812'::uuid, 'Catawba County – extended market', 'catawba service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('1b539e3a-f9b9-1df3-d9a0-eaab2de0eeea'::uuid, 'Concord / Kannapolis', 'cabarrus service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('26883292-e8f6-cf14-6d22-d3ab89b5b501'::uuid, 'Salisbury / Rowan County', 'rowan service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('4a1e2b17-d7dc-ae44-a33a-59d473d40b6a'::uuid, 'Albemarle / Stanly County', 'stanly service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('b0ac04c1-3702-dbc6-532a-c0c471de6bee'::uuid, 'Belmont / Mount Holly', 'gaston-east service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('36da910b-bdfe-c2e0-c846-ecae077eb421'::uuid, 'Gastonia / Bessemer City', 'gaston-west service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('012081df-7ca6-9f4a-4525-2efd191449ec'::uuid, 'Shelby / Cleveland County', 'cleveland service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('0ff5b0cf-3648-c0ab-aa39-92a158ac8340'::uuid, 'Matthews / Indian Trail', 'union-west service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('a1578c6b-2b13-30d4-8718-0b5bf6314197'::uuid, 'Monroe / Wingate', 'union-east service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('b5ad60eb-510b-5e7f-7c66-f07277c15c40'::uuid, 'Anson County – rural extension', 'anson service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('f955f6b0-5536-6472-f850-0d9f1fd708b1'::uuid, 'Fort Mill / Tega Cay', 'york-north service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('ec5184f6-41c2-aee0-04a0-056c3f6fa04e'::uuid, 'Rock Hill', 'york-central service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('4ace8ea6-d291-2894-f801-b9e448bfdd9e'::uuid, 'York / Clover', 'york-south service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('c3770ecc-7df1-209c-4568-ac0f115386cb'::uuid, 'Lancaster / Indian Land', 'lancaster service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('03bf9420-dbb7-6c5d-5efb-76fda132b1bd'::uuid, 'Chester County', 'chester-sc service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('d4a5a733-333e-a982-22f3-a79895f501c1'::uuid, 'Chesterfield County – extended', 'chesterfield service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('f72ac1da-a066-f099-f565-255b911b26c3'::uuid, 'Alexander County', 'alexander service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('73c2f6a3-b536-a4b9-dd75-8278d4c2f002'::uuid, 'Unifour spillover', 'burke-caldwell service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
INSERT INTO service_areas (id, name, description, active, created_at, updated_at)
VALUES ('fe5bd0c2-6c4b-9929-0f3f-00099be619d5'::uuid, 'far southern fringe beyond York/Lancaster', 'upper-piedmont-sc service area', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;

-- Service area postal codes. The mobile-unit eligibility query resolves an address
-- through these rows and nothing else -- MobileUnitCoverageRuleRepository inner-joins
-- serviceArea.postalCodes, so an area without them covers no address however many
-- coverage rules point at it. NC/SC codes matching each area's name; disjoint across
-- areas, so an address resolves to one area and the unit priority alone orders the result.
-- Mecklenburg County
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('42fdb342-ffba-9670-584b-6030b450a178'::uuid, 'US', '28202'),
       ('42fdb342-ffba-9670-584b-6030b450a178'::uuid, 'US', '28203'),
       ('42fdb342-ffba-9670-584b-6030b450a178'::uuid, 'US', '28204'),
       ('42fdb342-ffba-9670-584b-6030b450a178'::uuid, 'US', '28205'),
       ('42fdb342-ffba-9670-584b-6030b450a178'::uuid, 'US', '28206'),
       ('42fdb342-ffba-9670-584b-6030b450a178'::uuid, 'US', '28208'),
       ('42fdb342-ffba-9670-584b-6030b450a178'::uuid, 'US', '28209'),
       ('42fdb342-ffba-9670-584b-6030b450a178'::uuid, 'US', '28211')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- South Mecklenburg / Ballantyne / Pineville
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('633291b1-5487-1bfe-d183-182be094d44f'::uuid, 'US', '28134'),
       ('633291b1-5487-1bfe-d183-182be094d44f'::uuid, 'US', '28210'),
       ('633291b1-5487-1bfe-d183-182be094d44f'::uuid, 'US', '28226'),
       ('633291b1-5487-1bfe-d183-182be094d44f'::uuid, 'US', '28270'),
       ('633291b1-5487-1bfe-d183-182be094d44f'::uuid, 'US', '28277')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Huntersville / Cornelius / Davidson corridor
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('df412fda-4a6a-f06d-4537-7b6dc351be47'::uuid, 'US', '28031'),
       ('df412fda-4a6a-f06d-4537-7b6dc351be47'::uuid, 'US', '28036'),
       ('df412fda-4a6a-f06d-4537-7b6dc351be47'::uuid, 'US', '28078')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Mooresville / south Iredell
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('bd77c0c8-fd05-72d3-22f4-ff324d34d4a1'::uuid, 'US', '28115'),
       ('bd77c0c8-fd05-72d3-22f4-ff324d34d4a1'::uuid, 'US', '28117')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Statesville / north Iredell
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('1dd42dc9-d985-499e-aad6-fd6f84ee102b'::uuid, 'US', '28166'),
       ('1dd42dc9-d985-499e-aad6-fd6f84ee102b'::uuid, 'US', '28625'),
       ('1dd42dc9-d985-499e-aad6-fd6f84ee102b'::uuid, 'US', '28677')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Lincoln County
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('ca3fb3cd-9c02-2fb2-4b5d-a57d47b2657c'::uuid, 'US', '28037'),
       ('ca3fb3cd-9c02-2fb2-4b5d-a57d47b2657c'::uuid, 'US', '28080'),
       ('ca3fb3cd-9c02-2fb2-4b5d-a57d47b2657c'::uuid, 'US', '28092'),
       ('ca3fb3cd-9c02-2fb2-4b5d-a57d47b2657c'::uuid, 'US', '28168')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Catawba County – extended market
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('d425f491-e26f-6642-8a63-144e794f2812'::uuid, 'US', '28601'),
       ('d425f491-e26f-6642-8a63-144e794f2812'::uuid, 'US', '28602'),
       ('d425f491-e26f-6642-8a63-144e794f2812'::uuid, 'US', '28610'),
       ('d425f491-e26f-6642-8a63-144e794f2812'::uuid, 'US', '28613'),
       ('d425f491-e26f-6642-8a63-144e794f2812'::uuid, 'US', '28658')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Concord / Kannapolis
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('1b539e3a-f9b9-1df3-d9a0-eaab2de0eeea'::uuid, 'US', '28025'),
       ('1b539e3a-f9b9-1df3-d9a0-eaab2de0eeea'::uuid, 'US', '28027'),
       ('1b539e3a-f9b9-1df3-d9a0-eaab2de0eeea'::uuid, 'US', '28081'),
       ('1b539e3a-f9b9-1df3-d9a0-eaab2de0eeea'::uuid, 'US', '28083')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Salisbury / Rowan County
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('26883292-e8f6-cf14-6d22-d3ab89b5b501'::uuid, 'US', '28023'),
       ('26883292-e8f6-cf14-6d22-d3ab89b5b501'::uuid, 'US', '28144'),
       ('26883292-e8f6-cf14-6d22-d3ab89b5b501'::uuid, 'US', '28146'),
       ('26883292-e8f6-cf14-6d22-d3ab89b5b501'::uuid, 'US', '28147')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Albemarle / Stanly County
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('4a1e2b17-d7dc-ae44-a33a-59d473d40b6a'::uuid, 'US', '28001'),
       ('4a1e2b17-d7dc-ae44-a33a-59d473d40b6a'::uuid, 'US', '28009'),
       ('4a1e2b17-d7dc-ae44-a33a-59d473d40b6a'::uuid, 'US', '28128'),
       ('4a1e2b17-d7dc-ae44-a33a-59d473d40b6a'::uuid, 'US', '28137')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Belmont / Mount Holly
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('b0ac04c1-3702-dbc6-532a-c0c471de6bee'::uuid, 'US', '28012'),
       ('b0ac04c1-3702-dbc6-532a-c0c471de6bee'::uuid, 'US', '28120')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Gastonia / Bessemer City
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('36da910b-bdfe-c2e0-c846-ecae077eb421'::uuid, 'US', '28016'),
       ('36da910b-bdfe-c2e0-c846-ecae077eb421'::uuid, 'US', '28052'),
       ('36da910b-bdfe-c2e0-c846-ecae077eb421'::uuid, 'US', '28054'),
       ('36da910b-bdfe-c2e0-c846-ecae077eb421'::uuid, 'US', '28056')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Shelby / Cleveland County
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('012081df-7ca6-9f4a-4525-2efd191449ec'::uuid, 'US', '28086'),
       ('012081df-7ca6-9f4a-4525-2efd191449ec'::uuid, 'US', '28090'),
       ('012081df-7ca6-9f4a-4525-2efd191449ec'::uuid, 'US', '28150'),
       ('012081df-7ca6-9f4a-4525-2efd191449ec'::uuid, 'US', '28152')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Matthews / Indian Trail
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('0ff5b0cf-3648-c0ab-aa39-92a158ac8340'::uuid, 'US', '28079'),
       ('0ff5b0cf-3648-c0ab-aa39-92a158ac8340'::uuid, 'US', '28104'),
       ('0ff5b0cf-3648-c0ab-aa39-92a158ac8340'::uuid, 'US', '28105')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Monroe / Wingate
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('a1578c6b-2b13-30d4-8718-0b5bf6314197'::uuid, 'US', '28110'),
       ('a1578c6b-2b13-30d4-8718-0b5bf6314197'::uuid, 'US', '28112'),
       ('a1578c6b-2b13-30d4-8718-0b5bf6314197'::uuid, 'US', '28174')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Anson County – rural extension
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('b5ad60eb-510b-5e7f-7c66-f07277c15c40'::uuid, 'US', '28007'),
       ('b5ad60eb-510b-5e7f-7c66-f07277c15c40'::uuid, 'US', '28091'),
       ('b5ad60eb-510b-5e7f-7c66-f07277c15c40'::uuid, 'US', '28133'),
       ('b5ad60eb-510b-5e7f-7c66-f07277c15c40'::uuid, 'US', '28170')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Fort Mill / Tega Cay
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('f955f6b0-5536-6472-f850-0d9f1fd708b1'::uuid, 'US', '29708'),
       ('f955f6b0-5536-6472-f850-0d9f1fd708b1'::uuid, 'US', '29715'),
       ('f955f6b0-5536-6472-f850-0d9f1fd708b1'::uuid, 'US', '29716')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Rock Hill
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('ec5184f6-41c2-aee0-04a0-056c3f6fa04e'::uuid, 'US', '29730'),
       ('ec5184f6-41c2-aee0-04a0-056c3f6fa04e'::uuid, 'US', '29732'),
       ('ec5184f6-41c2-aee0-04a0-056c3f6fa04e'::uuid, 'US', '29733')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- York / Clover
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('4ace8ea6-d291-2894-f801-b9e448bfdd9e'::uuid, 'US', '29710'),
       ('4ace8ea6-d291-2894-f801-b9e448bfdd9e'::uuid, 'US', '29745')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Lancaster / Indian Land
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('c3770ecc-7df1-209c-4568-ac0f115386cb'::uuid, 'US', '29707'),
       ('c3770ecc-7df1-209c-4568-ac0f115386cb'::uuid, 'US', '29720')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Chester County
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('03bf9420-dbb7-6c5d-5efb-76fda132b1bd'::uuid, 'US', '29055'),
       ('03bf9420-dbb7-6c5d-5efb-76fda132b1bd'::uuid, 'US', '29706'),
       ('03bf9420-dbb7-6c5d-5efb-76fda132b1bd'::uuid, 'US', '29714'),
       ('03bf9420-dbb7-6c5d-5efb-76fda132b1bd'::uuid, 'US', '29724')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Chesterfield County – extended
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('d4a5a733-333e-a982-22f3-a79895f501c1'::uuid, 'US', '29709'),
       ('d4a5a733-333e-a982-22f3-a79895f501c1'::uuid, 'US', '29718'),
       ('d4a5a733-333e-a982-22f3-a79895f501c1'::uuid, 'US', '29728'),
       ('d4a5a733-333e-a982-22f3-a79895f501c1'::uuid, 'US', '29741')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Alexander County
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('f72ac1da-a066-f099-f565-255b911b26c3'::uuid, 'US', '28636'),
       ('f72ac1da-a066-f099-f565-255b911b26c3'::uuid, 'US', '28678'),
       ('f72ac1da-a066-f099-f565-255b911b26c3'::uuid, 'US', '28681')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Unifour spillover
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('73c2f6a3-b536-a4b9-dd75-8278d4c2f002'::uuid, 'US', '28630'),
       ('73c2f6a3-b536-a4b9-dd75-8278d4c2f002'::uuid, 'US', '28638'),
       ('73c2f6a3-b536-a4b9-dd75-8278d4c2f002'::uuid, 'US', '28645'),
       ('73c2f6a3-b536-a4b9-dd75-8278d4c2f002'::uuid, 'US', '28655')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- far southern fringe beyond York/Lancaster
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
VALUES
       ('fe5bd0c2-6c4b-9929-0f3f-00099be619d5'::uuid, 'US', '29010'),
       ('fe5bd0c2-6c4b-9929-0f3f-00099be619d5'::uuid, 'US', '29020'),
       ('fe5bd0c2-6c4b-9929-0f3f-00099be619d5'::uuid, 'US', '29045'),
       ('fe5bd0c2-6c4b-9929-0f3f-00099be619d5'::uuid, 'US', '29058')
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;

-- Capabilities
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('7df8046a-3862-e521-e888-e509c7c5483d'::uuid, 'ALIGNMENT', 'Wheel Alignment', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('a8892380-c066-9e4d-6982-5e918c78514d'::uuid, 'OIL_CHANGE', 'Oil Change', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('6cff0034-9829-8b80-390b-8e4e450af6a7'::uuid, 'BRAKE_SERVICE', 'Brake Inspection & Repair', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('4060148a-347e-d792-efe9-9039d14e6eb8'::uuid, 'TIRE_SERVICE', 'Tire Mounting, Balancing & Repair', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('a9efb56b-f6ed-bfc5-ed83-ba4d18af4004'::uuid, 'SUSPENSION', 'Suspension & Steering Repair', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('98eba49d-cadd-5a65-3d13-23f7a5680ba1'::uuid, 'ENGINE_DIAGNOSTICS', 'Engine Diagnostics', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('ad052aad-ed59-3c73-4c15-d2ed99e61a55'::uuid, 'TRANSMISSION', 'Transmission Service & Repair', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('3a1664e4-ca49-6cc7-d3af-05eb51df69b7'::uuid, 'ELECTRICAL', 'Electrical System Diagnostics & Repair', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('ffff4f59-d51c-1836-687e-3d1d5cb3920e'::uuid, 'COOLING_SYSTEM', 'Cooling System Service', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('62275182-7bba-64d8-1f34-e4613697b1b4'::uuid, 'FUEL_SYSTEM', 'Fuel System Service & Repair', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('65f6cb13-b7df-108e-e0d4-ca099e0a5a94'::uuid, 'EXHAUST', 'Exhaust & Emissions Repair', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('5bad5d98-25fd-95c2-243e-5428a1d259bf'::uuid, 'AC_SERVICE', 'HVAC / A/C Service', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('d8ecc105-5b4e-ec9e-3149-b17eda00c60d'::uuid, 'DOT_INSPECTION', 'DOT Inspection', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('92890323-55d9-c743-1b8c-97e97fe3a5ee'::uuid, 'PM_SERVICE', 'Preventive Maintenance Service', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('b68be185-41af-51d9-55c1-c83d8ab3f0bb'::uuid, 'HYDRAULICS', 'Hydraulic System Repair', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('b00d8687-e7a5-4759-48db-65f5ff07f5d9'::uuid, 'DRIVELINE', 'Driveline & Differential Service', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('438caf70-bb2f-bbb9-9339-47525996b449'::uuid, 'CLUTCH', 'Clutch Repair & Replacement', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('2db24f4e-042c-6e71-63bb-e7f238c3ab5d'::uuid, 'BATTERY', 'Battery Testing & Replacement', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('9e9d62f9-87b0-d003-4468-781421418e43'::uuid, 'TRAILER_REPAIR', 'Trailer Repair & Maintenance', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;
INSERT INTO service_location_capabilities (id, code, name, active, created_at, updated_at)
VALUES ('498845ee-8f3a-e131-3bba-e1f1a0b53091'::uuid, 'ROADSIDE_SERVICE', 'Emergency Roadside Service', TRUE, NOW(), NOW())
ON CONFLICT (tenant_id, code) DO NOTHING;

-- Travel buffer policies
INSERT INTO travel_buffer_policies (id, name, buffer_type, buffer_value, created_at, updated_at)
VALUES ('407617dd-f4f6-fec6-50e4-4221bdf102c8'::uuid, 'Default 15m', 'MINUTES', 15, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;

INSERT INTO travel_buffer_policies (id, name, buffer_type, buffer_value, created_at, updated_at)
VALUES ('01960002-0000-7000-8000-000000000001'::uuid, 'Standard Suburban', 'MINUTES', 20, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;

INSERT INTO travel_buffer_policies (id, name, buffer_type, buffer_value, created_at, updated_at)
VALUES ('01960002-0000-7000-8000-000000000002'::uuid, 'Extended Rural', 'MINUTES', 35, NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;

-- Location types
INSERT INTO location_type (id, name, description, created_at, updated_at)
VALUES ('01960001-0000-7000-8000-000000000001'::uuid, 'Service Center', 'Customer-facing vehicle service location', NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;

INSERT INTO location_type (id, name, description, created_at, updated_at)
VALUES ('01960001-0000-7000-8000-000000000002'::uuid, 'Warehouse', 'Parts and inventory storage facility', NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;

INSERT INTO location_type (id, name, description, created_at, updated_at)
VALUES ('01960001-0000-7000-8000-000000000003'::uuid, 'Distribution Center', 'Regional distribution hub', NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;

INSERT INTO location_type (id, name, description, created_at, updated_at)
VALUES ('01960001-0000-7000-8000-000000000004'::uuid, 'Corporate HQ', 'Corporate headquarters location', NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;

INSERT INTO location_type (id, name, description, created_at, updated_at)
VALUES ('01960001-0000-7000-8000-000000000005'::uuid, 'Mobile Unit Base', 'Base of operations for mobile service units', NOW(), NOW())
ON CONFLICT (tenant_id, name) DO NOTHING;
