erDiagram
	accounting_audit_log {
		uuid audit_log_id PK
		varchar entity_type
		uuid entity_id
		varchar operation
		varchar user_id
		timestamp timestamp
		varchar justification
		varchar ip_address
		varchar trace_id
		text old_value
		text new_value
	}
	accounting_event {
		uuid event_id PK
		bigint version
		varchar event_type
		uuid organization_id
		varchar source_system
		timestamp transaction_date
		json payload
		varchar status
		uuid journal_entry_id
		varchar error_message
		timestamp received_at
		timestamp processed_at
		bigint sequence_number
		varchar failure_reason_code
		text failure_details
	}
	accounting_status_sync_audit {
		uuid id PK
		uuid invoice_id
		varchar old_status
		varchar new_status
		varchar event_id
		timestamp synced_at
		varchar sync_source
		bigint latency_ms
	}
	ap_payment {
		uuid payment_id PK
		varchar payment_ref
		uuid vendor_bill_id FK
		uuid vendor_id
		varchar vendor_name
		decimal gross_amount
		decimal fee_amount
		decimal net_amount
		decimal unapplied_amount
		varchar currency
		varchar status
		varchar payment_method
		timestamp payment_date
		uuid bank_account_id
		varchar gateway_transaction_id
		varchar gateway_response
		timestamp gateway_timestamp
		uuid gl_journal_entry_id FK
		timestamp gl_posted_at
		varchar gl_post_error
		varchar memo
		timestamp created_at
		varchar created_by
	}
	ap_payment_allocation {
		uuid allocation_id PK
		uuid payment_id FK
		uuid vendor_bill_id FK
		decimal applied_amount
		int allocation_sequence
		timestamp created_at
	}
	audit_trail_entry {
		uuid audit_id PK
		varchar exception_type
		varchar actor_id
		varchar actor_role
		timestamp timestamp
		varchar reason
		varchar authorization_level
		varchar policy_version
		uuid order_id
		uuid line_item_id
		decimal original_price
		decimal adjusted_price
		varchar override_amount_or_percent
		varchar forbidden_category_code
		varchar policy_validation_result
		uuid invoice_id
		uuid payment_id
		varchar refund_type
		decimal refund_amount
		varchar original_payment_status
		varchar refund_method
		text linked_source_ids
		varchar cancellation_type
		text before_snapshot
		text after_snapshot
		varchar accounting_intent
		varchar accounting_status
		uuid source_event_id
	}
	credit_memo {
		uuid credit_memo_id PK
		uuid original_invoice_id
		uuid customer_id
		decimal credit_amount
		decimal tax_amount_reversed
		varchar reason_code
		varchar justification_note
		varchar status
		timestamp creation_timestamp
		timestamp posted_timestamp
		varchar created_by_user_id
		boolean prior_period_adjustment
		varchar original_period_id
		varchar currency
	}
	customer_credit {
		uuid credit_id PK
		uuid customer_id
		varchar currency
		decimal amount
		uuid source_payment_id
		varchar trace_id
		timestamp created_at
		varchar created_by
	}
	default_gl_mapping {
		uuid mapping_id PK
		varchar event_type
		uuid organization_id
		uuid debit_account_id FK
		uuid credit_account_id FK
		varchar description
		boolean active
		timestamp created_at
		varchar created_by
		timestamp modified_at
		varchar modified_by
	}
	event_outbox {
		uuid outbox_id PK
		uuid event_id
		varchar aggregate_type
		uuid aggregate_id
		varchar event_type
		text payload
		varchar status
		int retry_count
		text last_error
		timestamp created_at
		timestamp published_at
		timestamp last_attempt_at
	}
	gl_account {
		uuid gl_account_id PK
		varchar account_code
		varchar account_name
		varchar account_type
		varchar description
		uuid parent_account_id FK
		timestamp activation_date
		timestamp deactivation_date
		timestamp created_at
		varchar created_by
		timestamp modified_at
		varchar modified_by
	}
	gl_mapping {
		uuid gl_mapping_id PK
		varchar source_system
		varchar external_code
		uuid posting_category_id FK
		uuid mapping_key_id FK
		uuid gl_account_id FK
		timestamp effective_start_date
		timestamp effective_end_date
		json dimensions
		timestamp created_at
		varchar created_by
	}
	idempotency_keys {
		uuid id PK
		varchar keyValue
		uuid invoiceId
		timestamp createdAt
		timestamp expiresAt
	}
	invoice_status_views {
		uuid id PK
		uuid invoiceId
		varchar currentStatus
		decimal totalPaid
		decimal invoiceTotal
		timestamp lastUpdated
		varchar latestTransactionReference
		bigint totalAmountMinor
		bigint outstandingAmountMinor
		bigint paidAmountMinor
		boolean postingError
		varchar latestIdempotencyKey
		varchar accounting_status
	}
	journal_entry {
		uuid journal_entry_id PK
		varchar status
		varchar entry_type
		timestamp transaction_date
		varchar description
		uuid source_event_id
		varchar source_event_type
		uuid posting_rule_set_id FK
		uuid posting_rule_version_id FK
		varchar reason_code
		varchar justification
		uuid reversal_journal_entry_id FK
		uuid reversed_by_journal_entry_id FK
		decimal total_debits
		decimal total_credits
		boolean is_balanced
		timestamp created_at
		varchar created_by
		timestamp modified_at
		varchar modified_by
		timestamp posted_at
		varchar posted_by
		timestamp reversed_at
	}
	journal_entry_line {
		uuid line_id PK
		uuid journal_entry_id FK
		int line_number
		uuid gl_account_id FK
		varchar account_code
		varchar account_name
		decimal debit_amount
		decimal credit_amount
		varchar description
		json dimensions
	}
	mapping_key {
		uuid mapping_key_id PK
		uuid posting_category_id FK
		varchar key_name
		varchar description
		boolean is_active
		timestamp created_at
		varchar created_by
		timestamp modified_at
		varchar modified_by
	}
	override_policy_threshold {
		uuid policy_id PK
		varchar role
		decimal max_absolute_amount
		decimal max_percent_off
		timestamp effective_date
		timestamp expiration_date
		varchar version
		boolean active
		timestamp created_at
		timestamp updated_at
	}
	payment_application {
		uuid payment_application_id PK
		uuid payment_id FK
		uuid invoice_id
		uuid customer_id
		varchar currency
		decimal applied_amount
		decimal invoice_balance_before
		decimal invoice_balance_after
		varchar invoice_status
		timestamp application_timestamp
		varchar application_request_id
		varchar trace_id
	}
	payment_application_reversal {
		uuid reversal_id PK
		uuid original_payment_application_id FK
		decimal amount
		varchar reason
		timestamp reversed_at
		varchar reversed_by
		varchar trace_id
	}
	payment_applied_events {
		uuid id PK
		uuid invoiceId
		decimal paymentAmount
		decimal invoiceTotal
		varchar status
		timestamp timestamp
		varchar idempotencyKey
		varchar transactionReference
	}
	posting_category {
		uuid posting_category_id PK
		varchar category_name
		varchar description
		boolean is_active
		timestamp created_at
		varchar created_by
		timestamp modified_at
		varchar modified_by
	}
	posting_rule_set {
		uuid posting_rule_set_id PK
		varchar name
		varchar event_type
		varchar description
		timestamp created_at
		varchar created_by
		timestamp modified_at
		varchar modified_by
	}
	posting_rule_version {
		uuid version_id PK
		uuid posting_rule_set_id FK
		int version_number
		varchar state
		text rules_definition
		timestamp created_at
		varchar created_by
		timestamp modified_at
		varchar modified_by
		timestamp published_at
		varchar published_by
		timestamp archived_at
		varchar archived_by
	}
	receivable_payment {
		uuid payment_id PK
		uuid customer_id
		varchar currency
		decimal total_amount
		decimal unapplied_amount
		varchar status
		timestamp cleared_at
		uuid source_event_id
		timestamp created_at
		varchar created_by
		timestamp modified_at
		varchar modified_by
	}
	reconciliation {
		uuid reconciliation_id PK
		uuid gl_account_id FK
		varchar account_code
		varchar account_name
		timestamp period_start_date
		timestamp period_end_date
		timestamp statement_date
		decimal statement_ending_balance
		decimal gl_ending_balance
		decimal difference
		varchar status
		json statement_lines
		json gl_transactions
		json adjustments
		timestamp created_at
		varchar created_by
		timestamp finalized_at
		varchar finalized_by
	}
	reconciliation_records {
		uuid id PK
		uuid invoiceId
		varchar transactionId
		timestamp created_at
	}
	reprocessing_attempt_history {
		uuid attempt_id PK
		uuid event_id FK
		timestamp attempted_at
		varchar triggered_by_user_id
		varchar outcome
		text outcome_details
		varchar mapping_version_used
	}
	refund_policy_config {
		uuid config_id PK
		boolean requires_separate_authorization
		varchar settled_payment_handling
		varchar unsettled_payment_handling
		varchar version
		boolean active
		timestamp created_at
		timestamp updated_at
	}
	statement_line_mappings {
		uuid mapping_id PK
		uuid gl_account_id FK
		varchar account_name
		varchar statement_type
		varchar statement_line_code
		varchar line_description
		int display_order
		varchar parent_line_code
		varchar operation
	}
	vendor_bill {
		uuid vendor_bill_id PK
		uuid vendor_id
		varchar vendor_name
		varchar bill_number
		timestamp bill_date
		timestamp due_date
		decimal total_amount
		varchar status
		uuid purchase_order_id
		varchar purchase_order_number
		uuid origin_event_id
		varchar origin_event_type
		uuid journal_entry_id FK
		uuid payment_transaction_id
		timestamp created_at
		varchar created_by
		timestamp modified_at
		varchar modified_by
		timestamp approved_at
		varchar approved_by
		timestamp rejected_at
		varchar rejected_by
		timestamp paid_at
		varchar paid_by
	}
	vendor_bill_line {
		uuid line_id PK
		uuid vendor_bill_id FK
		int line_number
		uuid product_id
		varchar sku
		varchar description
		decimal quantity
		decimal unit_price
		decimal line_total
		boolean is_inventory_item
	}
	vendor_bill_match_candidate {
		uuid candidate_id PK
		uuid invoice_event_id
		uuid vendor_bill_id FK
		uuid vendor_id
		varchar bill_number
		decimal bill_total_amount
		int match_score
		varchar score_breakdown
		boolean resolved
		varchar resolved_by
		timestamp resolved_at
		boolean selected
		timestamp created_at
	}
	gl_account |o--o{ gl_account : "parent_account_id"
	gl_account ||--o{ gl_mapping : "gl_account_id"
	gl_account ||--o{ journal_entry_line : "gl_account_id"
	gl_account ||--o{ statement_line_mappings : "gl_account_id"
	gl_account ||--o{ reconciliation : "gl_account_id"
	gl_account ||--o{ default_gl_mapping : "debit_account_id"
	gl_account ||--o{ default_gl_mapping : "credit_account_id"
	posting_category ||--o{ mapping_key : "posting_category_id"
	posting_category |o--o{ gl_mapping : "posting_category_id"
	mapping_key |o--o{ gl_mapping : "mapping_key_id"
	posting_rule_set ||--o{ posting_rule_version : "posting_rule_set_id"
	posting_rule_set |o--o{ journal_entry : "posting_rule_set_id"
	posting_rule_version |o--o{ journal_entry : "posting_rule_version_id"
	journal_entry ||--o{ journal_entry_line : "journal_entry_id"
	journal_entry |o--o{ journal_entry : "reversal_journal_entry_id"
	journal_entry |o--o{ journal_entry : "reversed_by_journal_entry_id"
	journal_entry |o--o{ vendor_bill : "journal_entry_id"
	journal_entry |o--o{ ap_payment : "gl_journal_entry_id"
	accounting_event ||--o{ reprocessing_attempt_history : "event_id"
	vendor_bill ||--o{ vendor_bill_line : "vendor_bill_id"
	vendor_bill ||--o{ vendor_bill_match_candidate : "vendor_bill_id"
	vendor_bill |o--o{ ap_payment : "vendor_bill_id"
	vendor_bill ||--o{ ap_payment_allocation : "vendor_bill_id"
	ap_payment ||--o{ ap_payment_allocation : "payment_id"
	receivable_payment ||--o{ payment_application : "payment_id"
	payment_application ||--o{ payment_application_reversal : "original_payment_application_id"
