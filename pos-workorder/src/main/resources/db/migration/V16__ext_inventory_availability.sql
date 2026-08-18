-- CAP #1315 Phase 3: pos-workorder needs its own availability replica to gate part issue.
--
-- pos-inventory is a Domain module (ADR-0044), so this module cannot ask it for stock when a
-- technician asks for a part. It instead keeps a read-only replica fed by
-- inventory.availability.updated on inventory.events.v1 — the same feed pos-order and pos-catalog
-- already maintain. Nothing in this module writes this table except the event consumer.
--
-- Scope is the servicing site: (stock_item_id, workorder.shop_id). shop_id holds a Location id —
-- WorkorderServiceImpl populates shop_id and location_id from the same resolved location — so it
-- is the same identifier space the availability fact is keyed by. Stock at a sibling site is not
-- available here until someone transfers it.

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

-- The gate always asks (stock item, servicing site).
CREATE UNIQUE INDEX idx_ext_inventory_availability_item_location
    ON ext_inventory_availability (stock_item_id, location_id);

-- The staleness contributor asks for the newest updated_at on every scrape.
CREATE INDEX idx_ext_inventory_availability_updated_at
    ON ext_inventory_availability (updated_at DESC);

-- The part carries what pos-inventory decided about it.
--
-- The gate reads a replica that trails the owner's ledger, so an issue is recorded on a
-- provisional answer. When the authoritative outcome disagrees the issue is reversed, and the
-- reversal has to be attributable: without these columns a reversed part would carry no trace of
-- which reservation caused it or which backorder now covers it, and the shop would see a quantity
-- move with no explanation.
ALTER TABLE workorder_part ADD COLUMN reservation_id UUID;
ALTER TABLE workorder_part ADD COLUMN backorder_id UUID;
