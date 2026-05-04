erDiagram
location_type {
UUID id
String name
String description
Instant created_at
Instant updated_at
}
bays {
UUID id
UUID location_id
String name
String normalized_name
String bay_type
String status
Integer max_concurrent_vehicles
TEXT service_capability_ids
TEXT skill_requirement_ids
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
service_location_capabilities {
UUID id
String code
String name
Boolean active
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
Date valid_from
Date valid_to
BigDecimal max_distance
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
