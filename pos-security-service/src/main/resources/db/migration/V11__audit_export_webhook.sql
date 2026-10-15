-- #2497: WEBHOOK delivery of audit exports to one registered endpoint per tenant.
--
-- audit_export_webhook is tenant-scoped (ADR-0062 section 2), introduced post-baseline with the full
-- tenancy schema (TENANCY_SCHEMA.md, AGENTS.md #1996): tenant_id first with the app_current_tenant()
-- default, the (tenant_id, webhook_id) key, RLS enabled and forced, and the tenant_isolation policy.
-- UNIQUE (tenant_id) makes it one destination per tenant and is also the tenant index, so no separate
-- audit_export_webhook_tenant_idx is created. signing_secret is stored as-is, protected by RLS;
-- encryption at rest is a follow-up (pos-security-service README).
--
-- audit_export_jobs gains the destination a WEBHOOK job was requested against (pinned, so a later
-- re-registration never redirects an in-flight job) and the delivery outcome. No foreign key to the
-- webhook: the job is history and outlives a deleted registration.

CREATE TABLE public.audit_export_webhook (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    webhook_id uuid NOT NULL,
    url character varying(2048) NOT NULL,
    signing_secret character varying(128) NOT NULL,
    registered_by character varying(255) NOT NULL,
    registered_at timestamp with time zone NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

ALTER TABLE ONLY public.audit_export_webhook
    ADD CONSTRAINT audit_export_webhook_pkey PRIMARY KEY (webhook_id);
ALTER TABLE ONLY public.audit_export_webhook
    ADD CONSTRAINT audit_export_webhook_tenant_key UNIQUE (tenant_id, webhook_id);
ALTER TABLE ONLY public.audit_export_webhook
    ADD CONSTRAINT audit_export_webhook_one_per_tenant UNIQUE (tenant_id);

ALTER TABLE public.audit_export_webhook ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_export_webhook FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.audit_export_webhook
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

ALTER TABLE public.audit_export_jobs
    ADD COLUMN webhook_id uuid,
    ADD COLUMN webhook_url character varying(2048),
    ADD COLUMN delivery_status character varying(16),
    ADD COLUMN delivery_attempts integer,
    ADD COLUMN delivered_at timestamp with time zone,
    ADD COLUMN delivery_error character varying(200);
