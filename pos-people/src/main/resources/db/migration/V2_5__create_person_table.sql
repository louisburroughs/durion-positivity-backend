-- Create the person table before V3 which creates user_person_links with FK to person.
-- Columns added by V7 are pre-included; V7 uses ADD COLUMN IF NOT EXISTS -> safe no-ops.
CREATE TABLE IF NOT EXISTS person (
  id UUID PRIMARY KEY,
  first_name VARCHAR(255),
  last_name VARCHAR(255),
  legal_name VARCHAR(255),
  preferred_name VARCHAR(255),
  employee_number VARCHAR(255),
  status VARCHAR(50),
  hire_date DATE,
  termination_date DATE,
  contact_info_json TEXT,
  status_effective_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ,
  primary_email VARCHAR(255),
  secondary_email VARCHAR(255),
  username VARCHAR(255)
);
-- ElementCollection table for Person.phoneNumbers.
CREATE TABLE IF NOT EXISTS person_phone_numbers (
  person_id UUID NOT NULL,
  phone_numbers VARCHAR(255),
  CONSTRAINT fk_person_phone_numbers_person FOREIGN KEY (person_id) REFERENCES person(id)
);