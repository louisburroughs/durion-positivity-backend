-- Repeatable seed migration for invoice reference data.
-- Source: durion/scripts/seed-generator/generated-seed-sql/006_invoice.sql
SET TIME ZONE 'UTC';


-- Billing rules
INSERT INTO billing_rules (
    id,
    party_id,
    purchase_order_required,
    payment_terms_code,
    invoice_delivery_method,
    invoice_grouping_strategy,
    version,
    created_at,
    updated_at,
    updated_by
)
VALUES (
    'b5f66d2d-b965-3f7a-0bdb-d7b34e086493'::uuid,
    'COMMERCIAL_DEFAULT',
    FALSE,
    'NET_30',
    'EMAIL',
    'SINGLE_INVOICE',
    0,
    NOW(),
    NOW(),
    'seed-generator'
)
ON CONFLICT (party_id) DO UPDATE SET
    purchase_order_required = EXCLUDED.purchase_order_required,
    payment_terms_code = EXCLUDED.payment_terms_code,
    invoice_delivery_method = EXCLUDED.invoice_delivery_method,
    invoice_grouping_strategy = EXCLUDED.invoice_grouping_strategy,
    version = EXCLUDED.version,
    updated_at = NOW(),
    updated_by = EXCLUDED.updated_by;
