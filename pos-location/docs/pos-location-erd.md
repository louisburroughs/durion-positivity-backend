erDiagram
location_type {
UUID id
String name
String description
Instant created_at
Instant updated_at
}
location {
UUID id
String name
String normalized_name
String code
String status
String distance_unit
Instant created_at
Instant updated_at
String hr_location_id
String timezone
TEXT operating_hours
TEXT holiday_closures
Integer check_in_buffer_minutes
Integer cleanup_buffer_minutes
UUID default_staging_location_id
UUID default_quarantine_location_id
Long version
UUID geographical_location_id
String address_line1
String address_line2
String city
String state
String postal_code
String country
String mailing_address
String phone_number
Boolean is_active
UUID location_type_id
UUID responsible_person_id
}
bays {
UUID id
UUID location_id
String name
String normalized_name
String bay_type
String status
Integer max_concurrent_vehicles
TEXT service_capability_codes
Integer max_duty_class
String out_of_service_reason
String out_of_service_note
Instant expected_return_at
Integer display_order
Instant created_at
Instant last_modified_at
}
service_areas {
UUID id
String name
String description
Boolean active
Instant created_at
Instant updated_at
}
storage_location {
UUID id
String name
String barcode
String type
String status
UUID site_id
UUID parent_storage_location_id
TEXT capacity
TEXT temperature
Instant created_at
Instant updated_at
}
travel_buffer_policies {
UUID id
String name
String buffer_type
BigDecimal buffer_value
String notes
Instant created_at
Instant updated_at
}
mobile_units {
UUID id
String name
UUID base_location_id
String status
UUID travel_buffer_policy_id
String notes
String out_of_service_reason
String out_of_service_note
Instant expected_return_at
Integer max_duty_class
String unit_number
String vin
String license_plate
String plate_region
Instant created_at
Instant updated_at
UUID created_by
UUID updated_by
}
mobile_unit_coverage_rules {
UUID id
UUID mobile_unit_id
UUID service_area_id
String rule_type
Integer priority
Instant valid_from
Instant valid_to
BigDecimal max_distance_km
Instant created_at
Instant updated_at
}
location_parent {
UUID id
UUID parent_id
UUID child_id
String parent_type
Instant created_at
Instant updated_at
}
storage_location o|--o{ storage_location : parent_storage_location_id
mobile_units ||--o{ mobile_unit_coverage_rules : mobile_unit_id
service_areas o|--o{ mobile_unit_coverage_rules : service_area_id
