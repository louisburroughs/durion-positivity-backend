-- ADR-0044 §6 (#899): consumer-side eventing for the inventory on-hand replica fed by
-- inventory.storage-location-on-hand.updated facts on inventory.events.v1. Replaces the
-- synchronous LocationInventoryInquiryClient guard ("no decommission while stocked").
CREATE TABLE processed_events (
    event_id character varying(36) NOT NULL,
    owner character varying(64) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id)
);
CREATE INDEX idx_processed_events_owner ON processed_events (owner, event_id);

CREATE TABLE ext_storage_location_on_hand (
    storage_location_id uuid NOT NULL,
    on_hand_quantity integer NOT NULL,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_storage_location_on_hand_pkey PRIMARY KEY (storage_location_id)
);
