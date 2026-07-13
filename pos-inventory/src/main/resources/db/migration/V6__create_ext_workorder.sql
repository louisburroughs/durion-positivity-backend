-- ADR-0044 §6 (#897): read-only workorder replicas fed by workorder.workorder.updated facts
-- on workorder.events.v1. Replaces the synchronous WorkorderValidationClient line lookup for
-- receiving/putaway validation.
CREATE TABLE ext_workorder (
    workorder_id uuid NOT NULL,
    workorder_number varchar(64),
    status varchar(64),
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_workorder_pkey PRIMARY KEY (workorder_id)
);

CREATE TABLE ext_workorder_part (
    workorder_line_id uuid NOT NULL,
    workorder_id uuid NOT NULL,
    product_entity_id uuid,
    quantity numeric(19, 4),
    CONSTRAINT ext_workorder_part_pkey PRIMARY KEY (workorder_line_id)
);
CREATE INDEX idx_ext_workorder_part_workorder ON ext_workorder_part (workorder_id);
