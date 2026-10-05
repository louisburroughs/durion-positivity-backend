-- #2408 (B-4): persisted, tenant-scoped audit export jobs and the file each completed job produced.
-- Both tables are tenant-scoped (ADR-0062 section 2), introduced post-baseline with the full
-- tenancy schema: tenant_id first, the RLS policy block, the tenant index and the
-- (tenant_id, <pk>) key; the file's foreign key to its job is composite (TENANCY_SCHEMA.md).

CREATE TABLE public.audit_export_jobs (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    job_id uuid NOT NULL,
    status character varying(16) NOT NULL,
    format character varying(8) NOT NULL,
    delivery_mode character varying(16) NOT NULL,
    filter_from_date timestamp with time zone,
    filter_to_date timestamp with time zone,
    filter_event_type character varying(100),
    filter_actor_id character varying(255),
    filter_aggregate_id character varying(255),
    requested_at timestamp with time zone NOT NULL,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    row_count bigint,
    error_message character varying(500),
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

ALTER TABLE ONLY public.audit_export_jobs
    ADD CONSTRAINT audit_export_jobs_pkey PRIMARY KEY (job_id);
ALTER TABLE ONLY public.audit_export_jobs
    ADD CONSTRAINT audit_export_jobs_tenant_key UNIQUE (tenant_id, job_id);

CREATE INDEX audit_export_jobs_tenant_idx ON public.audit_export_jobs USING btree (tenant_id);
CREATE INDEX idx_audit_export_jobs_status ON public.audit_export_jobs USING btree (tenant_id, status);

ALTER TABLE public.audit_export_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_export_jobs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.audit_export_jobs
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

CREATE TABLE public.audit_export_files (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    file_id uuid NOT NULL,
    job_id uuid NOT NULL,
    file_name character varying(255) NOT NULL,
    content_type character varying(100) NOT NULL,
    content text NOT NULL,
    size_bytes bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

ALTER TABLE ONLY public.audit_export_files
    ADD CONSTRAINT audit_export_files_pkey PRIMARY KEY (file_id);
ALTER TABLE ONLY public.audit_export_files
    ADD CONSTRAINT audit_export_files_tenant_key UNIQUE (tenant_id, file_id);
ALTER TABLE ONLY public.audit_export_files
    ADD CONSTRAINT audit_export_files_job_key UNIQUE (tenant_id, job_id);
ALTER TABLE ONLY public.audit_export_files
    ADD CONSTRAINT audit_export_files_job_fk FOREIGN KEY (tenant_id, job_id)
        REFERENCES public.audit_export_jobs (tenant_id, job_id) ON DELETE CASCADE;

CREATE INDEX audit_export_files_tenant_idx ON public.audit_export_files USING btree (tenant_id);

ALTER TABLE public.audit_export_files ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_export_files FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.audit_export_files
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
