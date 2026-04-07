-- Repeatable seed migration for price reference data.
-- Source: durion/scripts/seed-generator/generated-seed-sql/004_catalog_pricing_inventory.sql
SET TIME ZONE 'UTC';

-- Base prices
-- SVC-OIL
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('243afcc1-bdfc-6e67-8095-88d95562b57a'::uuid, 79.99, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0001
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('9b4a7745-e647-0556-f5ce-b6ac9d6ab887'::uuid, 301.34, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0002
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('d414c5b9-d011-63cc-f5a8-67264d92da65'::uuid, 433.76, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0003
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('1f575859-02ed-3f0c-2abe-33878315d465'::uuid, 485.11, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0004
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('2a66564d-7508-be8b-96ee-dd3869399c1e'::uuid, 95.78, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0005
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('2a405725-6fde-1fb7-ecba-3b6e9ad9a473'::uuid, 149.16, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0006
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('8c42e438-7a8c-0363-1364-577c0485ccca'::uuid, 186.35, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0007
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('75fc4962-20bd-ace0-c530-5ddaf7dac224'::uuid, 259.55, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0008
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('dad79831-81d1-0f0d-5b12-7a35545cd3b2'::uuid, 89.41, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0009
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('7a3cd8ca-3f7f-0800-3e89-36187e1ddd26'::uuid, 405.16, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0010
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('827396d2-f4a5-823a-fef0-c9d2780ffcac'::uuid, 222.87, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0011
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('80c1d2fe-4065-0a65-00c8-033120b93c07'::uuid, 455.12, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0012
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('e3dbe531-9f04-dbb6-6a21-36c3b0bf71fa'::uuid, 262.43, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0013
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('f16b20ef-7195-78cb-9bb4-dafa580f6fa1'::uuid, 388.69, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0014
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('5bbc07a1-077f-b228-f3fa-d576eea8cbc1'::uuid, 461.33, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0015
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('4f7be1fd-bdf5-010c-6522-622853e12011'::uuid, 166.04, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0016
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('ea9b7f33-825c-545b-7eae-73822945b8a6'::uuid, 181.59, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0017
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('b38c0ab3-39df-bf82-b40d-9698079b38d4'::uuid, 402.09, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0018
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('ebe94090-b27d-a41e-cf52-33ab859b9ad4'::uuid, 103.36, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0019
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('8c5dd587-ff4e-18b4-2cf0-c9eabda92b63'::uuid, 365.86, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0020
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('b35ba828-fa3e-5d16-bcf0-b267718e18f7'::uuid, 62.35, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0021
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('0216e911-2fb6-6513-03c1-7abe3b20041c'::uuid, 36.46, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0022
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('3724af23-d332-e6d8-9d81-0b1feea90416'::uuid, 320.59, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0023
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('e26cdc2d-9d7b-1eb5-7a63-fcc6ff70ea2c'::uuid, 358.06, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0024
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('a4da60d2-6cfe-4277-8824-19573d4ffcd8'::uuid, 249.43, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0025
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('9d3c89e5-0084-e089-bcd3-3139dd285e93'::uuid, 58.4, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0026
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('28829836-c15f-6ca0-01b4-844ea0d1e18a'::uuid, 256.74, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0027
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('327c1d8b-060e-8622-ea60-4a97bb0a94ae'::uuid, 379, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0028
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('d30bfe06-90f5-5a42-2c95-05a0861e8889'::uuid, 84.8, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0029
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('3f8247e9-074c-0d01-107d-d69264f5ff40'::uuid, 436.38, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0030
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('441f0352-4c70-48ac-5a22-864385fc15c4'::uuid, 271.97, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0031
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('f50c11cd-4522-f2e2-d214-51d40279cf61'::uuid, 59.47, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0032
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('0ef68dab-587a-6f3b-6896-6147e4548c73'::uuid, 431.07, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0033
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('59970989-f902-9955-daaf-5fb5ae1894e8'::uuid, 114.94, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0034
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('4ef912e9-c49d-6b20-929a-45f8871f2efc'::uuid, 164.98, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0035
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('f19f1ef6-f56f-dc84-bcee-81cec24efff0'::uuid, 486.04, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0036
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('ac0b71ca-6bae-5dd4-371d-22360d5b2a3a'::uuid, 153.93, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0037
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('68278ea7-3c94-c2d0-dcc7-1709115b4af3'::uuid, 253.83, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0038
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('f0fdc244-d03b-1219-9303-393617e12c23'::uuid, 123.58, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0039
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('bfc48fc9-3a2d-0517-eb1f-a88c43fa46c2'::uuid, 124.81, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0040
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('f28eb52e-69a6-95f5-83a1-a64e5dca1086'::uuid, 365.47, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0041
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('be8ffeca-ce8a-cadd-a601-eee40b6b6202'::uuid, 270.21, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0042
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('68aa9945-ab53-7a0a-477f-0d9acf8332bf'::uuid, 97.32, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0043
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('599252f1-8e49-88cc-0304-e6d30a53b4f3'::uuid, 13.21, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0044
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('eebe246c-f406-9641-a294-0cbc7a2c4a73'::uuid, 91.31, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0045
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('0a888fed-4e09-7e0e-a09a-582f61f12120'::uuid, 73.07, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0046
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('35bf6a49-ce56-a6d5-dd15-d1c3d70006b9'::uuid, 439.29, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0047
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('c61f7313-eb69-1bfe-c2e5-98980781639a'::uuid, 279.82, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0048
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('b13aa0b7-ed82-b7ba-1748-de8f7fcd6518'::uuid, 449.13, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0049
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('1a3717b6-e98c-3595-9a61-01f82e7d954e'::uuid, 177.86, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0050
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('fbb70f76-7e83-2882-6a9b-8c40e4c78e10'::uuid, 391.04, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0051
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('7b50f39c-05c0-72c6-9abc-5efdd3056d2e'::uuid, 449.79, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0052
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('45497b08-0098-8e19-7bef-22060796c161'::uuid, 333.44, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0053
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('285a38fb-cfaa-9a31-02cf-37ccfc7d0a36'::uuid, 347.49, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0054
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('1df51696-bb8b-b61d-8a03-06afc97dada6'::uuid, 122.44, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0055
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('bdb21ecf-16db-d1da-93e6-26a26e769acd'::uuid, 425.37, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0056
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('ca40beae-f869-8124-871a-7ec5e47fd1dc'::uuid, 187.02, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0057
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('28200fe5-cd19-600a-c83a-1d82ca9721b7'::uuid, 199.58, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0058
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('069927c6-0505-34e0-84bc-3cda40b721e9'::uuid, 253.81, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0059
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('289bea8e-d2d9-c866-d8fc-66f51f8ae2ee'::uuid, 362.62, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0060
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('780ba2af-7c1b-2c50-624d-1994244da8ec'::uuid, 92.67, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0061
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('dd7abf6e-feea-2505-e25c-97c9cf96ea5b'::uuid, 188.23, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0062
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('25e13dac-0f07-2a30-4ef5-8a805e9fa2f9'::uuid, 483.5, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0063
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('3ab043a4-d75a-b8a0-55a3-b070886f7efd'::uuid, 195.62, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0064
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('bfe3befc-0f7d-27a0-446d-e807bd509109'::uuid, 280.62, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0065
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('2639d0dd-9a8b-79a8-941a-a7126cb55f1f'::uuid, 53.1, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0066
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('3b85dac8-d238-b52c-1c97-48e2fa2525b2'::uuid, 261.57, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0067
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('0ac45196-d09c-a307-a134-bcdd244145bc'::uuid, 352.73, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0068
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('c1f08906-c141-6fb3-cc9c-41d144010a8f'::uuid, 406.01, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0069
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('0292dfbb-9184-31c4-8519-8208946bd3ed'::uuid, 253.25, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0070
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('4ab59918-d167-1c45-91ad-716426969cc0'::uuid, 398.92, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0071
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('2b546672-7831-7dc7-4c02-dcb395d1affa'::uuid, 51.52, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0072
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('39de27ff-368d-823a-2a7b-4fb11fe40b0a'::uuid, 273.61, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0073
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('1760d77a-acea-15a1-5455-e9ef6cd0e9c4'::uuid, 23.48, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0074
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('f913f396-cb8b-fb04-ed28-66a69ea2d6e7'::uuid, 263.51, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0075
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('f5d8d58a-3728-21ca-2008-dcea6da5a2ce'::uuid, 47.24, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0076
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('bc9eb938-5639-28d9-b489-945f5c2422bd'::uuid, 320.53, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0077
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('d128242c-308a-30df-5b64-acb229d30d9b'::uuid, 164.86, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0078
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('8501e6c4-fe8f-7fa1-ad8b-21869c9b50f3'::uuid, 43.83, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0079
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('50a06410-d1c2-cb8b-3aff-a8f8e53608bf'::uuid, 349.51, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0080
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('708676d9-cf81-5823-eaf0-bccf9c38390c'::uuid, 128.87, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0081
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('90120769-47d6-a2fd-bd86-85f080b51fa7'::uuid, 143.57, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0082
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('79da4b50-3157-43fa-0c12-a894617460c0'::uuid, 307.8, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0083
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('9dbb9196-cbfa-cd61-0c18-d9c14867e180'::uuid, 380.14, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0084
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('c02a991e-7da3-d09b-6e20-99a0abf502b0'::uuid, 25.16, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0085
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('8cf08dc7-9975-68e0-dd07-53312b5a3fe3'::uuid, 53.42, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0086
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('e69ac0ae-0919-d53f-3799-372471b0e14d'::uuid, 219.76, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0087
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('10bfaed7-e6a8-818d-495d-88a723997a13'::uuid, 27.42, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0088
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('1356169e-c3e4-5033-d305-c936bd3ad1c4'::uuid, 428.97, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0089
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('fc705e31-fce8-73de-24a9-fdac79fb5679'::uuid, 323, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0090
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('285d5afb-38c2-5df6-5d9f-1858da4c8d4a'::uuid, 289.86, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0091
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('79b22253-143a-b642-7b78-fa1c2d2ae6f2'::uuid, 275.08, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0092
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('08483c27-cf18-9e47-3d3d-87397fc6fcd7'::uuid, 453.15, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0093
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('17907205-469f-be72-d296-a1faff352f2d'::uuid, 14.01, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0094
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('479dc48e-6edb-7af1-7796-bae52cb24f70'::uuid, 162.89, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0095
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('255dafe3-9d1f-82fb-668c-297ea477da6a'::uuid, 144.52, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0096
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('926833de-ce95-aa20-92ec-73639bc63499'::uuid, 439.35, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0097
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('07814fe4-3fd7-c8d5-5021-aa14141cf7b4'::uuid, 92.83, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0098
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('38ffaaec-59c3-96dd-4079-6c8e125bc73a'::uuid, 209.24, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0099
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('df5d887c-9449-ad7f-b518-83ea4840e88c'::uuid, 465.73, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-PROD-0100
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('5cf90dbf-7b35-43bf-ff48-915e16c245b7'::uuid, 378.5, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-001
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('c3c710f5-7a42-6030-57e5-6b2edb0fbd40'::uuid, 196.63, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-002
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('e4972a69-fe1a-fcb5-0f12-fe9522f2cd48'::uuid, 317.62, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-003
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('d0117c2e-1892-493a-854b-75118ba4ea1c'::uuid, 320.67, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-004
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('9dcb6110-1da0-507c-54cc-8b698d2caaa0'::uuid, 334.52, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-005
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('5a8bea83-ccf2-a1cd-39c1-0803762315f5'::uuid, 235.87, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-006
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('9779243b-dee4-b904-b6fb-79de52b53959'::uuid, 141.88, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-007
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('85c96cb5-1250-f216-adac-1d0a64427b34'::uuid, 285.39, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-008
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('5b8ab7b6-ea9b-c1c7-37c7-ee5b83a16ae2'::uuid, 193.27, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-009
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('54575b57-0b82-1d2c-862b-104b12baf944'::uuid, 176.87, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-010
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('573991c9-c926-2ecb-a30d-2986cc10718d'::uuid, 76.29, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-011
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('4f5efbb1-d973-9f35-e291-36a2174a8984'::uuid, 231.65, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-012
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('fa6e0683-883a-2e06-2c38-855d49144bc2'::uuid, 257.3, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-013
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('71276b3a-edea-3b9a-268b-e5dc2b9c9e46'::uuid, 258.94, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-014
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('8d1ac12e-1bbf-97c4-57c2-1fe845c18ce6'::uuid, 345.86, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-015
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('264b6f0d-9fe6-6801-b2e9-f72c79bb2782'::uuid, 291.97, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-016
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('03f2ae62-3d65-2d8f-ad49-3e49107a162b'::uuid, 273.55, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-017
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('9d22f60b-3f8d-8b27-8303-039daea3276d'::uuid, 280.92, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-018
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('6a79aebf-647c-abef-692f-4ebfb951c29d'::uuid, 320.97, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-019
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('9e2eefef-286f-a765-a255-de895c10bd22'::uuid, 43.51, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
-- DEMO-SVC-020
INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('59145b92-6a42-b3a0-e63d-966245ef1b65'::uuid, 76.83, 'USD', NOW(), NULL, NOW(), NOW())
ON CONFLICT (product_id) DO UPDATE SET
    msrp = EXCLUDED.msrp,
    currency = EXCLUDED.currency,
    effective_from = EXCLUDED.effective_from,
    effective_to = EXCLUDED.effective_to,
    updated_at = NOW();
