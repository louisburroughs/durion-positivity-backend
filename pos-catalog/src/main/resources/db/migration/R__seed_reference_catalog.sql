-- Repeatable seed migration for catalog reference data.
-- Source: durion/scripts/seed-generator/generated-seed-sql/004_catalog_pricing_inventory.sql
-- Notes:
-- - Includes only pos-catalog-owned tables (product, service).
-- - product_base_price is owned by pos-price and intentionally excluded.
SET TIME ZONE 'UTC';

-- Catalog products
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('c0ac778b-b3e4-737b-74c3-4afc82f932bd'::uuid, 'OIL-5W30-5QT', 'Synthetic Oil 5W30', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('7a955d5e-65a9-e43d-0cf9-6bcf92ffd9b0'::uuid, 'DEMO-PROD-0001', 'Handcrafted Wooden Sausages', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('a977409b-a4c0-7482-4323-af16308658c5'::uuid, 'DEMO-PROD-0002', 'Ergonomic Bronze Ball', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('6b0b67fb-f0b0-3ec1-9987-0f7294182342'::uuid, 'DEMO-PROD-0003', 'Modern Plastic Bacon', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('21aa8f2a-f1e8-af05-8c44-4d9033c5c785'::uuid, 'DEMO-PROD-0004', 'Sleek Ceramic Chair', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('9a1865f5-8e98-8ea6-0626-0153cb45b903'::uuid, 'DEMO-PROD-0005', 'Generic Granite Gloves', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('28eaa040-091e-7879-1e7f-f496ccc76f8e'::uuid, 'DEMO-PROD-0006', 'Modern Bronze Chips', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('b0c5a37e-35cf-6dfe-026a-872959e3777d'::uuid, 'DEMO-PROD-0007', 'Incredible Rubber Chair', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('8a656668-c26f-7e35-5657-38a4f4bca3bd'::uuid, 'DEMO-PROD-0008', 'Luxurious Aluminum Pants', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('966dbc92-8e70-a88d-aa00-cb3ae1824575'::uuid, 'DEMO-PROD-0009', 'Bespoke Wooden Tuna', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('5740b0df-c91a-4931-afb2-4d42b87e51cb'::uuid, 'DEMO-PROD-0010', 'Generic Bamboo Salad', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('7a4dfa1a-1918-2f0c-8d44-03bc59864570'::uuid, 'DEMO-PROD-0011', 'Elegant Granite Bacon', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('87490506-4e24-e569-02be-a5f8916c9590'::uuid, 'DEMO-PROD-0012', 'Fresh Metal Chips', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('a82a758f-5044-280e-32e3-96b428044c0a'::uuid, 'DEMO-PROD-0013', 'Licensed Bronze Tuna', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('6d82c158-2c85-ccb8-9fae-0f5b80e5d9fb'::uuid, 'DEMO-PROD-0014', 'Tasty Steel Pants', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('dcc59036-66a3-4ed0-f437-14ef2a86ed79'::uuid, 'DEMO-PROD-0015', 'Electronic Bronze Ball', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('a1654e4c-e24a-4db6-9d36-557b7c1d6f0e'::uuid, 'DEMO-PROD-0016', 'Handcrafted Concrete Shoes', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('70b69394-2c66-28e2-453c-9b63345e0bec'::uuid, 'DEMO-PROD-0017', 'Frozen Marble Car', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('b51db47a-a160-0f84-2930-16db480ef78f'::uuid, 'DEMO-PROD-0018', 'Electronic Wooden Shirt', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('3e6ed25c-1946-c54c-b187-a0373fb5fa6a'::uuid, 'DEMO-PROD-0019', 'Awesome Silk Salad', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('2abc379e-2c4a-7042-85a6-2d63bc997974'::uuid, 'DEMO-PROD-0020', 'Refined Bamboo Computer', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('ed69b313-d916-517d-8729-016778e73b38'::uuid, 'DEMO-PROD-0021', 'Small Metal Chips', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('a3ceae91-4877-745d-02f6-7f660d75932a'::uuid, 'DEMO-PROD-0022', 'Generic Concrete Sausages', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('2418c283-a559-0077-a8a5-f19562eaef4b'::uuid, 'DEMO-PROD-0023', 'Small Granite Bike', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('7ba365fa-4b02-e2ab-64fb-6ab1b5c3ee8b'::uuid, 'DEMO-PROD-0024', 'Refined Marble Shirt', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('a4fb294a-73ad-0047-a313-2477dfdb277f'::uuid, 'DEMO-PROD-0025', 'Licensed Gold Bacon', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('22c5e2f7-cb8c-e859-02ba-7859e8faa3c3'::uuid, 'DEMO-PROD-0026', 'Awesome Metal Chips', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('a7825f5a-c082-d73c-abd8-aac3bed07f5b'::uuid, 'DEMO-PROD-0027', 'Soft Ceramic Fish', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('595f62a3-bb74-39d9-ebf9-6bdedde11748'::uuid, 'DEMO-PROD-0028', 'Fresh Bamboo Chicken', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('e1e3b74a-6383-30be-df95-b60c0ceabc29'::uuid, 'DEMO-PROD-0029', 'Tasty Silk Pizza', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('ab2f1242-048d-d297-4eff-d1da2ec2e848'::uuid, 'DEMO-PROD-0030', 'Rustic Bronze Table', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('eb2cb91a-0dcb-bc39-918a-d2da44d2f045'::uuid, 'DEMO-PROD-0031', 'Rustic Steel Chips', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('772ca998-3abc-12d1-9da3-3cbf6bfec642'::uuid, 'DEMO-PROD-0032', 'Fresh Gold Shoes', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('74374346-083d-726b-ce9a-ece72e4e4b7b'::uuid, 'DEMO-PROD-0033', 'Awesome Granite Gloves', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('bf7a77b1-ec35-6057-8322-5c43af52894e'::uuid, 'DEMO-PROD-0034', 'Elegant Cotton Towels', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('1b828782-7e3d-efb5-3b84-23c6ece04c6a'::uuid, 'DEMO-PROD-0035', 'Licensed Plastic Computer', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('63ed2965-d2cb-203e-0b6d-8617d9f063e1'::uuid, 'DEMO-PROD-0036', 'Tasty Ceramic Hat', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('016c609d-6c41-3c50-4061-e6de275fda99'::uuid, 'DEMO-PROD-0037', 'Frozen Aluminum Pants', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('3e98608a-3c48-0090-3bb8-1d9bb536c52e'::uuid, 'DEMO-PROD-0038', 'Bespoke Concrete Table', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('914f121a-d7fc-fdb9-7a9a-26096e225171'::uuid, 'DEMO-PROD-0039', 'Elegant Granite Tuna', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('98dca53d-4a39-e6df-2250-59aefc680c2d'::uuid, 'DEMO-PROD-0040', 'Practical Rubber Cheese', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('a1244922-2295-01a5-7cfe-d559e0ff359a'::uuid, 'DEMO-PROD-0041', 'Gorgeous Metal Pizza', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('b72a2147-6055-aee7-972b-1c10a60efbea'::uuid, 'DEMO-PROD-0042', 'Electronic Silk Chips', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('19b8eeb0-47fe-f8f3-6188-4788595ebe44'::uuid, 'DEMO-PROD-0043', 'Bespoke Marble Salad', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('49bbeea0-dba7-f033-dbba-7c6ae9c02e66'::uuid, 'DEMO-PROD-0044', 'Intelligent Ceramic Pizza', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('b32ac53d-7e2f-d06d-2b43-a21e67764113'::uuid, 'DEMO-PROD-0045', 'Practical Cotton Towels', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('e2df76df-a851-def9-25ab-2b38ded83164'::uuid, 'DEMO-PROD-0046', 'Gorgeous Bamboo Towels', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('078674a9-038a-7b24-5c44-391d174188c5'::uuid, 'DEMO-PROD-0047', 'Fresh Metal Shoes', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('9a43e976-78e2-c741-01b7-948dd2084a2a'::uuid, 'DEMO-PROD-0048', 'Licensed Ceramic Bike', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('8cb46a70-ac93-d389-43ed-544e5f87e64e'::uuid, 'DEMO-PROD-0049', 'Soft Metal Computer', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('b81fd917-48cc-ce9f-f150-924938c4346f'::uuid, 'DEMO-PROD-0050', 'Recycled Steel Table', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('a0be8b25-fba4-c0b9-55e9-354ceff72e91'::uuid, 'DEMO-PROD-0051', 'Oriental Bamboo Car', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('314a6a3c-3795-1ba1-922a-c7f80a3f5cc1'::uuid, 'DEMO-PROD-0052', 'Modern Aluminum Bike', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('a399fec8-55e7-67fc-6ea7-0f4c57d1d1fd'::uuid, 'DEMO-PROD-0053', 'Awesome Bronze Mouse', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('bd8c9c77-0a5b-790a-b249-eed97d39b72e'::uuid, 'DEMO-PROD-0054', 'Oriental Ceramic Sausages', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('1fbd8efc-eb0f-769b-4f9e-bc828d0adf14'::uuid, 'DEMO-PROD-0055', 'Generic Rubber Pizza', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('875afd19-b126-9ca2-9d64-6070f2ee9eb3'::uuid, 'DEMO-PROD-0056', 'Oriental Marble Bike', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('85a4c856-a91b-050e-6efe-4cb2409d1503'::uuid, 'DEMO-PROD-0057', 'Frozen Ceramic Tuna', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('0be1bcaf-eb1e-e4d6-290a-4ac0d91b2b2b'::uuid, 'DEMO-PROD-0058', 'Soft Metal Shoes', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('e3806699-4ed2-4a3f-0d7e-0b2198e0217e'::uuid, 'DEMO-PROD-0059', 'Luxurious Granite Chair', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('2006ac44-2893-873a-a87b-8d024ed61e65'::uuid, 'DEMO-PROD-0060', 'Frozen Aluminum Pizza', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('a6888980-2279-35ea-58fc-5f42669c2c56'::uuid, 'DEMO-PROD-0061', 'Tasty Wooden Table', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('fca20d4f-5e9c-7c95-b468-e6c4591e0895'::uuid, 'DEMO-PROD-0062', 'Awesome Steel Gloves', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('d2544d1a-073a-7b35-6b14-ae5dd2a967e4'::uuid, 'DEMO-PROD-0063', 'Unbranded Silk Chips', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('8df5997b-f7a6-2293-356d-d16bd0d972fb'::uuid, 'DEMO-PROD-0064', 'Sleek Concrete Chair', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('a236cda9-f23f-3f84-dcb3-42ee8cc1be75'::uuid, 'DEMO-PROD-0065', 'Tasty Plastic Mouse', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('c863b7e5-d534-e4d7-87af-d27fce0811b4'::uuid, 'DEMO-PROD-0066', 'Modern Wooden Car', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('ee0bc37a-7be3-31ad-2e68-b38da1ad101f'::uuid, 'DEMO-PROD-0067', 'Small Rubber Salad', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('110e2a92-8337-7683-6ab1-c684ebdd443a'::uuid, 'DEMO-PROD-0068', 'Gorgeous Concrete Shoes', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('d25f60b7-6003-f2d9-05ca-28ec8075d36f'::uuid, 'DEMO-PROD-0069', 'Small Steel Keyboard', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('18dab8fb-27dd-a70c-b266-9435bc2472e4'::uuid, 'DEMO-PROD-0070', 'Rustic Metal Salad', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('87d85389-2609-9ca2-fd40-58eef8799062'::uuid, 'DEMO-PROD-0071', 'Soft Cotton Fish', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('eed2edd4-d810-b80e-e0e7-5dd84c98e86d'::uuid, 'DEMO-PROD-0072', 'Luxurious Aluminum Hat', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('0ed490f3-8770-4cab-5101-9a01ae603698'::uuid, 'DEMO-PROD-0073', 'Frozen Marble Bacon', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('f6af4aa1-17d4-b4b2-a04e-af58068b2e00'::uuid, 'DEMO-PROD-0074', 'Sleek Cotton Car', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('2c7aeb7b-9ce0-2f8c-95ef-5f3f77a2fc05'::uuid, 'DEMO-PROD-0075', 'Refined Ceramic Pants', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('e05bd2e7-b0a4-040d-7a79-5977d7649141'::uuid, 'DEMO-PROD-0076', 'Bespoke Granite Keyboard', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('8b403d49-9cbe-73dd-f98a-05d942d33e66'::uuid, 'DEMO-PROD-0077', 'Recycled Wooden Keyboard', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('6c7c5e2e-e56a-5c99-71fb-33c9c89d738e'::uuid, 'DEMO-PROD-0078', 'Rustic Concrete Gloves', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('4d7070f2-8023-cb99-a7dc-f5b2756b6b87'::uuid, 'DEMO-PROD-0079', 'Awesome Wooden Soap', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('605e4102-610e-ff5a-5386-70c23df83a96'::uuid, 'DEMO-PROD-0080', 'Handmade Bronze Car', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('794f41a4-4d69-5bc1-32ce-6f988a0e327a'::uuid, 'DEMO-PROD-0081', 'Licensed Plastic Pizza', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('caf8fc96-b15e-ca24-258e-c3b763a67041'::uuid, 'DEMO-PROD-0082', 'Tasty Rubber Mouse', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('73f86c79-6646-c390-9436-cf74ed3a4448'::uuid, 'DEMO-PROD-0083', 'Handmade Ceramic Computer', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('cb31738e-8f22-daf4-920a-90c8b5671c5c'::uuid, 'DEMO-PROD-0084', 'Awesome Bamboo Ball', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('34a68195-058c-6968-a171-cadd369d172a'::uuid, 'DEMO-PROD-0085', 'Small Plastic Hat', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('5eae0c32-c2bb-e5be-a599-30e94e15a84d'::uuid, 'DEMO-PROD-0086', 'Intelligent Granite Chair', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('3b0ae7f4-ed58-e2e6-ed97-248b687500df'::uuid, 'DEMO-PROD-0087', 'Handcrafted Metal Pizza', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('a2c47e38-763c-0bb2-ff6b-dd4598b8bded'::uuid, 'DEMO-PROD-0088', 'Handcrafted Metal Keyboard', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('559a8ade-a06d-fb39-d5b5-7e6e6f03cf38'::uuid, 'DEMO-PROD-0089', 'Oriental Bronze Ball', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('bc6adbde-5f2e-ab38-c9c6-ab45b600f21e'::uuid, 'DEMO-PROD-0090', 'Awesome Marble Towels', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('1df53108-009b-6e1d-2272-033fe73768e3'::uuid, 'DEMO-PROD-0091', 'Handcrafted Metal Gloves', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('251880de-9481-4919-0df2-df5c7d8a4dfb'::uuid, 'DEMO-PROD-0092', 'Tasty Cotton Tuna', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('88b5814b-66a1-aabc-8157-0ef6a3918c6e'::uuid, 'DEMO-PROD-0093', 'Fantastic Bamboo Bike', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('87af0005-1303-2afc-9e5e-c36c7797d8d3'::uuid, 'DEMO-PROD-0094', 'Electronic Plastic Ball', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('0c09d0fd-2f5a-dbc2-45ce-f1c974ddca1c'::uuid, 'DEMO-PROD-0095', 'Sleek Aluminum Shoes', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('f5b7c594-8151-99de-7db7-f2c0e93a5036'::uuid, 'DEMO-PROD-0096', 'Elegant Plastic Pizza', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('043f4ace-f91c-0ab3-d976-cfd0b1a18d55'::uuid, 'DEMO-PROD-0097', 'Recycled Silk Chicken', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('c3d389f4-1e7d-dba8-68f6-2c45a140d202'::uuid, 'DEMO-PROD-0098', 'Refined Silk Tuna', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('8396f5d2-69f1-d083-421d-ada80b54109a'::uuid, 'DEMO-PROD-0099', 'Handcrafted Rubber Computer', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();
INSERT INTO product (id, sku, name, status, created_at, updated_at)
VALUES ('164fa305-1f1f-b279-624a-e3c56ef69998'::uuid, 'DEMO-PROD-0100', 'Small Gold Shirt', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, updated_at = NOW();

-- Catalog services
INSERT INTO service (id, name, short_description, long_description)
VALUES ('1bbf833f-65be-79ef-24f8-01695caa1258'::uuid, 'Oil Change Service', 'SVC-OIL', 'Oil Change Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('90631fe0-e7d6-bf77-2f4c-932410196fe1'::uuid, 'Electronic Sports Service', 'DEMO-SVC-001', 'Electronic Sports Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('392bedbe-e0c6-4bec-1fb4-310b48eb14f1'::uuid, 'Sleek Games Service', 'DEMO-SVC-002', 'Sleek Games Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('a4abfdd9-27cc-1e29-8aff-7674dd818b3b'::uuid, 'Handcrafted Automotive Service', 'DEMO-SVC-003', 'Handcrafted Automotive Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('9cfa6f73-4cd1-96de-80a1-9a6d10dacb1d'::uuid, 'Electronic Games Service', 'DEMO-SVC-004', 'Electronic Games Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('489ff1c0-5015-7f07-e3b8-ad75969817c8'::uuid, 'Tasty Industrial Service', 'DEMO-SVC-005', 'Tasty Industrial Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('90fc694b-dcc7-7fc9-d3e0-7ccb153fc905'::uuid, 'Incredible Electronics Service', 'DEMO-SVC-006', 'Incredible Electronics Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('79040e39-4b3c-e4bb-622c-6b957bef9828'::uuid, 'Practical Music Service', 'DEMO-SVC-007', 'Practical Music Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('ebc5187d-3979-b8ef-fa26-380cff0619f0'::uuid, 'Rustic Beauty Service', 'DEMO-SVC-008', 'Rustic Beauty Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('61c7e437-21ab-3e65-745e-c511018e928d'::uuid, 'Bespoke Industrial Service', 'DEMO-SVC-009', 'Bespoke Industrial Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('1484e4fa-65b5-2290-c192-f0b75f2f1bf7'::uuid, 'Small Games Service', 'DEMO-SVC-010', 'Small Games Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('9782e99c-0da7-ba4d-8c41-b8e3f07c7d90'::uuid, 'Elegant Music Service', 'DEMO-SVC-011', 'Elegant Music Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('2650f978-cb98-1f5c-fc0f-6395a6b991e3'::uuid, 'Electronic Baby Service', 'DEMO-SVC-012', 'Electronic Baby Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('233f1798-1667-c8ef-0143-d5eb6e44fc74'::uuid, 'Bespoke Shoes Service', 'DEMO-SVC-013', 'Bespoke Shoes Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('b97490b9-5c89-f80d-8d90-5d7c5cd2ad87'::uuid, 'Electronic Baby Service', 'DEMO-SVC-014', 'Electronic Baby Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('8be45b40-c40c-2d56-3c3d-268c8a1a0204'::uuid, 'Handcrafted Garden Service', 'DEMO-SVC-015', 'Handcrafted Garden Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('f5796374-6e2f-a130-0862-dc9fe617edea'::uuid, 'Tasty Toys Service', 'DEMO-SVC-016', 'Tasty Toys Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('aad3f4fe-559e-c8c9-a09f-b3e011925fa1'::uuid, 'Handcrafted Baby Service', 'DEMO-SVC-017', 'Handcrafted Baby Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('a0eb11df-accc-c826-26fc-945bece1f723'::uuid, 'Luxurious Grocery Service', 'DEMO-SVC-018', 'Luxurious Grocery Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('a655068c-f23b-dc30-1cb6-557eb8360da8'::uuid, 'Elegant Health Service', 'DEMO-SVC-019', 'Elegant Health Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
INSERT INTO service (id, name, short_description, long_description)
VALUES ('7f99fdfd-68fd-0605-730c-3336322356cf'::uuid, 'Incredible Baby Service', 'DEMO-SVC-020', 'Incredible Baby Service')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, short_description = EXCLUDED.short_description, long_description = EXCLUDED.long_description;
