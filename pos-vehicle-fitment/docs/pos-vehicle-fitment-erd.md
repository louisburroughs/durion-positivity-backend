erDiagram
	manufacturer {
		UUID id
		String name
		LocalDateTime cacheTimestamp
		LocalDateTime created_at
		LocalDateTime updated_at
	}
	make {
		UUID id
		String name
		LocalDateTime cacheTimestamp
		LocalDateTime created_at
		LocalDateTime updated_at
	}
	model {
		UUID id
		String name
		LocalDateTime cacheTimestamp
		LocalDateTime created_at
		LocalDateTime updated_at
	}
	vehicle_type {
		UUID id
		UUID make_id
		String vehicleTypeName
		String vehicleTypeId
		LocalDateTime cacheTimestamp
		LocalDateTime created_at
		LocalDateTime updated_at
	}
	vehicle_variable {
		UUID id
		String name
		String description
		LocalDateTime cacheTimestamp
		LocalDateTime created_at
		LocalDateTime updated_at
	}
	vehicle_variable_value {
		UUID id
		UUID variable_id
		String variable_value
		String valueId
		LocalDateTime cacheTimestamp
		LocalDateTime created_at
		LocalDateTime updated_at
	}
	vehicle_applicability_hints {
		UUID hintId
		UUID productId
		LocalDateTime createdAt
		LocalDateTime updatedAt
		String createdBy
		String updatedBy
	}
	fitment_tags {
		UUID id
		String tagType
		String tagValue
		UUID hint_id
		LocalDateTime created_at
		LocalDateTime updated_at
	}
	part_fitment_entity {
		UUID id
		Long partNumberId
		UUID vehicle_manufacturer_id
		UUID vehicle_make_id
		UUID vehicle_model_id
		UUID vehicle_type_id
		String vehicleYear
		String engineType
		String submodel
		String notes
		LocalDateTime created_at
		LocalDateTime updated_at
	}
	manufacturer ||--o{ make : manufacturer
	make ||--o{ model : make
	make ||--o{ vehicle_type : make_id
	vehicle_variable ||--o{ vehicle_variable_value : variable_id
	vehicle_applicability_hints ||--o{ fitment_tags : hint_id
	manufacturer ||--o{ part_fitment_entity : vehicle_manufacturer_id
	make ||--o{ part_fitment_entity : vehicle_make_id
	model ||--o{ part_fitment_entity : vehicle_model_id
	vehicle_type ||--o{ part_fitment_entity : vehicle_type_id
	part_fitment_entity }o--o{ vehicle_variable_value : vehicleVariableValues
