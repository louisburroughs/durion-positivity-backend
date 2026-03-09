-- CAP-141 Candidate Roles v0: Shop Management RBAC
INSERT INTO roles (id, name, description, created_at, created_by)
VALUES
    (gen_random_uuid(), 'READ_ONLY_SCHEDULER', 'Read-only access to schedules and assignments', NOW(), 'system'),
    (gen_random_uuid(), 'DISPATCHER', 'Create and modify schedule entries and dispatch workorders', NOW(), 'system'),
    (gen_random_uuid(), 'SHOP_MANAGER', 'Full shop management: schedules, assignments, and audit review', NOW(), 'system'),
    (gen_random_uuid(), 'SECURITY_ADMIN', 'Manage roles, permissions, and user role assignments', NOW(), 'system')
ON CONFLICT (name) DO NOTHING;
