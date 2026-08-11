-- FI-3 (#1133): CRM service-history read model, fed by workorder.events.v1.
-- pos-workorder owns the workorder facts (ADR-0044 R6); nothing here writes this table except the
-- WorkorderEventsListener. One row per completed workorder gives CRM the service-history signal it
-- lacked, powering win-back / service-due segmentation without a synchronous call to pos-workorder.
CREATE TABLE service_history (
    service_history_id uuid NOT NULL,
    party_id uuid NOT NULL,
    vehicle_id uuid,
    source_workorder_id uuid NOT NULL,
    workorder_number character varying(64),
    completed_at timestamp(6) with time zone NOT NULL,
    amount numeric(19, 2),
    -- Originating envelope id; the ingestion idempotency backstop independent of processed_events,
    -- so a replayed completion fact cannot create a second history row.
    source_event_id character varying(64) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT service_history_pkey PRIMARY KEY (service_history_id)
);

-- Segment resolution reads the most recent completion per party (last-service age, service-due).
CREATE INDEX idx_service_history_party ON service_history (party_id, completed_at DESC);
CREATE INDEX idx_service_history_vehicle ON service_history (vehicle_id, completed_at DESC);
CREATE UNIQUE INDEX uq_service_history_source_event ON service_history (source_event_id);
