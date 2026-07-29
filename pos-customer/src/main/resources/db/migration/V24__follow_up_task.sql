-- Story #1153: customer follow-up tasks.
-- Declined work and service-due reminders otherwise live as a note on a closed workorder that
-- nobody revisits. This gives them a queue with an owner, a due date, and a recorded outcome.
CREATE TABLE follow_up_task (
    task_id uuid NOT NULL,
    party_id uuid NOT NULL,
    vehicle_id uuid,
    type character varying(40) NOT NULL,
    due_date date,
    assigned_to character varying(255),
    status character varying(20) NOT NULL,
    source_workorder_id character varying(64),
    source_event_id character varying(64),
    reason character varying(500),
    outcome character varying(500),
    notes character varying(2000),
    -- CRM hands off to booking but never books; these record what the hand-off produced so the
    -- follow-up's value is measurable without this module owning scheduling.
    booked_workorder_id uuid,
    booked_appointment_id uuid,
    closed_at timestamp(6) with time zone,
    closed_by character varying(255),
    created_by character varying(255),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT follow_up_task_pkey PRIMARY KEY (task_id),
    CONSTRAINT follow_up_task_type_chk CHECK (type IN (
        'DECLINED_SERVICE_FOLLOWUP', 'SERVICE_DUE_REMINDER', 'FLEET_CHECKIN',
        'CAMPAIGN_RESPONSE', 'GENERAL')),
    CONSTRAINT follow_up_task_status_chk CHECK (status IN ('OPEN', 'DONE', 'DISMISSED')),
    -- A closed task must say what happened; a queue that empties without outcomes measures
    -- activity rather than result.
    CONSTRAINT follow_up_task_outcome_chk CHECK (status = 'OPEN' OR outcome IS NOT NULL)
);

CREATE INDEX idx_follow_up_task_party ON follow_up_task (party_id, status);
CREATE INDEX idx_follow_up_task_queue ON follow_up_task (status, due_date);
CREATE INDEX idx_follow_up_task_assignee ON follow_up_task (assigned_to, status);

-- Exactly one task per originating event, so a replayed declined-service fact cannot produce a
-- duplicate chase (the event-fed path lands with FI-3, #1133). Partial: CSR-raised tasks carry
-- no source event.
CREATE UNIQUE INDEX uq_follow_up_task_source_event
    ON follow_up_task (source_event_id)
    WHERE source_event_id IS NOT NULL;
