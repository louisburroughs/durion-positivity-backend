-- ADR-0044 §6 (#897): read-only workorder replica fed by workorder.workorder.updated facts
-- on workorder.events.v1. Replaces the synchronous WorkorderReferenceClient number lookup
-- used by invoice search (free-text number translation + result-row enrichment).
CREATE TABLE ext_workorder (
    workorder_id uuid NOT NULL,
    workorder_number varchar(64),
    status varchar(64),
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_workorder_pkey PRIMARY KEY (workorder_id)
);
CREATE INDEX idx_ext_workorder_number ON ext_workorder (workorder_number);
