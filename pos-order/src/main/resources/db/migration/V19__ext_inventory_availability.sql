-- CAP #1315 Phase 2: pos-order needs its own availability replica to gate checkout.
--
-- pos-inventory is a Domain module (ADR-0044), so this module cannot ask it for stock at
-- checkout time. It instead keeps a read-only replica fed by inventory.availability.updated on
-- inventory.events.v1 — the same feed and the same shape pos-catalog already maintains for
-- product-detail display. Nothing in this module writes this table except the event consumer.
--
-- The replica answers "is there enough at the selling location right now?" A short line is
-- admitted as a backorder rather than rejected, so a stale replica delays a backorder flag by a
-- few seconds; it never blocks a sale. Authoritative allocation still happens in pos-inventory,
-- which reads its own ledger when it handles the reservation-request command.

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

-- Lookups at checkout are always (sku, selling location).
CREATE UNIQUE INDEX idx_ext_inventory_availability_sku_location
    ON ext_inventory_availability (stock_item_id, location_id);

-- Staleness is observable per AC: the health contributor asks for the newest updated_at on every
-- scrape, so that one aggregate must not degrade into a sequential scan as the replica grows.
CREATE INDEX IF NOT EXISTS idx_ext_inventory_availability_updated_at
    ON ext_inventory_availability (updated_at DESC);
