-- #2530: the capacity read counts work that actually held a bay, appointment or not.
--
-- ext_workorder carries only the position a workorder holds now, and pos-workorder clears
-- it when the workorder closes, so a completed job's replica row no longer says which bay
-- it was done in. The owner publishes its service-position history on every
-- workorder.workorder.updated fact (WorkorderUpdatedV1.positions, a replacement set), and
-- this table mirrors it: one row per interval a workorder held a position for, written by
-- WorkorderEventsListener as a replace-set per fact, like ext_catalog_service_skill.

CREATE TABLE public.ext_workorder_position (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    workorder_id uuid NOT NULL,
    resource_type character varying(32) NOT NULL,
    resource_id uuid NOT NULL,
    location_id uuid,
    assigned_at timestamp(6) with time zone NOT NULL,
    released_at timestamp(6) with time zone,
    CONSTRAINT ext_workorder_position_pkey PRIMARY KEY (id),
    CONSTRAINT ext_workorder_position_tenant_key UNIQUE (tenant_id, id),
    CONSTRAINT ext_workorder_position_workorder_fkey
        FOREIGN KEY (tenant_id, workorder_id) REFERENCES public.ext_workorder(tenant_id, workorder_id) ON DELETE CASCADE
);

CREATE INDEX ext_workorder_position_tenant_idx ON public.ext_workorder_position USING btree (tenant_id);
CREATE INDEX idx_sm_ext_workorder_position_workorder ON public.ext_workorder_position USING btree (workorder_id);
-- The capacity read asks for every bay held at a location over a window.
CREATE INDEX idx_sm_ext_workorder_position_location_window
    ON public.ext_workorder_position USING btree (location_id, resource_type, assigned_at);

ALTER TABLE public.ext_workorder_position ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_workorder_position FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_workorder_position
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
