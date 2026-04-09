CREATE TABLE IF NOT EXISTS manufacturer (
  id UUID PRIMARY KEY,
  name VARCHAR(255),
  cache_timestamp TIMESTAMP
);
CREATE TABLE IF NOT EXISTS make (
  id UUID PRIMARY KEY,
  name VARCHAR(255),
  manufacturer_id UUID,
  cache_timestamp TIMESTAMP,
  CONSTRAINT fk_make_manufacturer FOREIGN KEY (manufacturer_id) REFERENCES manufacturer(id)
);
CREATE INDEX IF NOT EXISTS idx_make_manufacturer_id ON make (manufacturer_id);
CREATE TABLE IF NOT EXISTS model (
  id UUID PRIMARY KEY,
  name VARCHAR(255),
  make_id UUID,
  cache_timestamp TIMESTAMP,
  CONSTRAINT fk_model_make FOREIGN KEY (make_id) REFERENCES make(id)
);
CREATE INDEX IF NOT EXISTS idx_model_make_id ON model (make_id);
CREATE TABLE IF NOT EXISTS vehicle_type (
  id UUID PRIMARY KEY,
  make_id UUID,
  vehicle_type_name VARCHAR(255),
  vehicle_type_id VARCHAR(255),
  cache_timestamp TIMESTAMP,
  CONSTRAINT fk_vehicle_type_make FOREIGN KEY (make_id) REFERENCES make(id)
);
CREATE INDEX IF NOT EXISTS idx_vehicle_type_make_id ON vehicle_type (make_id);
CREATE TABLE IF NOT EXISTS vehicle_variable (
  id UUID PRIMARY KEY,
  name VARCHAR(255),
  description VARCHAR(255),
  cache_timestamp TIMESTAMP
);
CREATE TABLE IF NOT EXISTS vehicle_variable_value (
  id UUID PRIMARY KEY,
  variable_id UUID,
  value VARCHAR(255),
  value_id VARCHAR(255),
  cache_timestamp TIMESTAMP,
  CONSTRAINT fk_vehicle_variable_value_variable FOREIGN KEY (variable_id) REFERENCES vehicle_variable(id)
);
CREATE INDEX IF NOT EXISTS idx_vehicle_variable_value_variable_id ON vehicle_variable_value (variable_id);