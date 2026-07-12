-- ADR-0044 §6 / #843: read-only vehicle replica fed by vehicle.events.v1.
-- pos-vehicle-inventory owns these facts; nothing in this module may write the
-- table except the event consumer.
CREATE TABLE ext_vehicle (
    vehicle_id uuid NOT NULL,
    account_id uuid NOT NULL,
    vin character varying(17) NOT NULL,
    vin_normalized character varying(17) NOT NULL,
    unit_number character varying(255),
    description text,
    license_plate character varying(50),
    license_plate_jurisdiction character varying(50),
    model_year integer,
    make character varying(255),
    model character varying(255),
    trim character varying(255),
    is_active boolean NOT NULL,
    vehicle_created_at timestamp(6) with time zone,
    vehicle_updated_at timestamp(6) with time zone,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_vehicle_pkey PRIMARY KEY (vehicle_id)
);

CREATE INDEX idx_ext_vehicle_account ON ext_vehicle (account_id);
CREATE INDEX idx_ext_vehicle_vin_normalized ON ext_vehicle (vin_normalized);

-- ADR-0044 §4: idempotency log for replica consumers, keyed by envelope eventId.
-- The owner column scopes reconciliation-manifest window scans per producing domain,
-- so one owner's manifest math never sees another owner's eventIds.
CREATE TABLE processed_events (
    event_id character varying(36) NOT NULL,
    owner character varying(64) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id)
);

CREATE INDEX idx_processed_events_owner_event ON processed_events (owner, event_id);

-- Superseded by ext_vehicle: the workorder-event-fed projection duplicated vehicle
-- facts that pos-vehicle-inventory owns (ADR-0044 R6).
DROP TABLE IF EXISTS vehicle_projection;
