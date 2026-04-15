-- Create the person table (referenced by FK in V3, ALTER in V7).
CREATE TABLE IF NOT EXISTS person (
  id UUID PRIMARY KEY,
  first_name VARCHAR(255),
  last_name VARCHAR(255),
  legal_name VARCHAR(255),
  preferred_name VARCHAR(255),
  employee_number VARCHAR(255),
  status VARCHAR(255),
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
-- Create the ElementCollection table for Person.phoneNumbers.
CREATE TABLE IF NOT EXISTS person_phone_numbers (
  person_id UUID NOT NULL,
  phone_numbers VARCHAR(255),
  CONSTRAINT fk_person_phone_numbers_person FOREIGN KEY (person_id) REFERENCES person (id)
);