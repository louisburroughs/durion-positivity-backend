-- Create the location table before V3 which creates indexes on pre-existing columns here.
-- NOTE: Columns added by V6/V7/V8 are pre-included; those migrations use ADD COLUMN IF NOT EXISTS
--       and will safely no-op. V3 creates indexes on columns defined in this migration.
-- FK constraints to storage_location (V5) and location_type (V10) are in V11.
CREATE TABLE IF NOT EXISTS location (
  id UUID PRIMARY KEY,
  name VARCHAR(255),
  normalized_name VARCHAR(255),
  code VARCHAR(255) UNIQUE,
  status VARCHAR(50),
  created_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ,
  hr_location_id VARCHAR(255),
  timezone VARCHAR(100),
  operating_hours TEXT,
  holiday_closures TEXT,
  check_in_buffer_minutes INTEGER,
  cleanup_buffer_minutes INTEGER,
  default_staging_location_id UUID,
  default_quarantine_location_id UUID,
  version BIGINT,
  geographical_location_id UUID,
  address_line1 VARCHAR(255),
  address_line2 VARCHAR(255),
  city VARCHAR(255),
  state VARCHAR(255),
  postal_code VARCHAR(255),
  country VARCHAR(255),
  mailing_address VARCHAR(255),
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  location_type_id UUID,
  responsible_person_id BIGINT
);
-- Create the location_parent join table (FKs to location are safe - same migration).
CREATE TABLE IF NOT EXISTS location_parent (
  id UUID PRIMARY KEY,
  parent_id UUID,
  child_id UUID,
  parent_type VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_location_parent_child_type UNIQUE (child_id, parent_type),
  CONSTRAINT uq_location_parent_child_parent UNIQUE (child_id, parent_id),
  CONSTRAINT fk_location_parent_parent FOREIGN KEY (parent_id) REFERENCES location(id),
  CONSTRAINT fk_location_parent_child FOREIGN KEY (child_id) REFERENCES location(id)
);