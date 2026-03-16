INSERT INTO roles (id, name, description, created_at, created_by)
VALUES (
    gen_random_uuid(),
    'SELF_SERVICE_CUSTOMER',
    'Low-privilege self-registration role for external customer access',
    NOW(),
    'system'
)
ON CONFLICT (name) DO NOTHING;
