CREATE TABLE IF NOT EXISTS vehicle_entity (
  id UUID PRIMARY KEY,
  vehicle_type VARCHAR(31) NOT NULL,
  make VARCHAR(255),
  model VARCHAR(255),
  model_year INTEGER,
  vin VARCHAR(255),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS vehicle_records (
  vehicle_id UUID PRIMARY KEY,
  account_id UUID NOT NULL,
  vin VARCHAR(17) NOT NULL,
  vin_normalized VARCHAR(17) NOT NULL,
  unit_number VARCHAR(255) NOT NULL,
  description TEXT NOT NULL,
  license_plate VARCHAR(50),
  license_plate_jurisdiction VARCHAR(50),
  model_year INTEGER,
  make VARCHAR(255),
  model VARCHAR(255),
  trim VARCHAR(255),
  odometer JSONB,
  last_service_date TIMESTAMP,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  version BIGINT NOT NULL,
  CONSTRAINT uk_vehicle_records_vin_normalized UNIQUE (vin_normalized)
);
CREATE INDEX IF NOT EXISTS idx_vr_account_id ON vehicle_records (account_id);
CREATE INDEX IF NOT EXISTS idx_vr_vin_normalized ON vehicle_records (vin_normalized);
CREATE INDEX IF NOT EXISTS idx_vr_unit_number ON vehicle_records (unit_number);
CREATE INDEX IF NOT EXISTS idx_vr_license_plate ON vehicle_records (license_plate);
CREATE TABLE IF NOT EXISTS event_processing_log (
  log_id UUID PRIMARY KEY,
  event_id VARCHAR(255) NOT NULL,
  workorder_id VARCHAR(255) NOT NULL,
  vehicle_id VARCHAR(255) NOT NULL,
  status VARCHAR(50) NOT NULL,
  conflict_policy VARCHAR(50),
  details JSONB,
  processed_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_event_processing_log_event_id UNIQUE (event_id)
);
CREATE INDEX IF NOT EXISTS idx_epl_event_id ON event_processing_log (event_id);
CREATE INDEX IF NOT EXISTS idx_epl_workorder_id ON event_processing_log (workorder_id);
CREATE INDEX IF NOT EXISTS idx_epl_vehicle_id ON event_processing_log (vehicle_id);
CREATE INDEX IF NOT EXISTS idx_epl_status ON event_processing_log (status);
CREATE TABLE IF NOT EXISTS vehicle_care_preferences (
  id UUID PRIMARY KEY,
  vehicle_id UUID NOT NULL,
  preferences JSONB NOT NULL,
  service_notes TEXT,
  created_by_user_id UUID NOT NULL,
  updated_by_user_id UUID NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  version BIGINT NOT NULL,
  CONSTRAINT uk_vehicle_care_preferences_vehicle_id UNIQUE (vehicle_id),
  CONSTRAINT fk_vehicle_care_preferences_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle_records(vehicle_id)
);
CREATE INDEX IF NOT EXISTS idx_vcp_vehicle_id ON vehicle_care_preferences (vehicle_id);