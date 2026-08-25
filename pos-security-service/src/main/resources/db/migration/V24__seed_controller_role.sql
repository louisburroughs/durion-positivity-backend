INSERT INTO roles (id, name, description, created_at, created_by)
VALUES (
    '7169d371-053c-4182-a0f3-3a4f8a9ae8ce'::uuid,
    'CONTROLLER',
    'Accounting management: GL configuration, journal entries, close cycle, reconciliation, AP',
    NOW(),
    'system'
)
ON CONFLICT (name) DO NOTHING;
