CREATE TABLE IF NOT EXISTS manufacturer (
  id UUID PRIMARY KEY,
  name VARCHAR(255),
  cache_timestamp TIMESTAMP,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS make (
  id UUID PRIMARY KEY,
  name VARCHAR(255),
  manufacturer_id UUID,
  cache_timestamp TIMESTAMP,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_make_manufacturer FOREIGN KEY (manufacturer_id) REFERENCES manufacturer(id)
);
CREATE TABLE IF NOT EXISTS model (
  id UUID PRIMARY KEY,
  name VARCHAR(255),
  make_id UUID,
  cache_timestamp TIMESTAMP,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_model_make FOREIGN KEY (make_id) REFERENCES make(id)
);
CREATE TABLE IF NOT EXISTS vehicle_type (
  id UUID PRIMARY KEY,
  make_id UUID,
  vehicle_type_name VARCHAR(255),
  vehicle_type_id VARCHAR(255),
  cache_timestamp TIMESTAMP,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_vehicle_type_make FOREIGN KEY (make_id) REFERENCES make(id)
);
CREATE TABLE IF NOT EXISTS vehicle_variable (
  id UUID PRIMARY KEY,
  name VARCHAR(255),
  description VARCHAR(255),
  cache_timestamp TIMESTAMP,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS vehicle_variable_value (
  id UUID PRIMARY KEY,
  variable_id UUID,
  variable_value VARCHAR(255),
  value_id VARCHAR(255),
  cache_timestamp TIMESTAMP,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_vehicle_variable_value_variable FOREIGN KEY (variable_id) REFERENCES vehicle_variable(id)
);
CREATE TABLE IF NOT EXISTS vehicle_applicability_hints (
  hint_id UUID PRIMARY KEY,
  product_id UUID NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  created_by VARCHAR(255),
  updated_by VARCHAR(255)
);
CREATE TABLE IF NOT EXISTS fitment_tags (
  id UUID PRIMARY KEY,
  tag_type VARCHAR(255) NOT NULL,
  tag_value VARCHAR(255) NOT NULL,
  hint_id UUID NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_fitment_tags_hint FOREIGN KEY (hint_id) REFERENCES vehicle_applicability_hints(hint_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS part_fitment_entity (
  id UUID PRIMARY KEY,
  part_number_id BIGINT,
  vehicle_manufacturer_id UUID,
  vehicle_make_id UUID,
  vehicle_model_id UUID,
  vehicle_type_id UUID,
  vehicle_year VARCHAR(255),
  engine_type VARCHAR(255),
  submodel VARCHAR(255),
  notes VARCHAR(255),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_part_fitment_manufacturer FOREIGN KEY (vehicle_manufacturer_id) REFERENCES manufacturer(id),
  CONSTRAINT fk_part_fitment_make FOREIGN KEY (vehicle_make_id) REFERENCES make(id),
  CONSTRAINT fk_part_fitment_model FOREIGN KEY (vehicle_model_id) REFERENCES model(id),
  CONSTRAINT fk_part_fitment_vehicle_type FOREIGN KEY (vehicle_type_id) REFERENCES vehicle_type(id)
);
CREATE TABLE IF NOT EXISTS part_fitment_entity_vehicle_variable_values (
  part_fitment_entity_id UUID NOT NULL,
  vehicle_variable_values_id UUID NOT NULL,
  CONSTRAINT fk_part_fitment_entity_vehicle_values_fitment FOREIGN KEY (part_fitment_entity_id) REFERENCES part_fitment_entity(id) ON DELETE CASCADE,
  CONSTRAINT fk_part_fitment_entity_vehicle_values_value FOREIGN KEY (vehicle_variable_values_id) REFERENCES vehicle_variable_value(id)
);
CREATE INDEX IF NOT EXISTS idx_make_manufacturer_id ON make (manufacturer_id);
CREATE INDEX IF NOT EXISTS idx_model_make_id ON model (make_id);
CREATE INDEX IF NOT EXISTS idx_vehicle_type_make_id ON vehicle_type (make_id);
CREATE INDEX IF NOT EXISTS idx_vehicle_variable_value_variable_id ON vehicle_variable_value (variable_id);
CREATE INDEX IF NOT EXISTS idx_fitment_tags_hint_id ON fitment_tags (hint_id);
CREATE INDEX IF NOT EXISTS idx_part_fitment_vehicle_values_fitment_id ON part_fitment_entity_vehicle_variable_values (part_fitment_entity_id);
CREATE INDEX IF NOT EXISTS idx_part_fitment_vehicle_values_value_id ON part_fitment_entity_vehicle_variable_values (vehicle_variable_values_id);