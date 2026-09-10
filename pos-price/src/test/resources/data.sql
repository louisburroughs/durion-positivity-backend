-- ADR-0062: tenant_id is NOT NULL on every scoped table; H2 has no app_current_tenant(), so the
-- fixtures name the alpha default tenant, the one the H2 slices run as.
INSERT INTO product_base_price (tenant_id, id, product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('01900000-0000-7000-8000-000000000001', '11111111-1111-1111-1111-111111111100', '11111111-1111-1111-1111-111111111111', 100.0000, 'USD', TIMESTAMP '2020-01-01 00:00:00', NULL, TIMESTAMP '2020-01-01 00:00:00', TIMESTAMP '2020-01-01 00:00:00');

INSERT INTO location_price_override (tenant_id, id, product_id, location_id, override_price, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('01900000-0000-7000-8000-000000000001', '22222222-2222-2222-2222-222222222000', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 95.0000, 'USD', TIMESTAMP '2020-01-01 00:00:00', NULL, TIMESTAMP '2020-01-01 00:00:00', TIMESTAMP '2020-01-01 00:00:00');

INSERT INTO customer_tier_pricing_rule (tenant_id, id, product_id, customer_tier_id, discount_rate, effective_from, effective_to, created_at, updated_at)
VALUES ('01900000-0000-7000-8000-000000000001', '33333333-3333-3333-3333-333333333000', '11111111-1111-1111-1111-111111111111', '33333333-3333-3333-3333-333333333333', 0.1000, TIMESTAMP '2020-01-01 00:00:00', NULL, TIMESTAMP '2020-01-01 00:00:00', TIMESTAMP '2020-01-01 00:00:00');

INSERT INTO product_base_price (tenant_id, id, product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('01900000-0000-7000-8000-000000000001', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa00', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 50.0000, 'USD', TIMESTAMP '2020-01-01 00:00:00', NULL, TIMESTAMP '2020-01-01 00:00:00', TIMESTAMP '2020-01-01 00:00:00');

INSERT INTO product_base_price (tenant_id, id, product_id, msrp, currency, effective_from, effective_to, created_at, updated_at)
VALUES ('01900000-0000-7000-8000-000000000001', '11111111-1111-1111-1111-111111111101', '11111111-1111-1111-1111-111111111111', 130.0000, 'CAD', TIMESTAMP '2020-01-01 00:00:00', NULL, TIMESTAMP '2020-01-01 00:00:00', TIMESTAMP '2020-01-01 00:00:00');
