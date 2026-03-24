erDiagram
	users {
		UUID id
		String username
		String password
		boolean enabled
		boolean account_non_locked
		boolean account_non_expired
		boolean credentials_non_expired
		int failed_login_attempts
		Instant last_failed_login_at
		Instant last_successful_login_at
		Instant locked_at
		Instant locked_until
		Instant disabled_at
		String disabled_by
		Instant account_expires_at
		Instant credentials_expire_at
		UUID person_id
		String last_login_ip
		String last_login_user_agent
		Instant created_at
		Instant updated_at
	}
	roles {
		UUID id
		String name
		String description
		Instant created_at
		String created_by
		Instant updated_at
		Instant last_modified_at
		String last_modified_by
	}
	permissions {
		UUID id
		String name
		String description
		boolean deprecated
		String domain
		String resource
		String action
		Instant registered_at
		String registered_by_service
		String version
		Instant created_at
		Instant updated_at
		Integer bit_index
	}
	role_assignments {
		UUID id
		UUID user_id
		UUID role_id
		String scope_type
		LocalDateTime effective_start_date
		LocalDateTime effective_end_date
		Instant revoked_at
		Instant created_at
		String created_by
		Instant updated_at
	}
	principal_roles {
		UUID id
		String principal_id
		UUID role_id
		Instant created_at
		Instant updated_at
	}
	audit_log_events {
		UUID event_id
		Instant timestamp
		String event_type
		String actor_id
		String entity_id
		String entity_type
		String old_value
		String new_value
		String context
		Instant created_at
		Instant updated_at
	}
	self_registration_attempts {
		UUID id
		String idempotency_key
		String request_fingerprint
		String email
		String username
		String status
		UUID user_id
		UUID person_id
		String link_status
		boolean matched_existing_person
		boolean issued_tokens
		Integer crm_candidate_count
		Boolean crm_any_matches
		Integer crm_individual_customer_candidate_count
		Integer crm_commercial_contact_candidate_count
		Integer crm_shared_identity_candidate_count
		Boolean crm_exact_email_match
		Boolean crm_exact_phone_match
		Boolean crm_exact_name_match
		Boolean crm_review_required
		String conflict_code
		String conflict_message
		UUID reference_id
		Instant created_at
		Instant updated_at
	}
	self_registration_review_cases {
		UUID id
		String case_type
		String status
		String reason_code
		String reason_message
		String email
		String requested_username
		UUID person_id
		UUID linked_user_id
		Integer crm_candidate_count
		Integer crm_shared_identity_candidate_count
		Boolean crm_exact_email_match
		Boolean crm_exact_phone_match
		Boolean crm_exact_name_match
		String notes
		Instant resolved_at
		String resolved_by
		String resolution_notes
		Instant created_at
		Instant updated_at
	}
	pricing_snapshots {
		UUID snapshot_id
		Instant timestamp
		String quote_context
		BigDecimal final_price
		Instant created_at
		Instant updated_at
	}
	pricing_rule_trace_entries {
		UUID id
		UUID snapshot_id
		String rule_id
		String status
		String inputs
		String outputs
		Instant created_at
		Instant updated_at
	}
	users }o--o{ roles : ""
	roles }o--o{ permissions : ""
	role_assignments }o--|| users : "user_id"
	role_assignments }o--|| roles : "role_id"
	principal_roles }o--|| roles : "role_id"
	pricing_rule_trace_entries }o--|| pricing_snapshots : "snapshot_id"
