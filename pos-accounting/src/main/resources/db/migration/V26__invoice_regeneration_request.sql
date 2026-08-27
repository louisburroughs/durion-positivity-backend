-- Issue #1537 (D1): tracks async invoice-regeneration commands published to
-- workorder.commands.v1 so the regeneration endpoint can report a genuine terminal
-- state instead of returning PENDING forever, and so a repeat call carrying an
-- idempotency key that already completed can short-circuit without re-publishing.
--
-- Rows are created PENDING when the command is published (WorkorderCommandPublisher
-- generates and stamps commandId here) and resolved to COMPLETED by
-- WorkorderEventsListener once a workorder.events.v1 fact for the same workorder
-- carries the resulting invoiceId.
CREATE TABLE invoice_regeneration_request (
    id uuid NOT NULL,
    workorder_id uuid NOT NULL,
    command_id uuid NOT NULL,
    idempotency_key character varying(255),
    status character varying(20) NOT NULL,
    result_invoice_id uuid,
    requested_by character varying(255),
    requested_at timestamp(6) with time zone NOT NULL,
    resolved_at timestamp(6) with time zone,
    CONSTRAINT invoice_regeneration_request_pkey PRIMARY KEY (id)
);

-- Partial: idempotency_key is optional, and only supplied keys must be unique.
CREATE UNIQUE INDEX uq_invoice_regeneration_request_idempotency_key
    ON invoice_regeneration_request (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_invoice_regeneration_request_workorder_status
    ON invoice_regeneration_request (workorder_id, status);
