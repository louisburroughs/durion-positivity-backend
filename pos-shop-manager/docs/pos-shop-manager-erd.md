erDiagram
	mechanic {
		UUID mechanic_id
		String person_id
		String first_name
		String last_name
		String status
		LocalDate hire_date
		LocalDate termination_date
		int version
		Instant last_synced_at
		Instant created_at
		Instant updated_at
	}
	appointment {
		UUID appointment_id
		String status
		UUID location_id
		String resource_id
		String resource_type
		UUID crm_customer_id
		UUID crm_vehicle_id
		String customer_snapshot
		String vehicle_snapshot
		Instant start_at
		Instant end_at
		Instant created_at
		Instant updated_at
		String created_by
		String workorder_link_ref
		String idempotency_key
		String cancellation_reason
		String cancellation_notes
		boolean is_conflict_override
		String source_type
		String source_id
		String assignment_status
		boolean reopen_flag
	}
	assignment {
		UUID assignment_id
		UUID appointment_id
		String status
		UUID resource_id
		String resource_type
		boolean is_override
		String override_reason
		int version
		String notes
		Instant created_at
		Instant updated_at
	}
	assignment_mechanic {
		UUID id
		UUID assignment_id
		UUID mechanic_id
		String role
		Instant created_at
		Instant updated_at
	}
	mechanic_skill {
		UUID id
		UUID mechanic_id
		String skill_code
		int proficiency_level
		LocalDate certified_date
		LocalDate expiration_date
		Instant created_at
		Instant updated_at
	}
	mechanic_audit_log {
		UUID id
		UUID event_id
		String person_id
		String event_type
		String before_state
		String after_state
		Instant applied_at
		String changed_by
		Instant created_at
		Instant updated_at
	}
	hr_integration_log {
		UUID event_id
		String person_id
		String event_type
		Instant received_at
		Instant processed_at
		String status
		String error_message
		String payload_hash
		Instant created_at
		Instant updated_at
	}
	shop_audit_entry {
		UUID id
		String event_type
		String workorder_id
		String appointment_id
		String mechanic_id
		String location_id
		String actor_user_id
		String change_summary_text
		String change_patch
		String reason_code
		String reason_notes
		int retention_years
		Instant recorded_at
	}
	reschedule_history {
		UUID reschedule_id
		UUID appointment_id
		Instant previous_start_at
		Instant previous_end_at
		Instant new_start_at
		Instant new_end_at
		String reschedule_reason
		String reschedule_reason_notes
		String rescheduled_by
		Instant rescheduled_at
		boolean conflict_overridden
		String assignment_status
	}
	override_record {
		UUID override_id
		UUID appointment_id
		String overridden_by_user_id
		Instant override_timestamp
		String override_reason
		String conflict_details
		Instant created_at
		Instant updated_at
	}
	work_order_appointment_mapping {
		UUID work_order_id
		UUID appointment_id
		Instant created_at
		String status
	}
	travel_block {
		UUID id
		String person_id
		Instant start_time
		Instant end_time
		String description
		Instant created_at
		Instant updated_at
	}
	appointment_service_request {
		UUID service_request_id
		UUID appointment_id
		UUID service_entity_id
		Instant created_at
		Instant updated_at
	}
	appointment_audit {
		UUID audit_id
		UUID appointment_id
		String action
		String actor_id
		Instant previous_start_at
		Instant previous_end_at
		Instant new_start_at
		Instant new_end_at
		String cancellation_reason
		Instant created_at
		Instant updated_at
	}
	appointment ||--o{ assignment : "appointment_id"
	assignment ||--o{ assignment_mechanic : "assignment_id"
	mechanic ||--o{ assignment_mechanic : "mechanic_id"
	mechanic ||--o{ mechanic_skill : "mechanic_id"
	appointment ||--o{ reschedule_history : "appointment_id"
	appointment ||--o{ override_record : "appointment_id"
	appointment ||--o{ work_order_appointment_mapping : "appointment_id"
	appointment ||--o{ appointment_service_request : "appointment_id"
	appointment ||--o{ appointment_audit : "appointment_id"
