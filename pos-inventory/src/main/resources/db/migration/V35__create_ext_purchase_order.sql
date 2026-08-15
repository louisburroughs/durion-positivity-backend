-- CAP-320 (#1333): local projection of the purchase order (ADR-0044 R3, ADR-0049 amendment 2026-08-15).
--
-- The commercial purchase order moves to pos-order. Receiving, availability-to-promise, replenishment
-- forecasting and traceability all read it and all stay here, so they need a local read path: ADR-0044
-- R1 forbids the synchronous call that would otherwise answer it, and R3 makes an event-fed replica
-- the sanctioned one. This table is that replica, written only by the order.events.v1 consumer.
--
-- READ-ONLY BY CONSTRUCTION. No receiving flow may write ordered quantities, prices or approval state
-- here; a change to what was ordered goes through the owner. Received quantities are pos-inventory's
-- own fact and stay in its own tables — the open quantity below is what the owner last told us, not a
-- number this module maintains.
--
-- WHY THIS IS NOT JUST A RECEIVING CONVENIENCE. ExpectedSupplyServiceImpl sums open quantity for a SKU
-- filtered by order status and ship-to site and bounded by expected delivery date. That figure is
-- availability-to-promise: incoming supply a customer is quoted against. A missing or stale row here
-- does not degrade a screen, it changes a promise — and it errs optimistically, offering stock that
-- was never ordered. Hence the completeness log alongside it rather than trust that every event
-- arrived.
--
-- Written to be H2(MODE=PostgreSQL)-compatible, matching the other ext_* replicas in this module.

CREATE TABLE ext_purchase_order (
    purchase_order_id uuid NOT NULL,
    po_number character varying(255) NOT NULL,
    vendor_id uuid NOT NULL,
    status character varying(32) NOT NULL,
    ship_to_location_id uuid,
    -- Null means the order carries no promised delivery date. The horizon-bounded supply query
    -- deliberately excludes such orders rather than treating them as arriving today, so this column
    -- must stay nullable and must never be defaulted on the way in.
    expected_delivery_date date,
    currency character varying(8),
    grand_total_minor bigint,
    aggregate_version bigint DEFAULT 0 NOT NULL,
    occurred_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_purchase_order_pkey PRIMARY KEY (purchase_order_id)
);

-- The supply query filters on status and site before summing, so that pair leads the index.
CREATE INDEX idx_ext_po_status_site ON ext_purchase_order (status, ship_to_location_id);
CREATE INDEX idx_ext_po_expected_delivery ON ext_purchase_order (expected_delivery_date);

CREATE TABLE ext_purchase_order_line (
    line_id uuid NOT NULL,
    purchase_order_id uuid NOT NULL,
    line_number integer NOT NULL,
    sku_id uuid NOT NULL,
    -- Two quantities, and the difference is the contract. ordered_quantity is what was ordered and
    -- does not move as goods arrive; open_quantity is what is still outstanding. ATP sums the open
    -- figure, because stock already in the building is counted by the ledger and adding the ordered
    -- figure would count it twice.
    ordered_quantity numeric(19,4) NOT NULL,
    open_quantity numeric(19,4) NOT NULL,
    unit_cost_minor bigint,
    description character varying(255),
    CONSTRAINT ext_purchase_order_line_pkey PRIMARY KEY (line_id),
    CONSTRAINT fk_ext_po_line_order FOREIGN KEY (purchase_order_id)
        REFERENCES ext_purchase_order (purchase_order_id) ON DELETE CASCADE
);

-- The supply query starts from a SKU and joins to its orders, so sku_id leads here.
CREATE INDEX idx_ext_po_line_sku ON ext_purchase_order_line (sku_id);
CREATE INDEX idx_ext_po_line_order ON ext_purchase_order_line (purchase_order_id);

-- Completeness, not just currency. A replica feeding a promise has to be able to say when it is
-- missing something rather than quietly summing less. One row per order the consumer has ever seen,
-- carrying the highest version applied, so a gap between what the owner published and what was
-- applied is a query rather than an inference.
CREATE TABLE ext_purchase_order_receipt (
    purchase_order_id uuid NOT NULL,
    applied_version bigint NOT NULL,
    applied_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_purchase_order_receipt_pkey PRIMARY KEY (purchase_order_id)
);
