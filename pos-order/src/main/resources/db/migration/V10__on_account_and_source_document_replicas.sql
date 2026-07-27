-- Wave 4 (odoo parity #1075/#1077): on-account tender gates and source-document replicas.

-- C4 (#1075): commercial-party fields on the customer replica (already carried by
-- customer.party.updated) + AR billing-terms replica fed by customer.billing-rules.updated
ALTER TABLE ext_customer ADD COLUMN party_type VARCHAR(32);
ALTER TABLE ext_customer ADD COLUMN requirements_met BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE ext_billing_rules (
  party_id UUID PRIMARY KEY,
  payment_terms VARCHAR(100),
  credit_limit NUMERIC(19, 4),
  credit_hold BOOLEAN,
  po_required BOOLEAN,
  aggregate_version BIGINT NOT NULL,
  synced_at TIMESTAMP NOT NULL
);

-- E1 (#1077): estimate/workorder replicas fed by workorder.events.v1 snapshots
-- (pos-workorder is a domain module; sync REST toward it is barred by ADR-0044)
CREATE TABLE ext_workorder (
  workorder_id UUID PRIMARY KEY,
  workorder_number VARCHAR(64),
  status VARCHAR(40),
  customer_id UUID,
  vehicle_id UUID,
  shop_id UUID,
  invoice_id UUID,
  aggregate_version BIGINT NOT NULL,
  synced_at TIMESTAMP NOT NULL
);

CREATE TABLE ext_workorder_line (
  line_id UUID PRIMARY KEY,
  workorder_id UUID NOT NULL,
  line_kind VARCHAR(16) NOT NULL,
  product_id UUID,
  description VARCHAR(512),
  quantity NUMERIC(19, 4),
  unit_price NUMERIC(19, 4),
  line_total NUMERIC(19, 4),
  declined BOOLEAN,
  returnable BOOLEAN
);
CREATE INDEX idx_ext_workorder_line_workorder ON ext_workorder_line (workorder_id);

CREATE TABLE ext_estimate (
  estimate_id UUID PRIMARY KEY,
  estimate_number VARCHAR(64),
  status VARCHAR(40),
  customer_id UUID,
  vehicle_id UUID,
  location_id UUID,
  aggregate_version BIGINT NOT NULL,
  synced_at TIMESTAMP NOT NULL
);

CREATE TABLE ext_estimate_line (
  item_id UUID PRIMARY KEY,
  estimate_id UUID NOT NULL,
  item_type VARCHAR(16),
  description VARCHAR(512),
  quantity NUMERIC(19, 4),
  unit_price NUMERIC(19, 4),
  line_total NUMERIC(19, 4),
  product_id UUID,
  service_id UUID,
  approval_status VARCHAR(32),
  deleted BOOLEAN
);
CREATE INDEX idx_ext_estimate_line_estimate ON ext_estimate_line (estimate_id);
