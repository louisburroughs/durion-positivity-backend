erDiagram
	vehicle_records {
		UUID vehicle_id
		UUID account_id
		String vin
		String vin_normalized
		String unit_number
		TEXT description
		String license_plate
		String license_plate_jurisdiction
		Integer model_year
		String make
		String model
		String trim
		jsonb odometer
		Instant last_service_date
		Boolean is_active
		Instant created_at
		Instant updated_at
		Long version
	}
	vehicle_care_preferences {
		UUID id
		UUID vehicle_id
		jsonb preferences
		TEXT service_notes
		UUID created_by_user_id
		UUID updated_by_user_id
		Instant created_at
		Instant updated_at
		Long version
	}
	event_processing_log {
		UUID log_id
		String event_id
		String workorder_id
		String vehicle_id
		String status
		String conflict_policy
		jsonb details
		Instant processed_at
		Instant created_at
		Instant updated_at
	}
	vehicle_records ||--o{ vehicle_care_preferences : vehicle_id
