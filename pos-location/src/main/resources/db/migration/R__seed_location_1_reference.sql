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
-- coverage rules point at it. NC/SC codes matching each area's name, and disjoint
-- across these 25 areas -- though nothing in the schema enforces that, so an area
-- added later may overlap and a postal code may then resolve to more than one.
--
-- Resolved by name rather than by the literal ids above, and deliberately: the area
-- inserts are ON CONFLICT (tenant_id, name) DO NOTHING, so an area that already exists
-- under a different id -- created through POST /v1/service-areas before this migration
-- first ran -- keeps that id and never gets the hardcoded one. Naming a literal id here
-- would then violate the (tenant_id, service_area_id) foreign key, abort this repeatable
-- migration, and leave pos-location unable to start. SELECT makes that case a no-op.
-- The tenant_id predicate matters because Flyway runs as the owner, which bypasses RLS:
-- without it a same-named area in another tenant would match.
-- Mecklenburg County
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('28202'), ('28203'), ('28204'), ('28205'), ('28206'), ('28208'), ('28209'), ('28211')) AS v(code)
WHERE sa.name = 'Mecklenburg County'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- South Mecklenburg / Ballantyne / Pineville
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('28134'), ('28210'), ('28226'), ('28270'), ('28277')) AS v(code)
WHERE sa.name = 'South Mecklenburg / Ballantyne / Pineville'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Huntersville / Cornelius / Davidson corridor
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('28031'), ('28036'), ('28078')) AS v(code)
WHERE sa.name = 'Huntersville / Cornelius / Davidson corridor'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Mooresville / south Iredell
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('28115'), ('28117'), ('28166')) AS v(code)
WHERE sa.name = 'Mooresville / south Iredell'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Statesville / north Iredell
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('28625'), ('28677')) AS v(code)
WHERE sa.name = 'Statesville / north Iredell'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Lincoln County
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('28037'), ('28080'), ('28092'), ('28168')) AS v(code)
WHERE sa.name = 'Lincoln County'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Catawba County – extended market
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('28601'), ('28602'), ('28610'), ('28613'), ('28658')) AS v(code)
WHERE sa.name = 'Catawba County – extended market'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Concord / Kannapolis
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('28025'), ('28027'), ('28081'), ('28083')) AS v(code)
WHERE sa.name = 'Concord / Kannapolis'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Salisbury / Rowan County
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('28023'), ('28144'), ('28146'), ('28147')) AS v(code)
WHERE sa.name = 'Salisbury / Rowan County'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Albemarle / Stanly County
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('28001'), ('28009'), ('28128'), ('28137')) AS v(code)
WHERE sa.name = 'Albemarle / Stanly County'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Belmont / Mount Holly
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('28012'), ('28120')) AS v(code)
WHERE sa.name = 'Belmont / Mount Holly'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Gastonia / Bessemer City
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('28016'), ('28052'), ('28054'), ('28056')) AS v(code)
WHERE sa.name = 'Gastonia / Bessemer City'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Shelby / Cleveland County
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('28086'), ('28090'), ('28150'), ('28152')) AS v(code)
WHERE sa.name = 'Shelby / Cleveland County'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Matthews / Indian Trail
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('28079'), ('28104'), ('28105')) AS v(code)
WHERE sa.name = 'Matthews / Indian Trail'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Monroe / Wingate
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('28110'), ('28112'), ('28174')) AS v(code)
WHERE sa.name = 'Monroe / Wingate'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Anson County – rural extension
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('28007'), ('28091'), ('28133'), ('28170')) AS v(code)
WHERE sa.name = 'Anson County – rural extension'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Fort Mill / Tega Cay
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('29708'), ('29715')) AS v(code)
WHERE sa.name = 'Fort Mill / Tega Cay'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Rock Hill
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('29730'), ('29732')) AS v(code)
WHERE sa.name = 'Rock Hill'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- York / Clover
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('29710'), ('29745')) AS v(code)
WHERE sa.name = 'York / Clover'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Lancaster / Indian Land
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('29707'), ('29720'), ('29058')) AS v(code)
WHERE sa.name = 'Lancaster / Indian Land'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Chester County
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('29055'), ('29706'), ('29714')) AS v(code)
WHERE sa.name = 'Chester County'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Chesterfield County – extended
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('29709'), ('29718'), ('29728'), ('29741')) AS v(code)
WHERE sa.name = 'Chesterfield County – extended'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Alexander County
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('28636'), ('28678'), ('28681')) AS v(code)
WHERE sa.name = 'Alexander County'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- Unifour spillover
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('28630'), ('28638'), ('28645'), ('28655')) AS v(code)
WHERE sa.name = 'Unifour spillover'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;
-- far southern fringe beyond York/Lancaster
INSERT INTO service_area_postal_codes (service_area_id, country_code, postal_code)
SELECT sa.id, 'US', v.code
FROM service_areas sa
CROSS JOIN (VALUES ('29009'), ('29010'), ('29020'), ('29045')) AS v(code)
WHERE sa.name = 'far southern fringe beyond York/Lancaster'
  AND sa.tenant_id = public.app_current_tenant()
ON CONFLICT (tenant_id, country_code, service_area_id, postal_code) DO NOTHING;

-- Capabilities: the service_location_capabilities registry was retired by V5 (CAP-325, spec
-- D14.1). Bays and mobile units claim catalog operation codes, validated against the
-- ext_catalog_service replica; there is no location-owned capability vocabulary to seed.

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
