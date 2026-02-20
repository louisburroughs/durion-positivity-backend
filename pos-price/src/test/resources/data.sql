INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to)
VALUES ('11111111-1111-1111-1111-111111111111', 100.0000, 'USD', TIMESTAMP '2020-01-01 00:00:00', NULL);

INSERT INTO location_price_override (id, product_id, location_id, override_price, currency, effective_from, effective_to)
VALUES ('22222222-2222-2222-2222-222222222000', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 95.0000, 'USD', TIMESTAMP '2020-01-01 00:00:00', NULL);

INSERT INTO customer_tier_pricing_rule (id, product_id, customer_tier_id, discount_rate, effective_from, effective_to)
VALUES ('33333333-3333-3333-3333-333333333000', '11111111-1111-1111-1111-111111111111', '33333333-3333-3333-3333-333333333333', 0.1000, TIMESTAMP '2020-01-01 00:00:00', NULL);

INSERT INTO product_base_price (product_id, msrp, currency, effective_from, effective_to)
VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 50.0000, 'USD', TIMESTAMP '2020-01-01 00:00:00', NULL);
