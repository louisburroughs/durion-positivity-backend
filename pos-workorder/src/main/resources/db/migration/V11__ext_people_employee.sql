-- #2119 / #2120: a tenant-scoped replica of pos-people's employment status, fed by
-- people.employee.updated on people.events.v1 (ADR-0044 §6), so this module can tell an offboarded
-- technician (TERMINATED / DISABLED / SUSPENDED) from an employed one without a synchronous
-- cross-domain read.
--
-- One row per employee (pos-people's employeeId); a person may have more than one employee row
-- over time, so readers pick the latest by status_effective_at, falling back to updated_at. The
-- table starts empty and stays empty until an employee fact arrives; consumers must keep behaving
-- exactly as today (treat the person as employed) for anyone without a row, since replica lag,
-- bootstrap and a stalled DLQ must not take a shop offline.

CREATE TABLE public.ext_people_employee (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    employee_id uuid NOT NULL,
    person_id uuid NOT NULL,
    status character varying(32) NOT NULL,
    status_effective_at timestamp(6) with time zone,
    termination_date date,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_people_employee_pkey PRIMARY KEY (employee_id),
    CONSTRAINT ext_people_employee_tenant_key UNIQUE (tenant_id, employee_id)
);

CREATE INDEX ext_people_employee_tenant_idx ON public.ext_people_employee USING btree (tenant_id);
CREATE INDEX ext_people_employee_tenant_person_idx ON public.ext_people_employee USING btree (tenant_id, person_id);

ALTER TABLE public.ext_people_employee ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_people_employee FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_people_employee
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
