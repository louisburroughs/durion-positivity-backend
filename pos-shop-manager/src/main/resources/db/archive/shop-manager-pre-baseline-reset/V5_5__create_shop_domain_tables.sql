-- Create shop table before V6 which does ALTER TABLE shop ADD COLUMN timezone.
-- NOTE: timezone is intentionally omitted here - V6 adds it.
CREATE TABLE IF NOT EXISTS shop (
  id UUID PRIMARY KEY,
  name VARCHAR(255),
  address VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
-- Create bay table.
CREATE TABLE IF NOT EXISTS bay (
  id UUID PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
-- Create mobile_unit table.
CREATE TABLE IF NOT EXISTS mobile_unit (
  id UUID PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
-- Create technician table (FK to shop - both in same migration).
CREATE TABLE IF NOT EXISTS technician (
  id UUID PRIMARY KEY,
  person_id BIGINT,
  shop_id UUID,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT fk_technician_shop FOREIGN KEY (shop_id) REFERENCES shop(id)
);
-- Create certification table (FK to technician - both in same migration).
CREATE TABLE IF NOT EXISTS certification (
  id UUID PRIMARY KEY,
  ase_code VARCHAR(255),
  description VARCHAR(255),
  technician_id UUID,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT fk_certification_technician FOREIGN KEY (technician_id) REFERENCES technician(id)
);
-- Create shop_qualification table (FK to shop - both in same migration).
CREATE TABLE IF NOT EXISTS shop_qualification (
  id UUID PRIMARY KEY,
  name VARCHAR(255),
  description VARCHAR(255),
  shop_id UUID,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT fk_shop_qualification_shop FOREIGN KEY (shop_id) REFERENCES shop(id)
);
-- Create shop_service table (FK to shop - both in same migration).
CREATE TABLE IF NOT EXISTS shop_service (
  id UUID PRIMARY KEY,
  service_entity_id BIGINT,
  shop_id UUID,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT fk_shop_service_shop FOREIGN KEY (shop_id) REFERENCES shop(id)
);
