-- ADR-0062 (plan WS3 wave 12): the tenancy column the Postgres baseline already carries on the seven
-- tenant-scoped tables, for the H2 dev/openapi chain. Postgres fills tenant_id from app_current_tenant()
-- and confines every row with row-level security; H2 has neither, so the column defaults to the alpha
-- default tenant here -- the one tenant these profiles run as (pos.tenancy.default-tenant-id).
-- Isolation is proven on Postgres (TenantIsolationIT), not here.

ALTER TABLE nlti_session ADD COLUMN tenant_id UUID DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE nlti_request ADD COLUMN tenant_id UUID DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE nlti_intent ADD COLUMN tenant_id UUID DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE nlti_write_plan ADD COLUMN tenant_id UUID DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE nlti_audit_event ADD COLUMN tenant_id UUID DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE mcp_eval_turn_trace ADD COLUMN tenant_id UUID DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE mcp_tool_invocation_log ADD COLUMN tenant_id UUID DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
