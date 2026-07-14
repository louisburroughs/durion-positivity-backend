-- ADR-0044 §6 (#901): read-only pick replicas fed by inventory.pick-list.updated /
-- inventory.pick-task.updated / inventory.consumption.recorded facts on inventory.events.v1.
-- Replace the synchronous InventoryPickClient read path for the workorder pick flow.
CREATE TABLE ext_pick_list (
    pick_list_id uuid NOT NULL,
    workorder_id uuid,
    status varchar(64),
    priority integer NOT NULL DEFAULT 0,
    due_at timestamp(6) with time zone,
    pick_list_created_at timestamp(6) with time zone,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_pick_list_pkey PRIMARY KEY (pick_list_id)
);
CREATE INDEX idx_ext_pick_list_workorder ON ext_pick_list (workorder_id);

CREATE TABLE ext_pick_task (
    pick_task_id uuid NOT NULL,
    pick_list_id uuid,
    workorder_id uuid,
    sku_id uuid,
    location_id uuid,
    quantity_required integer NOT NULL DEFAULT 0,
    quantity_picked integer NOT NULL DEFAULT 0,
    quantity_consumed integer NOT NULL DEFAULT 0,
    status varchar(64),
    sort_order integer NOT NULL DEFAULT 0,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_pick_task_pkey PRIMARY KEY (pick_task_id)
);
CREATE INDEX idx_ext_pick_task_pick_list ON ext_pick_task (pick_list_id);
