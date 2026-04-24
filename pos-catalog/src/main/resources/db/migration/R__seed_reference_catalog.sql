-- Repeatable seed for pos-catalog reference data (file 1 of 4): categories + subcategories.
-- Products: R__seed_reference_catalog_2_products.sql
-- Services: R__seed_reference_catalog_3_services.sql
-- Pricing:  R__seed_reference_catalog_4_pricing.sql
SET TIME ZONE 'UTC';

-- Remove legacy DEMO rows (products and services seeded by later files)
DELETE FROM product WHERE sku LIKE 'DEMO-PROD-%';
DELETE FROM service WHERE short_description LIKE 'DEMO-SVC-%';
DELETE FROM service WHERE short_description = 'SVC-OIL';
INSERT INTO category (id, name, created_at, updated_at) VALUES
  ('01960030-0000-7000-8000-000000000001', 'Tires & Wheels',                  NOW(), NOW()),
  ('01960030-0000-7000-8000-000000000002', 'Engine Parts',                    NOW(), NOW()),
  ('01960030-0000-7000-8000-000000000003', 'Brake System',                    NOW(), NOW()),
  ('01960030-0000-7000-8000-000000000004', 'Electrical System',               NOW(), NOW()),
  ('01960030-0000-7000-8000-000000000005', 'Drivetrain & Transmission',       NOW(), NOW()),
  ('01960030-0000-7000-8000-000000000006', 'Suspension & Steering',           NOW(), NOW()),
  ('01960030-0000-7000-8000-000000000007', 'Fluids & Chemicals',              NOW(), NOW()),
  ('01960030-0000-7000-8000-000000000008', 'Filters',                         NOW(), NOW()),
  ('01960030-0000-7000-8000-000000000009', 'Exhaust System',                  NOW(), NOW()),
  ('01960030-0000-7000-8000-00000000000a', 'HVAC & Climate',                  NOW(), NOW()),
  ('01960030-0000-7000-8000-00000000000b', 'Body & Lighting',                 NOW(), NOW()),
  ('01960030-0000-7000-8000-00000000000c', 'Heavy Equipment & Hydraulics',    NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, updated_at = NOW();

INSERT INTO subcategory (id, name, created_at, updated_at) VALUES
  ('01960031-0000-7000-8000-000000000001', 'Commercial Truck Tires',            NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000002', 'Light Truck & SUV Tires',           NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000003', 'Passenger Car Tires',               NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000004', 'OTR & Off-Highway Tires',           NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000005', 'Trailer Tires',                     NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000006', 'Forklift & Industrial Tires',       NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000007', 'Spark Plugs & Ignition',            NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000008', 'Belts & Tensioners',                NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000009', 'Hoses & Cooling',                   NOW(), NOW()),
  ('01960031-0000-7000-8000-00000000000a', 'Engine Gaskets & Seals',            NOW(), NOW()),
  ('01960031-0000-7000-8000-00000000000b', 'Brake Pads & Shoes',                NOW(), NOW()),
  ('01960031-0000-7000-8000-00000000000c', 'Brake Rotors & Drums',              NOW(), NOW()),
  ('01960031-0000-7000-8000-00000000000d', 'Brake Hardware & Calipers',         NOW(), NOW()),
  ('01960031-0000-7000-8000-00000000000e', 'Batteries',                         NOW(), NOW()),
  ('01960031-0000-7000-8000-00000000000f', 'Alternators & Starters',            NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000010', 'Fuses & Relays',                    NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000011', 'Wiper Blades',                      NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000012', 'ATF & Gear Oil',                    NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000013', 'CV Axles & Driveshafts',            NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000014', 'Clutch Components',                 NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000015', 'Shocks & Struts',                   NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000016', 'Ball Joints & Control Arms',        NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000017', 'Wheel Bearings & Hubs',             NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000018', 'Steering Components',               NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000019', 'Motor Oil',                         NOW(), NOW()),
  ('01960031-0000-7000-8000-00000000001a', 'Coolant & Antifreeze',              NOW(), NOW()),
  ('01960031-0000-7000-8000-00000000001b', 'Brake Fluid & Power Steering',      NOW(), NOW()),
  ('01960031-0000-7000-8000-00000000001c', 'Specialty Fluids & Additives',      NOW(), NOW()),
  ('01960031-0000-7000-8000-00000000001d', 'Oil Filters',                       NOW(), NOW()),
  ('01960031-0000-7000-8000-00000000001e', 'Air Filters',                       NOW(), NOW()),
  ('01960031-0000-7000-8000-00000000001f', 'Fuel Filters',                      NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000020', 'Cabin Air Filters',                 NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000021', 'Mufflers & Exhaust Pipes',          NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000022', 'Catalytic Converters & O2 Sensors', NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000023', 'A/C Compressors & Components',      NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000024', 'Heater Cores & Blower Motors',      NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000025', 'Lighting & Bulbs',                  NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000026', 'Mirrors & Body Hardware',           NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000027', 'Hydraulic Cylinders & Hoses',       NOW(), NOW()),
  ('01960031-0000-7000-8000-000000000028', 'Heavy Equipment Filters',           NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, updated_at = NOW();
