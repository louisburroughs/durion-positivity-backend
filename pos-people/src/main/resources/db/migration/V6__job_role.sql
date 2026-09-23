-- durion#2157: the job role a tenant assigns its employees -- "Lead Technician", "Parts Counter",
-- "HR Generalist" -- and the nullable reference to it on employee.
--
-- Tenant-scoped, the OPPOSITE of V3's skill registry (see that migration's header). A skill is
-- reference data issued by a national certifying body (ASE, EPA): BRAKES-MEDIUM_HEAVY means the
-- same thing in every shop, so it is platform-global with no tenant_id (docs/TENANCY_SCHEMA.md
-- "Global tables"). A job role is not issued by anybody outside the tenant -- it is a label the
-- tenant invents for its own org chart. Two tenants both calling something "Lead Technician" is
-- coincidence, not agreement, and nothing stops one tenant from having a "Parts Counter" role the
-- next tenant has never heard of. There is no authority a shared vocabulary could defer to, so
-- job_role gets the full ADR-0062 tenant-scoped treatment: tenant_id first, row-level security,
-- and every constraint led by tenant_id -- and it must NOT appear in tenancy-global-tables.txt.
--
-- Also unlike skill (spec D13's min/max GVWR range), a job role carries no certification
-- semantics and grants no permission: it is a label for reporting, filtering and display, enforced
-- nowhere. Permission-bearing "roles" are application roles and live in pos-security-service; the
-- two are namespaced apart on purpose (job_role here, security-service's own roles table there) so
-- neither can be mistaken for the other.
CREATE TABLE public.job_role (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(255) NOT NULL,
    description character varying(1000),
    active boolean DEFAULT true NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT job_role_pkey PRIMARY KEY (id),
    CONSTRAINT job_role_tenant_key UNIQUE (tenant_id, id),
    CONSTRAINT job_role_tenant_code_key UNIQUE (tenant_id, code)
);
CREATE INDEX job_role_tenant_idx ON public.job_role USING btree (tenant_id);
ALTER TABLE public.job_role ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.job_role FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.job_role
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
COMMENT ON TABLE public.job_role IS
    'durion#2157: HR master data a tenant defines for its own employees. Tenant-scoped (unlike the platform-global skill registry): a job role is the tenant''s own label, not a nationally-issued certification.';

-- The employee's job role. Nullable: existing rows and any employee hired without one read back
-- null rather than requiring a placeholder row. tenant_id + job_role_id is composite
-- (TENANCY_SCHEMA.md step 2: a foreign key between two tenant-scoped tables must be, since FK
-- checks run with owner privileges and bypass row-level security -- a single-column key could be
-- satisfied by a job_role row belonging to a different tenant).
ALTER TABLE public.employee ADD COLUMN job_role_id uuid;
ALTER TABLE public.employee
    ADD CONSTRAINT fk_employee_job_role FOREIGN KEY (tenant_id, job_role_id) REFERENCES public.job_role(tenant_id, id);
CREATE INDEX employee_job_role_idx ON public.employee USING btree (tenant_id, job_role_id);
COMMENT ON COLUMN public.employee.job_role_id IS
    'durion#2157: the tenant-defined job role this employee holds, or null when none is set.';
