-- ADR-0044 §6 (#899): consumer-side eventing for the inventory availability and lead-time
-- replicas fed by inventory.events.v1. Replaces the synchronous InventoryClientImpl lookups
-- behind product-detail display.
CREATE TABLE processed_events (
    event_id character varying(36) NOT NULL,
    owner character varying(64) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id)
);
CREATE INDEX idx_processed_events_owner ON processed_events (owner, event_id);

CREATE TABLE ext_inventory_availability (
    aggregate_id uuid NOT NULL,
    stock_item_id varchar(255) NOT NULL,
    location_id uuid,
    on_hand_quantity integer NOT NULL,
    allocated_quantity integer NOT NULL,
    available_to_promise_quantity integer NOT NULL,
    unit_of_measure varchar(32),
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_inventory_availability_pkey PRIMARY KEY (aggregate_id)
);
CREATE UNIQUE INDEX idx_ext_inventory_availability_sku_location
    ON ext_inventory_availability (stock_item_id, location_id);

CREATE TABLE ext_product_lead_time (
    product_id uuid NOT NULL,
    min_days integer,
    max_days integer,
    display_text varchar(64),
    source varchar(64),
    confidence varchar(32),
    as_of timestamp(6) with time zone,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_product_lead_time_pkey PRIMARY KEY (product_id)
);
