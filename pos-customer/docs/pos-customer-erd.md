erDiagram
person_party {
UUID customer_id
String customerNumber
String phoneNumber
String email
String primaryAddress
String status
String tier
Instant tierAssignedAt
String tierAssignedBy
boolean tierManualOverride
Instant created_at
Instant updated_at
UUID person_id
String lastName
String firstName
String preferred_contact_method
}
commercial_party {
UUID customer_id
String customerNumber
String phoneNumber
String email
String primaryAddress
String status
String tier
Instant tierAssignedAt
String tierAssignedBy
boolean tierManualOverride
Instant created_at
Instant updated_at
String partyNumber
String legalName
String displayName
String taxId
String billingTermsId
String partyType
UUID parent_party_id
}
contact {
UUID contact_id
UUID person_id
UUID party_id
String firstName
String lastName
String email
String phoneNumber
boolean active
Instant created_at
Instant updated_at
}
contact_point {
UUID contact_point_id
UUID person_id
String contact_type
String value
boolean is_primary
Instant created_at
Instant updated_at
}
contact_role_assignment {
UUID contact_id
UUID customer_account_id
String role_name
boolean is_primary
Instant created_at
Instant updated_at
}
party_relationship {
UUID party_relationship_id
UUID from_party_id
UUID to_person_id
boolean is_primary_billing_contact
LocalDate effective_start_date
LocalDate effective_end_date
Instant created_at
UUID created_by
Instant updated_at
}
party_note {
UUID note_id
UUID party_id
String note_text
String note_type
String source_workorder_id
String source_event_id
Instant created_at
}
party_alias {
UUID source_party_id
UUID target_party_id
Instant created_at
Instant updated_at
}
merge_audit {
UUID merge_audit_id
UUID survivor_party_id
UUID source_party_id
UUID merged_by_user_id
String merge_reason
Instant merged_at
Instant updated_at
Integer contacts_transferred
Integer vehicles_transferred
Integer external_ids_merged
}
communication_preference {
UUID preference_id
UUID party_id
String email_preference
String sms_preference
String phone_preference
String marketing_preference
String preferences_note
String update_source
Long version
Instant created_at
Instant updated_at
}
promotion_redemption {
UUID promotion_redemption_id
UUID promotion_id
UUID customer_id
UUID workorder_id
UUID invoice_id
BigDecimal discount_amount
String discount_type
String promotion_code
String recorded_by
Boolean recorded_over_limit
String status
LocalDateTime redemption_timestamp
LocalDateTime created_at
}
promotion_counter {
UUID counter_id
UUID promotion_id
int total_usage_count
Long version
}
processing_log {
UUID id
String event_id
String event_type
String correlation_id
String status
String failure_reason
Instant processed_at
Instant created_at
}
vehicle_projection {
UUID vehicle_id
String vin
String make
String model
Integer vehicle_year
String color
Instant last_event_at
}
commercial_party ||--o{ commercial_party : "parent_party_id"
commercial_party ||--o{ contact : "party_id"
person_party ||--o{ contact_point : "person_id"
commercial_party ||--o{ party_relationship : "from_party_id"
person_party ||--o{ party_relationship : "to_person_id"
