-- Platform tenant bootstrap and the per-tenant role template (ADR-0062 sections 6 and 7,
-- plan WS2b part 2).
--
-- Repeatables run in filename order, so this file runs after the alpha floor roles
-- (R__seed_reference_security, V2), their grants (R__seed_role_permissions) and their location
-- scope (R__seed_role_location_scope) exist. It does three things:
--
--   1. Marks alpha's Flyway floor roles with template_key, so they reject delete exactly like the
--      copies every later tenant receives (section 6: canonical names are immutable per tenant).
--   2. Holds the role template as data in the platform tenant: a copy of the floor roles, their
--      grants and their ADR-0061 location-scope attributes, keyed by template_key.
--      TenantProvisioningService reads this copy under the platform binding and applies it to a
--      new tenant on tenant.created. Roles the bulk loader adds after startup (roles.csv) join
--      the template when the job targets the platform tenant (WS8: RoleBulkIngestController sets
--      template_key under the platform binding), and reach existing tenants through
--      POST /v1/platform/tenants/{tenantId}/roles/reconcile-template.
--   3. Bootstraps PLATFORM_ADMIN and admin.platform in the platform tenant: the only role holding
--      the platform:tenant:* and platform:account:* families (section 7), and the one user that
--      can reach pos-tenant's registry. The alpha ADMIN no longer holds those grants. It also
--      holds the four bulk-import and role grants the documented platform role-template load needs
--      (plan WS8); see the comment on that VALUES block.
--
-- Idempotent: every write is ON CONFLICT DO NOTHING (the seed password is refreshed on re-run,
-- as for admin.alpha). RoleSeedSql, the parser behind the alpha role-set tests, skips this file:
-- these rows belong to the platform tenant, not to the alpha role baseline those tests govern.
-- audit-rbac.py and generate-permissions.py read the PLATFORM_ADMIN grant tuples below as a third
-- grant source, so platform:* stays reachable in their eyes.

SET TIME ZONE 'UTC';

-- ---------------------------------------------------------------------------
-- 1. Alpha's floor roles are template roles.
-- ---------------------------------------------------------------------------
SELECT set_config('app.current_tenant', '01900000-0000-7000-8000-000000000001', true);

UPDATE roles SET template_key = name
 WHERE template_key IS NULL
   AND name IN ('ADMIN', 'SYSTEM_ADMINISTRATOR', 'DISPATCHER', 'SHOP_MANAGER',
                'SELF_SERVICE_CUSTOMER', 'CONTROLLER');

-- ---------------------------------------------------------------------------
-- 2. The template: alpha's floor roles copied into the platform tenant.
--
-- Two bindings inside one transaction: the rows are read under alpha (row-level security shows
-- only the bound tenant, also to the owner: every scoped table is FORCE ROW LEVEL SECURITY) into
-- transaction-scoped temp tables, then written under the platform binding. Names resolve the
-- copies, so re-running after a grant change adds the new grant and touches nothing else.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    alpha    CONSTANT uuid := '01900000-0000-7000-8000-000000000001';
    platform CONSTANT uuid := '01900000-0000-7000-8000-000000000000';
BEGIN
    PERFORM set_config('app.current_tenant', alpha::text, true);

    CREATE TEMP TABLE tenant_template_roles ON COMMIT DROP AS
        SELECT name, description, persona_title, persona_focus, persona_tone, mcp_persona_rank,
               mcp_persona_eligible, location_scope, location_hierarchy
          FROM roles
         WHERE template_key IS NOT NULL;

    CREATE TEMP TABLE tenant_template_grants ON COMMIT DROP AS
        SELECT r.name AS role_name, rp.permission_id
          FROM role_permissions rp
          JOIN roles r ON r.id = rp.role_id
         WHERE r.template_key IS NOT NULL;

    PERFORM set_config('app.current_tenant', platform::text, true);

    INSERT INTO roles (tenant_id, id, name, description, created_at, created_by,
                       persona_title, persona_focus, persona_tone, mcp_persona_rank,
                       mcp_persona_eligible, location_scope, location_hierarchy, template_key)
    SELECT platform, gen_random_uuid(), t.name, t.description, NOW(), 'seed-tenant-template',
           t.persona_title, t.persona_focus, t.persona_tone, t.mcp_persona_rank,
           t.mcp_persona_eligible, t.location_scope, t.location_hierarchy, t.name
      FROM tenant_template_roles t
    ON CONFLICT (tenant_id, name) DO NOTHING;

    -- Keep the copies' attributes in step with alpha's floor (a repeatable re-run after a
    -- persona or scope edit): name is the key, everything else follows.
    UPDATE roles r
       SET description = t.description,
           persona_title = t.persona_title,
           persona_focus = t.persona_focus,
           persona_tone = t.persona_tone,
           mcp_persona_rank = t.mcp_persona_rank,
           mcp_persona_eligible = t.mcp_persona_eligible,
           location_scope = t.location_scope,
           location_hierarchy = t.location_hierarchy
      FROM tenant_template_roles t
     WHERE r.name = t.name
       AND r.template_key IS NOT NULL;

    INSERT INTO role_permissions (tenant_id, role_id, permission_id, granted_at, granted_by)
    SELECT platform, r.id, g.permission_id, NOW(), 'seed-tenant-template'
      FROM tenant_template_grants g
      JOIN roles r ON r.name = g.role_name
    ON CONFLICT DO NOTHING;
END $$;

-- ---------------------------------------------------------------------------
-- 3. PLATFORM_ADMIN and admin.platform, platform tenant only (section 7).
--
-- Not an MCP persona (mcp_persona_eligible false): platform operators work the registry, not the
-- shop floor. Location scope ALL / OTHER: pos-tenant's rows carry no location.
-- ---------------------------------------------------------------------------
SELECT set_config('app.current_tenant', '01900000-0000-7000-8000-000000000000', true);

INSERT INTO roles (id, name, description, created_at, created_by,
                   persona_title, persona_focus, persona_tone, mcp_persona_rank,
                   mcp_persona_eligible, location_scope, location_hierarchy)
VALUES ('01900000-0000-7000-8000-0000000a0100'::uuid, 'PLATFORM_ADMIN',
        'Platform operator: tenant and account registry (ADR-0062 section 7)', NOW(),
        'seed-tenant-template',
        'platform operator',
        'tenant onboarding, account records, and the tenant lifecycle',
        'careful, explicit about blast radius across tenants',
        5, false, 'ALL', 'OTHER')
ON CONFLICT (tenant_id, name) DO NOTHING;

-- The platform operator's grants. Two families:
--
--   * platform:* (section 7) -- the tenant and account registry, held nowhere else.
--   * the four an operator needs to load the role template through pos-bulk-loader (plan WS8):
--     bulkImport:upload:execute and bulkImport:status:read gate every create/upload/process/status
--     call on the loader, and security:role:create / security:role:edit gate the /v1/roles and
--     /v1/roles/permissions bulk-ingest endpoints the loader then calls. Without them the
--     documented platform template load (docs/OPERATIONS_RUNBOOK.md, "Bulk loading into a tenant")
--     is refused with 403 before a single row is read. They are safe here because a load, like
--     everything else this role does, runs under the platform binding: the roles it writes are the
--     template's own rows in the platform tenant, which is exactly what the operator owns. No
--     tenant role receives these tuples -- this file writes only to the platform tenant.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
    ('PLATFORM_ADMIN', 'bulkImport:status:read'),
    ('PLATFORM_ADMIN', 'bulkImport:upload:execute'),
    ('PLATFORM_ADMIN', 'platform:account:create'),
    ('PLATFORM_ADMIN', 'platform:account:read'),
    ('PLATFORM_ADMIN', 'platform:account:update'),
    ('PLATFORM_ADMIN', 'platform:tenant:create'),
    ('PLATFORM_ADMIN', 'platform:tenant:decommission'),
    ('PLATFORM_ADMIN', 'platform:tenant:provision'),
    ('PLATFORM_ADMIN', 'platform:tenant:reactivate'),
    ('PLATFORM_ADMIN', 'platform:tenant:read'),
    ('PLATFORM_ADMIN', 'platform:tenant:suspend'),
    ('PLATFORM_ADMIN', 'platform:tenant:update'),
    ('PLATFORM_ADMIN', 'security:role:create'),
    ('PLATFORM_ADMIN', 'security:role:edit')
) AS g(role_name, permission_name)
JOIN roles r ON r.name = g.role_name
JOIN permissions p ON p.name = g.permission_name
ON CONFLICT DO NOTHING;

-- The first platform operator. Same placeholder as admin.alpha; no person link: pos-people has
-- no platform-tenant rows (ADR-0015 section 3 is a tenant concern).
INSERT INTO users (id, username, password, enabled)
VALUES ('01900000-0000-7000-8000-0000000a0101'::uuid, 'admin.platform', '${seed_admin_password_hash}', TRUE)
ON CONFLICT (tenant_id, username) DO UPDATE SET password = EXCLUDED.password, enabled = EXCLUDED.enabled;

INSERT INTO role_assignments (id, user_id, role_id, effective_start_date, created_at, created_by)
VALUES ('01900000-0000-7000-8000-0000000a0102'::uuid, '01900000-0000-7000-8000-0000000a0101'::uuid,
        '01900000-0000-7000-8000-0000000a0100'::uuid, CURRENT_DATE, NOW(), 'seed-tenant-template')
ON CONFLICT (tenant_id, id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. Fail loudly if the platform bootstrap did not resolve.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    template_roles integer;
    platform_grants integer;
BEGIN
    SELECT count(*) INTO template_roles FROM roles WHERE template_key IS NOT NULL;
    IF template_roles < 6 THEN
        RAISE EXCEPTION 'platform role template holds % roles, expected the six floor roles', template_roles;
    END IF;

    SELECT count(*) INTO platform_grants
      FROM role_permissions rp
      JOIN roles r ON r.id = rp.role_id
     WHERE r.name = 'PLATFORM_ADMIN';
    IF platform_grants < 14 THEN
        RAISE EXCEPTION 'PLATFORM_ADMIN holds % grants, expected 14: the ten platform:* families plus the four the role-template bulk load needs (permission rows missing?)', platform_grants;
    END IF;
END $$;
