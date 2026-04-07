-- Phase 5.2 bootstrap: create additional accounting entity tables and baseline columns.

CREATE TABLE IF NOT EXISTS accounting_audit_log (
    id UUID PRIMARY KEY,
    audit_log_id UUID,
    entity_id VARCHAR(255),
    entity_type VARCHAR(128),
    operation VARCHAR(64),
    old_value TEXT,
    new_value TEXT,
    justification TEXT,
    user_id VARCHAR(255),
    ip_address VARCHAR(128),
    trace_id VARCHAR(255),
    "timestamp" TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS accounting_event (
    id UUID PRIMARY KEY,
    event_id UUID,
    journal_entry_id UUID,
    event_type VARCHAR(128),
    source_system VARCHAR(128),
    source_event_type VARCHAR(128),
    status VARCHAR(64),
    organization_id UUID,
    sequence_number BIGINT,
    transaction_date DATE,
    received_at TIMESTAMPTZ,
    processed_at TIMESTAMPTZ,
    payload TEXT,
    attempt_count INTEGER,
    mapping_version_attempted INTEGER,
    failure_reason_code VARCHAR(128),
    failure_details TEXT,
    error_message TEXT,
    final_posting_reference_id UUID,
    resolved_by_user_id VARCHAR(255),
    version INTEGER
);

CREATE TABLE IF NOT EXISTS ap_payment (
    id UUID PRIMARY KEY,
    payment_id UUID,
    vendor_bill_id UUID,
    vendor_id UUID,
    vendor_name VARCHAR(255),
    payment_ref VARCHAR(255),
    payment_method VARCHAR(64),
    payment_date DATE,
    status VARCHAR(64),
    currency VARCHAR(16),
    gross_amount NUMERIC(19, 4),
    fee_amount NUMERIC(19, 4),
    net_amount NUMERIC(19, 4),
    unapplied_amount NUMERIC(19, 4),
    bank_account_id UUID,
    memo TEXT,
    gateway_transaction_id VARCHAR(255),
    gateway_response TEXT,
    gateway_timestamp TIMESTAMPTZ,
    gl_journal_entry_id UUID,
    gl_posted_at TIMESTAMPTZ,
    gl_post_error TEXT,
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS ap_payment_allocation (
    id UUID PRIMARY KEY,
    allocation_id UUID,
    payment_id UUID,
    vendor_bill_id UUID,
    allocation_sequence INTEGER,
    applied_amount NUMERIC(19, 4),
    created_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS audit_trail_entry (
    id UUID PRIMARY KEY,
    audit_id UUID,
    timestamp TIMESTAMPTZ,
    actor_id VARCHAR(255),
    actor_role VARCHAR(128),
    source_event_id VARCHAR(255),
    source_document_id VARCHAR(255),
    order_id UUID,
    line_item_id UUID,
    invoice_id UUID,
    payment_id UUID,
    linked_source_ids TEXT,
    reason TEXT,
    exception_type VARCHAR(128),
    accounting_intent VARCHAR(128),
    expected_accounting_outcome VARCHAR(255),
    accounting_status VARCHAR(128),
    policy_validation_result TEXT,
    policy_version VARCHAR(64),
    authorization_level VARCHAR(128),
    forbidden_category_code VARCHAR(128),
    override_amount_or_percent NUMERIC(19, 4),
    adjusted_price NUMERIC(19, 4),
    original_price NUMERIC(19, 4),
    original_payment_status VARCHAR(128),
    partial_payment_info TEXT,
    refund_type VARCHAR(128),
    refund_amount NUMERIC(19, 4),
    refund_method VARCHAR(128),
    cancellation_type VARCHAR(128),
    gl_reversal_status VARCHAR(128),
    before_snapshot TEXT,
    after_snapshot TEXT
);

CREATE TABLE IF NOT EXISTS credit_memo (
    id UUID PRIMARY KEY,
    credit_memo_id UUID,
    customer_id UUID,
    original_invoice_id UUID,
    original_period_id UUID,
    reason_code VARCHAR(128),
    status VARCHAR(64),
    currency VARCHAR(16),
    credit_amount NUMERIC(19, 4),
    tax_amount_reversed NUMERIC(19, 4),
    justification_note TEXT,
    prior_period_adjustment BOOLEAN,
    created_by_user_id VARCHAR(255),
    creation_timestamp TIMESTAMPTZ,
    posted_timestamp TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS customer_credit (
    id UUID PRIMARY KEY,
    credit_id UUID,
    customer_id UUID,
    source_payment_id UUID,
    currency VARCHAR(16),
    amount NUMERIC(19, 4),
    created_by VARCHAR(255),
    trace_id VARCHAR(255),
    created_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS journal_entry (
    id UUID PRIMARY KEY,
    journal_entry_id UUID,
    source_event_id UUID,
    source_event_type VARCHAR(128),
    transaction_date DATE,
    reason_code VARCHAR(128),
    description TEXT,
    status VARCHAR(64),
    entry_type VARCHAR(64),
    total_debits NUMERIC(19, 4),
    total_credits NUMERIC(19, 4),
    is_balanced BOOLEAN,
    posting_rule_set_id UUID,
    posting_rule_version_id UUID,
    reversal_journal_entry_id UUID,
    reversed_by_journal_entry_id UUID,
    reversed_at TIMESTAMPTZ,
    posted_at TIMESTAMPTZ,
    posted_by VARCHAR(255),
    justification TEXT,
    created_by VARCHAR(255),
    modified_by VARCHAR(255),
    created_at TIMESTAMPTZ,
    modified_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS journal_entry_line (
    id UUID PRIMARY KEY,
    line_id UUID,
    journal_entry_id UUID,
    line_number INTEGER,
    account_code VARCHAR(128),
    account_name VARCHAR(255),
    gl_account_id UUID,
    description TEXT,
    dimensions TEXT,
    debit_amount NUMERIC(19, 4),
    credit_amount NUMERIC(19, 4)
);

CREATE TABLE IF NOT EXISTS override_policy_threshold (
    id UUID PRIMARY KEY,
    policy_id UUID,
    role VARCHAR(128),
    max_percent_off NUMERIC(19, 4),
    max_absolute_amount NUMERIC(19, 4),
    effective_date DATE,
    expiration_date DATE,
    active BOOLEAN,
    version INTEGER,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS payment_application (
    id UUID PRIMARY KEY,
    payment_application_id UUID,
    payment_id UUID,
    invoice_id UUID,
    customer_id UUID,
    currency VARCHAR(16),
    invoice_status VARCHAR(64),
    application_request_id VARCHAR(255),
    trace_id VARCHAR(255),
    applied_amount NUMERIC(19, 4),
    invoice_balance_before NUMERIC(19, 4),
    invoice_balance_after NUMERIC(19, 4),
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ,
    application_timestamp TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS payment_application_reversal (
    id UUID PRIMARY KEY,
    reversal_id UUID,
    original_payment_application_id UUID,
    amount NUMERIC(19, 4),
    reason TEXT,
    reversed_by VARCHAR(255),
    reversed_at TIMESTAMPTZ,
    trace_id VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS payment_applied_events (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS posting_rule_set (
    id UUID PRIMARY KEY,
    posting_rule_set_id UUID,
    name VARCHAR(255),
    event_type VARCHAR(128),
    description TEXT,
    created_by VARCHAR(255),
    modified_by VARCHAR(255),
    created_at TIMESTAMPTZ,
    modified_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS posting_rule_version (
    id UUID PRIMARY KEY,
    version_id UUID,
    posting_rule_set_id UUID,
    version_number INTEGER,
    state VARCHAR(64),
    rules_definition TEXT,
    created_by VARCHAR(255),
    modified_by VARCHAR(255),
    published_by VARCHAR(255),
    archived_by VARCHAR(255),
    created_at TIMESTAMPTZ,
    modified_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    archived_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS receivable_payment (
    id UUID PRIMARY KEY,
    payment_id UUID,
    customer_id UUID,
    source_event_id UUID,
    currency VARCHAR(16),
    status VARCHAR(64),
    total_amount NUMERIC(19, 4),
    unapplied_amount NUMERIC(19, 4),
    created_by VARCHAR(255),
    modified_by VARCHAR(255),
    created_at TIMESTAMPTZ,
    modified_at TIMESTAMPTZ,
    cleared_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS reconciliation (
    id UUID PRIMARY KEY,
    reconciliation_id UUID,
    gl_account_id UUID,
    account_code VARCHAR(128),
    account_name VARCHAR(255),
    status VARCHAR(64),
    period_start_date DATE,
    period_end_date DATE,
    statement_date DATE,
    statement_ending_balance NUMERIC(19, 4),
    gl_ending_balance NUMERIC(19, 4),
    difference NUMERIC(19, 4),
    statement_lines TEXT,
    gl_transactions TEXT,
    adjustments TEXT,
    finalized_by VARCHAR(255),
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ,
    finalized_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS refund_policy_config (
    id UUID PRIMARY KEY,
    config_id UUID,
    unsettled_payment_handling VARCHAR(128),
    settled_payment_handling VARCHAR(128),
    requires_separate_authorization BOOLEAN,
    active BOOLEAN,
    version INTEGER,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS reprocessing_attempt_history (
    id UUID PRIMARY KEY,
    attempt_id UUID,
    event_id UUID,
    mapping_version_used INTEGER,
    outcome VARCHAR(64),
    outcome_details TEXT,
    triggered_by_user_id VARCHAR(255),
    attempted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS vendor_bill (
    id UUID PRIMARY KEY,
    vendor_bill_id UUID,
    vendor_id UUID,
    vendor_name VARCHAR(255),
    bill_number VARCHAR(255),
    purchase_order_id UUID,
    purchase_order_number VARCHAR(255),
    origin_event_id UUID,
    origin_event_type VARCHAR(128),
    status VARCHAR(64),
    total_amount NUMERIC(19, 4),
    journal_entry_id UUID,
    payment_transaction_id UUID,
    approval_justification TEXT,
    rejection_reason TEXT,
    approved_by VARCHAR(255),
    rejected_by VARCHAR(255),
    paid_by VARCHAR(255),
    created_by VARCHAR(255),
    modified_by VARCHAR(255),
    bill_date DATE,
    due_date DATE,
    approved_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ,
    modified_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS vendor_bill_line (
    id UUID PRIMARY KEY,
    line_id UUID,
    vendor_bill_id UUID,
    line_number INTEGER,
    sku VARCHAR(255),
    description TEXT,
    product_id UUID,
    quantity NUMERIC(19, 4),
    unit_price NUMERIC(19, 4),
    line_total NUMERIC(19, 4),
    is_inventory_item BOOLEAN
);

CREATE TABLE IF NOT EXISTS vendor_bill_match_candidate (
    id UUID PRIMARY KEY,
    candidate_id UUID,
    invoice_event_id UUID,
    vendor_bill_id UUID,
    vendor_id UUID,
    bill_number VARCHAR(255),
    bill_total_amount NUMERIC(19, 4),
    match_score NUMERIC(10, 4),
    score_breakdown TEXT,
    selected BOOLEAN,
    resolved BOOLEAN,
    resolved_by VARCHAR(255),
    created_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ
);
