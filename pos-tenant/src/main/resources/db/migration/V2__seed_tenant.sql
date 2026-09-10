-- Platform bootstrap (ADR-0062 section 7). Runs inside Flyway's migration transaction; the tenant
-- binding below is transaction-local. Every row here belongs to the platform tenant.
SELECT set_config('app.current_tenant', '01900000-0000-7000-8000-000000000000', true);

-- The platform operator's own account, and the reserved platform tenant under the constant id
-- published by pos-tenancy-common (PlatformTenant.ID). pos-security-service bootstraps its platform
-- users and role template against this id before the first event flows, so the tenant is ACTIVE
-- from the start rather than waiting for tenant.provisioned.
INSERT INTO account (id, legal_name, trading_name, status, tax_id, home_country, home_currency, version, created_at, updated_at)
VALUES ('01900000-0000-7000-8000-00000000a000', 'Durion Platform', 'Durion', 'ACTIVE', NULL, 'US', 'USD', 0, now(), now());

INSERT INTO tenant (id, slug, display_name, status, account_id, cell, initial_admin_email, version, created_at, updated_at, activated_at)
VALUES ('01900000-0000-7000-8000-000000000000', 'platform', 'Durion Platform', 'ACTIVE',
        '01900000-0000-7000-8000-00000000a000', NULL, 'platform@durionpos.org', 0, now(), now(), now());

-- The alpha default tenant (docs/TENANCY_SCHEMA.md): the one tenant every flattened seed row in
-- every module already belongs to, registered here so login resolution and the ext_tenant
-- replicas (plan WS2b) know it. ACTIVE for the same reason as the platform tenant.
INSERT INTO account (id, legal_name, trading_name, status, tax_id, home_country, home_currency, version, created_at, updated_at)
VALUES ('01900000-0000-7000-8000-00000000a001', 'Durion Alpha Cell', 'Alpha', 'ACTIVE', NULL, 'US', 'USD', 0, now(), now());

INSERT INTO tenant (id, slug, display_name, status, account_id, cell, initial_admin_email, version, created_at, updated_at, activated_at)
VALUES ('01900000-0000-7000-8000-000000000001', 'alpha', 'Alpha', 'ACTIVE',
        '01900000-0000-7000-8000-00000000a001', 'alpha', 'admin@durionpos.org', 0, now(), now(), now());
