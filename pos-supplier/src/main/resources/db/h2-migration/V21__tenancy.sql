-- ADR-0062 (plan WS3 wave 7): the tenancy columns the Postgres baseline already carries, for the H2
-- slices. Postgres fills tenant_id from app_current_tenant() and confines every row with row-level
-- security; H2 has neither, so the column defaults to the alpha default tenant here -- the one tenant
-- the slices run as (pos.tenancy.default-tenant-id) -- which keeps the raw-SQL fixtures valid and
-- lets Hibernate's @TenantId filter match what those fixtures wrote. The @ElementCollection table
-- (supplier_endpoint_binding_redaction) gets the same default because Hibernate stamps @TenantId on
-- entity tables only. Isolation is proven on Postgres (TenantIsolationIT), not here.
--
-- H2(MODE=PostgreSQL)-compatible, matching V20.
ALTER TABLE ext_product_code ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_account ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_audit_access ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_auth_config ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_endpoint_binding ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_endpoint_binding_redaction ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_exchange_audit ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_invoice ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_invoice_line ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_mktcat_subscription ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_mktcat_variant ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_pricat_entry ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_pricat_import ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_pricat_unmatched_line ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_profile ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_schedule_lease ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_stock_snapshot ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_stock_snapshot_line ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_transmission_intent ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_transmission_line ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
ALTER TABLE supplier_workorder_authorization ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;

-- Global table (no policy on Postgres either): the producing tenant, carried as data.
ALTER TABLE supplier_event_outbox ADD COLUMN tenant_id uuid DEFAULT '01900000-0000-7000-8000-000000000001' NOT NULL;
