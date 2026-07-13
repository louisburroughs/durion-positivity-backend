-- ADR-0044 §6 (#892): event-driven location replicas fed by location.events.v1, replacing the
-- synchronous LocationRosterClient / SiteDefaultsClient / StorageLocationTopologyClient /
-- StorageLocationValidationClient edges toward pos-location.

-- Consumer idempotency + manifest reconciliation source (Phase 3.4 pattern, #886).
CREATE TABLE processed_events (
    event_id character varying(36) NOT NULL,
    owner character varying(64) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id)
);
CREATE INDEX idx_processed_events_owner ON processed_events (owner, event_id);

-- location_ref becomes the event-fed roster replica: site defaults + stale guard.
ALTER TABLE location_ref ADD COLUMN default_staging_location_id uuid;
ALTER TABLE location_ref ADD COLUMN default_quarantine_location_id uuid;
ALTER TABLE location_ref ADD COLUMN aggregate_version bigint NOT NULL DEFAULT 0;

-- Typed location-parent edges carried on location.location.updated facts; one parent per
-- (child, parentType) upstream, replaced wholesale per fact.
CREATE TABLE ext_location_parent (
    child_id uuid NOT NULL,
    parent_type character varying(64) NOT NULL,
    parent_id uuid NOT NULL,
    CONSTRAINT ext_location_parent_pkey PRIMARY KEY (child_id, parent_type)
);
CREATE INDEX idx_ext_location_parent_parent ON ext_location_parent (parent_id, parent_type);

-- Storage-location replica serving topology, descendants, and putaway/reservation validation.
CREATE TABLE ext_storage_location (
    storage_location_id uuid NOT NULL,
    site_id uuid,
    name character varying(255),
    barcode character varying(255),
    type character varying(64),
    status character varying(64),
    parent_storage_location_id uuid,
    max_unit_capacity integer,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_storage_location_pkey PRIMARY KEY (storage_location_id)
);
CREATE INDEX idx_ext_storage_location_site ON ext_storage_location (site_id);

-- The manual sync endpoint now performs an administrative re-emit (async repair).
ALTER TABLE location_sync_log DROP CONSTRAINT location_sync_log_outcome_check;
ALTER TABLE location_sync_log ADD CONSTRAINT location_sync_log_outcome_check
    CHECK (outcome IN ('OK', 'PARTIAL', 'FAILED', 'INVALID_PAYLOAD', 'REQUESTED'));
