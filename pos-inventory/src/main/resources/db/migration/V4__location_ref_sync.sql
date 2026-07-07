-- CAP-214 #40: local reference table for locations synced from pos-location
-- plus the audit log of sync runs consumed by the location-sync UI.

CREATE TABLE location_ref (
    location_ref_id uuid NOT NULL,
    location_id uuid NOT NULL,
    hr_location_id character varying(255),
    name character varying(255) NOT NULL,
    code character varying(255),
    status character varying(50) NOT NULL,
    timezone character varying(100),
    is_active boolean NOT NULL,
    source_updated_at timestamp(6) with time zone,
    deactivated_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    CONSTRAINT location_ref_pkey PRIMARY KEY (location_ref_id),
    CONSTRAINT location_ref_location_id_key UNIQUE (location_id)
);

CREATE INDEX idx_location_ref_is_active ON location_ref (is_active);
CREATE INDEX idx_location_ref_hr_location_id ON location_ref (hr_location_id);

CREATE TABLE location_sync_log (
    sync_log_id uuid NOT NULL,
    sync_run_id uuid NOT NULL,
    scope character varying(20) NOT NULL,
    outcome character varying(30) NOT NULL,
    correlation_id character varying(255),
    idempotency_key character varying(255),
    triggered_by character varying(255),
    location_id uuid,
    hr_location_id character varying(255),
    payload character varying(4000),
    error_message character varying(2000),
    locations_processed integer,
    locations_created integer,
    locations_updated integer,
    locations_unchanged integer,
    locations_failed integer,
    started_at timestamp(6) with time zone,
    completed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT location_sync_log_pkey PRIMARY KEY (sync_log_id),
    CONSTRAINT location_sync_log_scope_check CHECK (((scope)::text = ANY ((ARRAY['RUN'::character varying, 'RECORD'::character varying])::text[]))),
    CONSTRAINT location_sync_log_outcome_check CHECK (((outcome)::text = ANY ((ARRAY['OK'::character varying, 'PARTIAL'::character varying, 'FAILED'::character varying, 'INVALID_PAYLOAD'::character varying])::text[])))
);

CREATE INDEX idx_location_sync_log_run_id ON location_sync_log (sync_run_id);
CREATE INDEX idx_location_sync_log_outcome ON location_sync_log (outcome);
CREATE INDEX idx_location_sync_log_created_at ON location_sync_log (created_at);
CREATE INDEX idx_location_sync_log_idempotency_key ON location_sync_log (idempotency_key);
