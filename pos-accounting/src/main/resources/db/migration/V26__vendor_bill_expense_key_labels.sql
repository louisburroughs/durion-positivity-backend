-- CAP:550 AP reads (#2670 §D; #2676 review A2): the VENDOR_BILL expense keys' descriptions are served as the category
-- labels of GET /v1/accounting/vendor-bills/expense-categories, so they become plain labels in every tenant that
-- still holds the seeded text. Template application is add-only, so R__seed_reference_accounting.sql reaches only the
-- platform template and tenants provisioned after it; this carries the change to the tenants provisioned before.
--
-- Only a description that still equals the old seeded text changes: a description a tenant edited is left alone. Keys
-- and mappings do not change. Flyway runs as the owner, which row-level security does not restrict, so every tenant's
-- rows are reached. A fixed timestamp, as V11's renumbering: no SQL clock (ADR-0024).

UPDATE public.mapping_key k
SET description = t.label,
    modified_at = '2026-10-09 00:00:00+00',
    modified_by = 'v26-expense-labels'
FROM (VALUES
    ('EXPENSE_SHOP_SUPPLIES', 'Shop supplies (AW18, AW30)', 'Shop supplies'),
    ('EXPENSE_SMALL_TOOLS', 'Small tools (AW18, AW30)', 'Small tools'),
    ('EXPENSE_OFFICE_SUPPLIES', 'Office supplies (AW18, AW30)', 'Office supplies'),
    ('EXPENSE_BUILDING_REPAIRS', 'Building repairs (AW18, AW30)', 'Building repairs'),
    ('EXPENSE_EQUIPMENT_REPAIRS', 'Equipment repairs (AW18, AW30)', 'Equipment repairs'),
    ('EXPENSE_POSTAGE_SHIPPING', 'Postage and shipping (AW18, AW30)', 'Postage and shipping'),
    ('EXPENSE_CLEANING_JANITORIAL', 'Cleaning and janitorial (AW18, AW30)', 'Cleaning and janitorial'),
    ('EXPENSE_STAFF_MEALS', 'Staff meals (AW18, AW30)', 'Staff meals'),
    ('EXPENSE_VEHICLE_FUEL', 'Vehicle fuel (AW18, AW30)', 'Vehicle fuel')
) AS t(key_name, seeded, label),
     public.posting_category c
WHERE c.posting_category_id = k.posting_category_id
  AND c.tenant_id = k.tenant_id
  AND c.category_name = 'VENDOR_BILL'
  AND k.key_name = t.key_name
  AND k.description = t.seeded;
