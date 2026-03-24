erDiagram
	employee_offboarding_retry_queue {
		UUID id
		UUID employee_id
		VARCHAR assignment_policy
		VARCHAR disable_reason
		VARCHAR actor_id
		VARCHAR failure_reason
		INT attempts
		TIMESTAMP next_attempt_at
		TIMESTAMP created_at
		TIMESTAMP updated_at
	}
	person_location_assignment {
		UUID id
		UUID person_id
		UUID location_id
		VARCHAR role
		BOOLEAN is_primary
		DATE effective_from
		DATE effective_to
		VARCHAR status
		TIMESTAMP created_at
		TIMESTAMP updated_at
		VARCHAR created_by
	}
	time_entry {
		UUID time_entry_id
		UUID person_id
		VARCHAR timesheet_id
		UUID location_id
		TIMESTAMP attendance_start_at
		TIMESTAMP attendance_end_at
		VARCHAR status
		VARCHAR approved_by
		TIMESTAMP approved_at
		VARCHAR rejected_by
		TIMESTAMP rejected_at
		VARCHAR rejection_reason
		TIMESTAMP created_at
		TIMESTAMP updated_at
	}
	time_entry_adjustment {
		UUID adjustment_id
		UUID time_entry_id
		VARCHAR reason_code
		TEXT notes
		TIMESTAMP proposed_start_at
		TIMESTAMP proposed_end_at
		INT minutes_delta
		VARCHAR status
		VARCHAR created_by
		TIMESTAMP created_at
		TIMESTAMP updated_at
		VARCHAR decided_by
		TIMESTAMP decided_at
	}
	time_entry_audit {
		UUID audit_id
		VARCHAR time_entry_id
		VARCHAR action
		VARCHAR actor_id
		TIMESTAMP timestamp
		VARCHAR correlation_id
		TEXT details
		TIMESTAMP created_at
		TIMESTAMP updated_at
	}
	time_entry_exception {
		UUID exception_id
		VARCHAR employee_id
		DATE work_date
		VARCHAR exception_code
		VARCHAR severity
		VARCHAR status
		VARCHAR time_entry_id
		TEXT resolution_notes
		TIMESTAMP detected_at
		VARCHAR resolved_by
		TIMESTAMP resolved_at
		TIMESTAMP created_at
		TIMESTAMP updated_at
	}
	timekeeping_entry {
		UUID timekeeping_entry_id
		UUID tenant_id
		VARCHAR source_system
		UUID source_session_id
		UUID original_source_session_id
		UUID correction_id
		TEXT correction_reason
		UUID employee_id
		TIMESTAMP session_start_time
		TIMESTAMP session_end_time
		VARCHAR approval_status
		UUID associated_work_order_id
		TIMESTAMP created_at
	}
	timekeeping_policy {
		UUID timekeeping_policy_id
		VARCHAR scope_type
		UUID scope_id
		INT job_time_discrepancy_threshold_minutes
		TIMESTAMP effective_start_at
		TIMESTAMP effective_end_at
		VARCHAR updated_by
		TIMESTAMP created_at
		TIMESTAMP updated_at
	}
	user_person_links {
		UUID id
		UUID user_id
		UUID person_id
		VARCHAR link_type
		VARCHAR status
		TIMESTAMP created_at
		TIMESTAMP updated_at
		VARCHAR created_by
		VARCHAR notes
	}
	work_session {
		UUID session_id
		UUID person_id
		VARCHAR status
		TIMESTAMP started_at
		TIMESTAMP ended_at
		VARCHAR actor
		TIMESTAMP created_at
		TIMESTAMP updated_at
	}
	work_session_break {
		UUID break_id
		UUID session_id
		TIMESTAMP started_at
		TIMESTAMP ended_at
		VARCHAR actor
		TIMESTAMP created_at
		TIMESTAMP updated_at
	}
	time_entry ||--o{ time_entry_adjustment : "time_entry_id"
	work_session ||--o{ work_session_break : "session_id"
