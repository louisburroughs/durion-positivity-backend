-- Create missing workorder-domain tables for Flyway/entity parity.
CREATE TABLE IF NOT EXISTS approval_configuration (
  id UUID PRIMARY KEY,
  updated_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  location_id UUID,
  customer_id UUID,
  approval_method VARCHAR(255) DEFAULT 'CLICK_CONFIRM',
  decline_expiry_days INTEGER DEFAULT 30,
  approval_window_days INTEGER,
  require_signature BOOLEAN DEFAULT FALSE,
  priority INTEGER DEFAULT 0
);
CREATE TABLE IF NOT EXISTS customer (
  id UUID PRIMARY KEY,
  updated_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  name VARCHAR(255),
  email VARCHAR(255),
  phone VARCHAR(255)
);
CREATE TABLE IF NOT EXISTS vehicle (
  id UUID PRIMARY KEY,
  updated_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  vin VARCHAR(255),
  make VARCHAR(255),
  model VARCHAR(255),
  "year" VARCHAR(255),
  license_plate VARCHAR(255),
  color VARCHAR(255)
);
CREATE TABLE IF NOT EXISTS estimate_sequence (
  id UUID PRIMARY KEY,
  updated_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  last_sequence_number BIGINT NOT NULL UNIQUE,
  version BIGINT
);
CREATE TABLE IF NOT EXISTS change_request (
  id UUID PRIMARY KEY,
  workorder_id UUID NOT NULL,
  requested_by_user_id VARCHAR(255) NOT NULL,
  requested_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  status VARCHAR(255) NOT NULL,
  description TEXT NOT NULL,
  is_emergency_exception BOOLEAN NOT NULL DEFAULT FALSE,
  exception_reason TEXT,
  approval_note TEXT,
  is_approval_gated BOOLEAN NOT NULL DEFAULT TRUE,
  supplemental_estimate_pdf_id BIGINT,
  approved_at TIMESTAMP,
  approved_by VARCHAR(255),
  declined_at TIMESTAMP,
  CONSTRAINT fk_change_request_workorder FOREIGN KEY (workorder_id) REFERENCES workorder(id)
);
CREATE TABLE IF NOT EXISTS workorder_service (
  id UUID PRIMARY KEY,
  updated_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  work_order_id UUID,
  service_entity_id UUID,
  technician_id UUID,
  description VARCHAR(500),
  quantity NUMERIC(19, 4),
  unit_price NUMERIC(19, 2),
  line_total NUMERIC(19, 2),
  tax_code VARCHAR(50),
  origin_estimate_item_id UUID,
  declined BOOLEAN DEFAULT FALSE,
  status VARCHAR(255) DEFAULT 'OPEN',
  change_request_id UUID,
  is_emergency_safety BOOLEAN DEFAULT FALSE,
  photo_evidence_url VARCHAR(255),
  emergency_notes TEXT,
  photo_not_possible BOOLEAN DEFAULT FALSE,
  customer_denial_acknowledged BOOLEAN,
  CONSTRAINT fk_workorder_service_workorder FOREIGN KEY (work_order_id) REFERENCES workorder(id),
  CONSTRAINT fk_workorder_service_change_request FOREIGN KEY (change_request_id) REFERENCES change_request(id),
  CONSTRAINT fk_workorder_service_estimate_item FOREIGN KEY (origin_estimate_item_id) REFERENCES estimate_item(id)
);