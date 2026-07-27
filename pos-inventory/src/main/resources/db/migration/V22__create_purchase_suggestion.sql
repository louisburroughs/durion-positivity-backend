-- odoo-parity F4 (#1044, spec F4, plan D-3): purchase suggestions.
--
-- A purchase_suggestion row is scan-generated ADVICE to buy stock for a replenishment
-- policy whose preferred source is PURCHASE. Lifecycle SUGGESTED -> ACCEPTED ->
-- CONVERTED | DISMISSED; ACCEPT is human-mandatory (D-3 / OQ-3: the scan never creates
-- purchase orders), and conversion produces a DRAFT PO through the existing PO service,
-- so the normal PO approval workflow forms a second human spend gate.
--
-- Vendor columns are nullable on purpose: a policy with no usable vendor-feed data still
-- yields a (non-convertible) suggestion whose selection_reason explains the gap, and
-- distributor feeds carry no price (unit_cost_minor NULL).
--
-- vendor_pack_size is captured at suggestion time so conversion can express the PO line
-- in the vendor's pack UoM (B2 document UoM rule) without re-reading feed data that may
-- have changed since the suggestion was computed.
CREATE TABLE purchase_suggestion (
    suggestion_id               uuid NOT NULL,
    policy_id                   uuid NOT NULL,
    item_sku                    character varying(255) NOT NULL,
    location_id                 uuid NOT NULL,
    suggested_quantity          integer NOT NULL,
    suggested_vendor_type       character varying(20),
    vendor_ref_id               uuid,
    unit_cost_minor             bigint,
    unit_cost_currency          character varying(3),
    vendor_pack_size            integer,
    lead_time_days              integer,
    earliest_expected_date      date,
    selection_reason            character varying(2000) NOT NULL,
    status                      character varying(20) NOT NULL,
    dismissal_reason            character varying(255),
    converted_purchase_order_id uuid,
    created_at                  timestamp(6) with time zone NOT NULL,
    updated_at                  timestamp(6) with time zone NOT NULL,
    CONSTRAINT purchase_suggestion_pkey PRIMARY KEY (suggestion_id),
    CONSTRAINT purchase_suggestion_status_check
        CHECK (status IN ('SUGGESTED', 'ACCEPTED', 'CONVERTED', 'DISMISSED')),
    CONSTRAINT purchase_suggestion_vendor_type_check
        CHECK (suggested_vendor_type IS NULL OR suggested_vendor_type IN ('MANUFACTURER', 'DISTRIBUTOR')),
    CONSTRAINT purchase_suggestion_quantity_check CHECK (suggested_quantity > 0),
    CONSTRAINT purchase_suggestion_pack_size_check
        CHECK (vendor_pack_size IS NULL OR vendor_pack_size > 0)
);

-- Open-suggestion guard + refresh lookup: one open (SUGGESTED/ACCEPTED) suggestion per
-- policy is enforced in the service layer, mirroring the replenishment-task guard.
CREATE INDEX idx_purchase_suggestion_policy_status ON purchase_suggestion (policy_id, status);

-- Netting lookup: open suggestion quantities join the replenishment in-progress sum
-- (the F2 seam), keyed like the task netting query by SKU + destination location.
CREATE INDEX idx_purchase_suggestion_sku_location_status
    ON purchase_suggestion (item_sku, location_id, status);
