CREATE TABLE IF NOT EXISTS travel_buffer_policies (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    buffer_type VARCHAR(30),
    buffer_value DECIMAL(10,2),
    notes TEXT,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS service_areas (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS mobile_units (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    base_location_id UUID,
    status VARCHAR(20) NOT NULL,
    travel_buffer_policy_id UUID,
    notes TEXT,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    created_by UUID,
    updated_by UUID,
    CONSTRAINT fk_mobile_units_base_location
        FOREIGN KEY (base_location_id) REFERENCES location(id),
    CONSTRAINT fk_mobile_units_travel_policy
        FOREIGN KEY (travel_buffer_policy_id) REFERENCES travel_buffer_policies(id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_mobile_unit_base_location_lower_name
    ON mobile_units (base_location_id, lower(name));

CREATE TABLE IF NOT EXISTS mobile_unit_capabilities (
    mobile_unit_id UUID NOT NULL,
    service_capability_id UUID NOT NULL,
    PRIMARY KEY (mobile_unit_id, service_capability_id),
    CONSTRAINT fk_mobile_unit_capabilities_mobile_unit
        FOREIGN KEY (mobile_unit_id) REFERENCES mobile_units(id)
);

CREATE TABLE IF NOT EXISTS service_area_postal_codes (
    service_area_id UUID NOT NULL,
    postal_code VARCHAR(20) NOT NULL,
    country_code CHAR(2) NOT NULL,
    PRIMARY KEY (service_area_id, postal_code, country_code),
    CONSTRAINT fk_service_area_postal_codes_service_area
        FOREIGN KEY (service_area_id) REFERENCES service_areas(id)
);

CREATE TABLE IF NOT EXISTS mobile_unit_coverage_rules (
    id UUID PRIMARY KEY,
    mobile_unit_id UUID NOT NULL,
    service_area_id UUID,
    rule_type VARCHAR(20),
    priority INT NOT NULL,
    valid_from DATE,
    valid_to DATE,
    max_distance DECIMAL(10,2),
    CONSTRAINT fk_mobile_unit_coverage_rules_mobile_unit
        FOREIGN KEY (mobile_unit_id) REFERENCES mobile_units(id),
    CONSTRAINT fk_mobile_unit_coverage_rules_service_area
        FOREIGN KEY (service_area_id) REFERENCES service_areas(id)
);
