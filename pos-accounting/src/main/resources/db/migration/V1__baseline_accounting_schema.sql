-- Collapsed baseline for pos-accounting generated from emitted Postgres schema.

-- Source dump: /tmp/pos_accounting_baseline_emitted.sql

-- Repeatable seed/data migrations remain separate and are not folded into this file.



SET TIME ZONE 'UTC';



CREATE TABLE accounting_audit_log ( "timestamp" timestamp(6) with time zone NOT NULL, audit_log_id uuid NOT NULL, entity_id uuid NOT NULL, entity_type character varying(50) NOT NULL, ip_address character varying(50), operation character varying(50) NOT NULL, user_id character varying(50) NOT NULL, trace_id character varying(100), justification character varying(2000), new_value text, old_value text );

CREATE TABLE accounting_event ( attempt_count integer, processed_at timestamp(6) with time zone, received_at timestamp(6) with time zone NOT NULL, sequence_number bigint, transaction_date timestamp(6) without time zone NOT NULL, version bigint NOT NULL, event_id uuid NOT NULL, journal_entry_id uuid, organization_id uuid, status character varying(20) NOT NULL, mapping_version_attempted character varying(50), event_type character varying(100) NOT NULL, failure_reason_code character varying(100), final_posting_reference_id character varying(100), resolved_by_user_id character varying(100), source_system character varying(100) NOT NULL, error_message character varying(2000), failure_details character varying(4000), payload jsonb NOT NULL, CONSTRAINT accounting_event_status_check CHECK (((status)::text = ANY ((ARRAY['RECEIVED'::character varying, 'PROCESSING'::character varying, 'PROCESSED'::character varying, 'FAILED'::character varying, 'SUSPENDED'::character varying])::text[]))) );

CREATE TABLE accounting_status_sync_audit ( latency_ms bigint, synced_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, invoice_id uuid NOT NULL, new_status character varying(50) NOT NULL, old_status character varying(50), sync_source character varying(100) NOT NULL, event_id character varying(255) NOT NULL, CONSTRAINT accounting_status_sync_audit_new_status_check CHECK (((new_status)::text = ANY ((ARRAY['PENDING_POSTING'::character varying, 'POSTED'::character varying, 'RECONCILED'::character varying, 'REJECTED'::character varying, 'REVERSED'::character varying, 'VOIDED'::character varying, 'ON_HOLD'::character varying, 'DISPUTED'::character varying])::text[]))), CONSTRAINT accounting_status_sync_audit_old_status_check CHECK (((old_status)::text = ANY ((ARRAY['PENDING_POSTING'::character varying, 'POSTED'::character varying, 'RECONCILED'::character varying, 'REJECTED'::character varying, 'REVERSED'::character varying, 'VOIDED'::character varying, 'ON_HOLD'::character varying, 'DISPUTED'::character varying])::text[]))) );

CREATE TABLE ap_payment ( currency character varying(3) NOT NULL, fee_amount numeric(19,4), gross_amount numeric(19,4) NOT NULL, net_amount numeric(19,4), unapplied_amount numeric(19,4), created_at timestamp(6) with time zone NOT NULL, gateway_timestamp timestamp(6) with time zone, gl_posted_at timestamp(6) with time zone, payment_date timestamp(6) without time zone, bank_account_id uuid, gl_journal_entry_id uuid, payment_id uuid NOT NULL, vendor_bill_id uuid, vendor_id uuid NOT NULL, payment_method character varying(20), status character varying(30) NOT NULL, created_by character varying(50) NOT NULL, payment_ref character varying(100) NOT NULL, gateway_transaction_id character varying(200), vendor_name character varying(200), memo character varying(1000), gateway_response character varying(2000), gl_post_error character varying(2000), CONSTRAINT ap_payment_payment_method_check CHECK (((payment_method)::text = ANY ((ARRAY['ACH'::character varying, 'CHECK'::character varying, 'WIRE'::character varying, 'CREDIT_CARD'::character varying, 'OTHER'::character varying])::text[]))), CONSTRAINT ap_payment_status_check CHECK (((status)::text = ANY ((ARRAY['INITIATED'::character varying, 'GATEWAY_PENDING'::character varying, 'GATEWAY_FAILED'::character varying, 'GATEWAY_SUCCEEDED'::character varying, 'GL_POST_PENDING'::character varying, 'GL_POSTED'::character varying, 'GL_POST_FAILED'::character varying])::text[]))) );

CREATE TABLE ap_payment_allocation ( allocation_sequence integer, applied_amount numeric(19,4) NOT NULL, created_at timestamp(6) with time zone NOT NULL, allocation_id uuid NOT NULL, payment_id uuid NOT NULL, vendor_bill_id uuid NOT NULL );

CREATE TABLE audit_trail_entry ( adjusted_price numeric(19,4), original_price numeric(19,4), refund_amount numeric(19,4), "timestamp" timestamp(6) with time zone NOT NULL, audit_id uuid NOT NULL, invoice_id uuid, line_item_id uuid, order_id uuid, payment_id uuid, source_event_id uuid, accounting_intent character varying(50), accounting_status character varying(50) NOT NULL, cancellation_type character varying(50), exception_type character varying(50) NOT NULL, gl_reversal_status character varying(50), original_payment_status character varying(50), override_amount_or_percent character varying(50), policy_validation_result character varying(50), policy_version character varying(50) NOT NULL, refund_method character varying(50), refund_type character varying(50), actor_role character varying(100) NOT NULL, authorization_level character varying(100) NOT NULL, forbidden_category_code character varying(100), expected_accounting_outcome character varying(1000), reason character varying(2000), actor_id character varying(255) NOT NULL, after_snapshot text, before_snapshot text, linked_source_ids text, partial_payment_info text, source_document_id character varying(255), CONSTRAINT audit_trail_entry_accounting_intent_check CHECK (((accounting_intent)::text = ANY ((ARRAY['REVENUE_ADJUSTMENT'::character varying, 'PAYMENT_REVERSAL'::character varying, 'CUSTOMER_CREDIT'::character varying, 'WRITE_OFF'::character varying, 'REVENUE_REVERSAL'::character varying, 'PAYMENT_RECOVERY'::character varying])::text[]))), CONSTRAINT audit_trail_entry_accounting_status_check CHECK (((accounting_status)::text = ANY ((ARRAY['PENDING_POSTING'::character varying, 'POSTED'::character varying, 'RECONCILED'::character varying, 'REJECTED'::character varying, 'REVERSED'::character varying, 'VOIDED'::character varying, 'ON_HOLD'::character varying, 'DISPUTED'::character varying])::text[]))), CONSTRAINT audit_trail_entry_cancellation_type_check CHECK (((cancellation_type)::text = ANY ((ARRAY['ORDER_CANCELLED'::character varying, 'INVOICE_CANCELLED'::character varying, 'PAYMENT_FAILED'::character varying])::text[]))), CONSTRAINT audit_trail_entry_exception_type_check CHECK (((exception_type)::text = ANY ((ARRAY['PRICE_OVERRIDE'::character varying, 'REFUND'::character varying, 'CANCELLATION'::character varying])::text[]))), CONSTRAINT audit_trail_entry_original_payment_status_check CHECK (((original_payment_status)::text = ANY ((ARRAY['PENDING'::character varying, 'SETTLED'::character varying, 'FAILED'::character varying, 'AUTHORIZED'::character varying])::text[]))), CONSTRAINT audit_trail_entry_policy_validation_result_check CHECK (((policy_validation_result)::text = ANY ((ARRAY['APPROVED'::character varying, 'REJECTED_FORBIDDEN'::character varying, 'REJECTED_THRESHOLD_EXCEEDED'::character varying])::text[]))), CONSTRAINT audit_trail_entry_refund_method_check CHECK (((refund_method)::text = ANY ((ARRAY['VOID'::character varying, 'CHARGEBACK'::character varying, 'CASH_REFUND'::character varying, 'CREDIT_MEMO'::character varying])::text[]))), CONSTRAINT audit_trail_entry_refund_type_check CHECK (((refund_type)::text = ANY ((ARRAY['REVERSAL'::character varying, 'CREDIT_MEMO'::character varying, 'ADJUSTMENT'::character varying])::text[]))) );

CREATE TABLE credit_memo ( credit_amount numeric(19,4) NOT NULL, currency character varying(3) NOT NULL, prior_period_adjustment boolean NOT NULL, tax_amount_reversed numeric(19,4) NOT NULL, creation_timestamp timestamp(6) with time zone NOT NULL, posted_timestamp timestamp(6) with time zone, credit_memo_id uuid NOT NULL, customer_id uuid NOT NULL, original_invoice_id uuid NOT NULL, status character varying(20) NOT NULL, created_by_user_id character varying(50) NOT NULL, original_period_id character varying(50), reason_code character varying(50) NOT NULL, justification_note character varying(1000), CONSTRAINT credit_memo_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'POSTED'::character varying, 'APPLIED'::character varying, 'VOIDED'::character varying])::text[]))) );

CREATE TABLE customer_credit ( amount numeric(19,4) NOT NULL, currency character varying(3) NOT NULL, created_at timestamp(6) with time zone NOT NULL, credit_id uuid NOT NULL, customer_id uuid NOT NULL, source_payment_id uuid NOT NULL, created_by character varying(50), trace_id character varying(100) );

CREATE TABLE default_gl_mapping ( active boolean NOT NULL, created_at timestamp(6) with time zone NOT NULL, modified_at timestamp(6) with time zone NOT NULL, credit_account_id uuid NOT NULL, debit_account_id uuid NOT NULL, mapping_id uuid NOT NULL, organization_id uuid, created_by character varying(100) NOT NULL, event_type character varying(100) NOT NULL, modified_by character varying(100) NOT NULL, description character varying(500) );

CREATE TABLE event_outbox ( retry_count integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, last_attempt_at timestamp(6) with time zone, published_at timestamp(6) with time zone, aggregate_id uuid NOT NULL, event_id uuid NOT NULL, outbox_id uuid NOT NULL, status character varying(20) NOT NULL, aggregate_type character varying(100) NOT NULL, event_type character varying(200) NOT NULL, last_error text, payload text NOT NULL, CONSTRAINT event_outbox_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PUBLISHED'::character varying, 'FAILED'::character varying])::text[]))) );

CREATE TABLE gl_account ( version integer, activation_date timestamp(6) without time zone, created_at timestamp(6) with time zone NOT NULL, deactivation_date timestamp(6) without time zone, modified_at timestamp(6) with time zone NOT NULL, gl_account_id uuid NOT NULL, parent_account_id uuid, account_code character varying(20) NOT NULL, account_type character varying(20) NOT NULL, created_by character varying(50) NOT NULL, modified_by character varying(50) NOT NULL, account_name character varying(100) NOT NULL, description character varying(500), CONSTRAINT gl_account_account_type_check CHECK (((account_type)::text = ANY ((ARRAY['ASSET'::character varying, 'LIABILITY'::character varying, 'EQUITY'::character varying, 'REVENUE'::character varying, 'EXPENSE'::character varying])::text[]))) );

CREATE TABLE gl_mapping ( created_at timestamp(6) with time zone NOT NULL, effective_end_date timestamp(6) without time zone, effective_start_date timestamp(6) without time zone NOT NULL, gl_account_id uuid NOT NULL, gl_mapping_id uuid NOT NULL, mapping_key_id uuid, posting_category_id uuid, created_by character varying(50) NOT NULL, source_system character varying(50) NOT NULL, external_code character varying(100) NOT NULL, dimensions jsonb );

CREATE TABLE idempotency_keys ( created_at timestamp(6) with time zone NOT NULL, expires_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, invoice_id uuid NOT NULL, key_value character varying(255) NOT NULL );

CREATE TABLE invoice_status_views ( archived boolean, discrepancy_detected boolean NOT NULL, invoice_total numeric(19,2) NOT NULL, posting_error boolean NOT NULL, total_paid numeric(19,2) NOT NULL, accounting_status_updated_at timestamp(6) with time zone, last_updated timestamp(6) with time zone NOT NULL, outstanding_amount_minor bigint, paid_amount_minor bigint, total_amount_minor bigint, id uuid NOT NULL, invoice_id uuid NOT NULL, discrepancy_reason character varying(1000), accounting_status character varying(255), current_status character varying(255) NOT NULL, latest_idempotency_key character varying(255), latest_transaction_reference character varying(255), posting_reference character varying(255), CONSTRAINT invoice_status_views_accounting_status_check CHECK (((accounting_status)::text = ANY ((ARRAY['PENDING_POSTING'::character varying, 'POSTED'::character varying, 'RECONCILED'::character varying, 'REJECTED'::character varying, 'REVERSED'::character varying, 'VOIDED'::character varying, 'ON_HOLD'::character varying, 'DISPUTED'::character varying])::text[]))), CONSTRAINT invoice_status_views_current_status_check CHECK (((current_status)::text = ANY ((ARRAY['PAID'::character varying, 'PARTIALLY_PAID'::character varying, 'UNPAID'::character varying, 'FAILED'::character varying, 'CHARGEBACK'::character varying])::text[]))) );

CREATE TABLE journal_entry ( is_balanced boolean NOT NULL, total_credits numeric(19,4) NOT NULL, total_debits numeric(19,4) NOT NULL, created_at timestamp(6) with time zone NOT NULL, modified_at timestamp(6) with time zone NOT NULL, posted_at timestamp(6) with time zone, reversed_at timestamp(6) with time zone, transaction_date timestamp(6) without time zone NOT NULL, journal_entry_id uuid NOT NULL, posting_rule_set_id uuid, posting_rule_version_id uuid, reversal_journal_entry_id uuid, reversed_by_journal_entry_id uuid, source_event_id uuid, entry_type character varying(20) NOT NULL, status character varying(20) NOT NULL, created_by character varying(50) NOT NULL, modified_by character varying(50) NOT NULL, posted_by character varying(50), reason_code character varying(50), source_event_type character varying(100), description character varying(500), justification character varying(1000), CONSTRAINT journal_entry_entry_type_check CHECK (((entry_type)::text = ANY ((ARRAY['EVENT_DRIVEN'::character varying, 'MANUAL'::character varying])::text[]))), CONSTRAINT journal_entry_reason_code_check CHECK (((reason_code)::text = ANY ((ARRAY['ACCRUAL_ADJUSTMENT'::character varying, 'ERROR_CORRECTION'::character varying, 'RECLASSIFICATION'::character varying, 'DEPRECIATION'::character varying, 'OTHER'::character varying])::text[]))), CONSTRAINT journal_entry_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PENDING'::character varying, 'POSTED'::character varying, 'REVERSED'::character varying])::text[]))) );

CREATE TABLE journal_entry_line ( credit_amount numeric(19,4) NOT NULL, debit_amount numeric(19,4) NOT NULL, line_number integer NOT NULL, gl_account_id uuid NOT NULL, journal_entry_id uuid NOT NULL, line_id uuid NOT NULL, account_code character varying(20), account_name character varying(100), description character varying(500), dimensions jsonb );

CREATE TABLE mapping_key ( is_active boolean NOT NULL, created_at timestamp(6) with time zone NOT NULL, modified_at timestamp(6) with time zone NOT NULL, mapping_key_id uuid NOT NULL, posting_category_id uuid NOT NULL, created_by character varying(50) NOT NULL, modified_by character varying(50) NOT NULL, key_name character varying(100) NOT NULL, description character varying(500) );

CREATE TABLE override_policy_threshold ( active boolean NOT NULL, max_absolute_amount numeric(19,4), max_percent_off numeric(5,2), created_at timestamp(6) with time zone NOT NULL, effective_date timestamp(6) with time zone NOT NULL, expiration_date timestamp(6) with time zone, updated_at timestamp(6) with time zone NOT NULL, policy_id uuid NOT NULL, version character varying(50) NOT NULL, role character varying(100) NOT NULL );

CREATE TABLE payment_application ( applied_amount numeric(19,4) NOT NULL, currency character varying(3) NOT NULL, invoice_balance_after numeric(19,4), invoice_balance_before numeric(19,4), invoice_status smallint, application_timestamp timestamp(6) with time zone NOT NULL, created_at timestamp(6) with time zone NOT NULL, customer_id uuid NOT NULL, invoice_id uuid NOT NULL, payment_application_id uuid NOT NULL, payment_id uuid NOT NULL, created_by character varying(50) NOT NULL, application_request_id character varying(100) NOT NULL, trace_id character varying(100), CONSTRAINT payment_application_invoice_status_check CHECK (((invoice_status >= 0) AND (invoice_status <= 5))) );

CREATE TABLE payment_application_reversal ( amount numeric(19,4) NOT NULL, reversed_at timestamp(6) with time zone NOT NULL, original_payment_application_id uuid NOT NULL, reversal_id uuid NOT NULL, reversed_by character varying(50) NOT NULL, trace_id character varying(100), reason character varying(1000) NOT NULL );

CREATE TABLE payment_applied_events ( invoice_total numeric(19,2) NOT NULL, payment_amount numeric(19,2) NOT NULL, "timestamp" timestamp(6) with time zone NOT NULL, id uuid NOT NULL, invoice_id uuid NOT NULL, idempotency_key character varying(255), status character varying(255) NOT NULL, transaction_reference character varying(255), CONSTRAINT payment_applied_events_status_check CHECK (((status)::text = ANY ((ARRAY['PAID'::character varying, 'PARTIALLY_PAID'::character varying, 'UNPAID'::character varying, 'FAILED'::character varying, 'CHARGEBACK'::character varying])::text[]))) );

CREATE TABLE posting_category ( is_active boolean NOT NULL, created_at timestamp(6) with time zone NOT NULL, modified_at timestamp(6) with time zone NOT NULL, posting_category_id uuid NOT NULL, created_by character varying(50) NOT NULL, modified_by character varying(50) NOT NULL, category_name character varying(100) NOT NULL, description character varying(500) );

CREATE TABLE posting_rule_set ( created_at timestamp(6) with time zone NOT NULL, modified_at timestamp(6) with time zone NOT NULL, posting_rule_set_id uuid NOT NULL, created_by character varying(50) NOT NULL, modified_by character varying(50) NOT NULL, event_type character varying(100) NOT NULL, name character varying(100) NOT NULL, description character varying(500) );

CREATE TABLE posting_rule_version ( version_number integer NOT NULL, archived_at timestamp(6) with time zone, created_at timestamp(6) with time zone NOT NULL, modified_at timestamp(6) with time zone NOT NULL, published_at timestamp(6) with time zone, posting_rule_set_id uuid NOT NULL, version_id uuid NOT NULL, state character varying(20) NOT NULL, archived_by character varying(50), created_by character varying(50) NOT NULL, modified_by character varying(50) NOT NULL, published_by character varying(50), rules_definition text NOT NULL, CONSTRAINT posting_rule_version_state_check CHECK (((state)::text = ANY ((ARRAY['DRAFT'::character varying, 'PUBLISHED'::character varying, 'ARCHIVED'::character varying])::text[]))) );

CREATE TABLE receivable_payment ( currency character varying(3) NOT NULL, total_amount numeric(19,4) NOT NULL, unapplied_amount numeric(19,4) NOT NULL, cleared_at timestamp(6) with time zone NOT NULL, created_at timestamp(6) with time zone NOT NULL, modified_at timestamp(6) with time zone, customer_id uuid NOT NULL, payment_id uuid NOT NULL, source_event_id uuid, status character varying(20) NOT NULL, created_by character varying(50), modified_by character varying(50), CONSTRAINT receivable_payment_status_check CHECK (((status)::text = ANY ((ARRAY['AVAILABLE'::character varying, 'FULLY_APPLIED'::character varying])::text[]))) );

CREATE TABLE reconciliation ( difference numeric(19,4) NOT NULL, gl_ending_balance numeric(19,4) NOT NULL, statement_ending_balance numeric(19,4) NOT NULL, created_at timestamp(6) with time zone NOT NULL, finalized_at timestamp(6) with time zone, period_end_date timestamp(6) without time zone NOT NULL, period_start_date timestamp(6) without time zone NOT NULL, statement_date timestamp(6) without time zone NOT NULL, gl_account_id uuid NOT NULL, reconciliation_id uuid NOT NULL, account_code character varying(20), status character varying(20) NOT NULL, created_by character varying(50) NOT NULL, finalized_by character varying(50), account_name character varying(100), adjustments jsonb, gl_transactions jsonb, statement_lines jsonb, CONSTRAINT reconciliation_status_check CHECK (((status)::text = ANY ((ARRAY['IN_PROGRESS'::character varying, 'FINALIZED'::character varying, 'CANCELLED'::character varying])::text[]))) );

CREATE TABLE reconciliation_records ( created_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, invoice_id uuid NOT NULL, transaction_id character varying(255) NOT NULL );

CREATE TABLE refund_policy_config ( active boolean NOT NULL, requires_separate_authorization boolean NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, config_id uuid NOT NULL, settled_payment_handling character varying(50) NOT NULL, unsettled_payment_handling character varying(50) NOT NULL, version character varying(50) NOT NULL );

CREATE TABLE reprocessing_attempt_history ( attempted_at timestamp(6) with time zone NOT NULL, attempt_id uuid NOT NULL, event_id uuid NOT NULL, outcome character varying(20) NOT NULL, mapping_version_used character varying(50), triggered_by_user_id character varying(100) NOT NULL, outcome_details character varying(4000), CONSTRAINT reprocessing_attempt_history_outcome_check CHECK (((outcome)::text = ANY ((ARRAY['SUCCESS'::character varying, 'FAILURE'::character varying])::text[]))) );

CREATE TABLE statement_line_mappings ( display_order integer, gl_account_id uuid NOT NULL, mapping_id uuid NOT NULL, operation character varying(50) NOT NULL, statement_type character varying(50) NOT NULL, parent_line_code character varying(100), statement_line_code character varying(100) NOT NULL, account_name character varying(255), line_description character varying(255), CONSTRAINT statement_line_mappings_operation_check CHECK (((operation)::text = ANY ((ARRAY['SUM'::character varying, 'SUBTRACT'::character varying, 'NEGATE'::character varying])::text[]))), CONSTRAINT statement_line_mappings_statement_type_check CHECK (((statement_type)::text = ANY ((ARRAY['INCOME_STATEMENT'::character varying, 'BALANCE_SHEET'::character varying])::text[]))) );

CREATE TABLE vendor_bill ( total_amount numeric(19,4) NOT NULL, approved_at timestamp(6) with time zone, bill_date timestamp(6) without time zone NOT NULL, created_at timestamp(6) with time zone NOT NULL, due_date timestamp(6) without time zone, modified_at timestamp(6) with time zone NOT NULL, paid_at timestamp(6) with time zone, rejected_at timestamp(6) with time zone, journal_entry_id uuid, origin_event_id uuid, payment_transaction_id uuid, purchase_order_id uuid, vendor_bill_id uuid NOT NULL, vendor_id uuid NOT NULL, status character varying(30) NOT NULL, approved_by character varying(50), bill_number character varying(50) NOT NULL, created_by character varying(50) NOT NULL, modified_by character varying(50) NOT NULL, paid_by character varying(50), purchase_order_number character varying(50), rejected_by character varying(50), origin_event_type character varying(100), vendor_name character varying(200), approval_justification character varying(1000), rejection_reason character varying(1000), CONSTRAINT vendor_bill_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING_RECEIPT_MATCH'::character varying, 'MATCH_EXCEPTION'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'PAID'::character varying, 'VOIDED'::character varying])::text[]))) );

CREATE TABLE vendor_bill_line ( is_inventory_item boolean NOT NULL, line_number integer NOT NULL, line_total numeric(19,4) NOT NULL, quantity numeric(19,4) NOT NULL, unit_price numeric(19,4) NOT NULL, line_id uuid NOT NULL, product_id uuid NOT NULL, vendor_bill_id uuid NOT NULL, sku character varying(100), description character varying(500) );

CREATE TABLE vendor_bill_match_candidate ( bill_total_amount numeric(19,4), match_score integer NOT NULL, resolved boolean NOT NULL, selected boolean NOT NULL, created_at timestamp(6) with time zone NOT NULL, resolved_at timestamp(6) with time zone, candidate_id uuid NOT NULL, invoice_event_id uuid NOT NULL, vendor_bill_id uuid NOT NULL, vendor_id uuid NOT NULL, bill_number character varying(50), resolved_by character varying(50), score_breakdown character varying(1000) );

ALTER TABLE accounting_audit_log ADD CONSTRAINT accounting_audit_log_pkey PRIMARY KEY (audit_log_id);

ALTER TABLE accounting_event ADD CONSTRAINT accounting_event_pkey PRIMARY KEY (event_id);

ALTER TABLE accounting_status_sync_audit ADD CONSTRAINT accounting_status_sync_audit_pkey PRIMARY KEY (id);

ALTER TABLE ap_payment_allocation ADD CONSTRAINT ap_payment_allocation_pkey PRIMARY KEY (allocation_id);

ALTER TABLE ap_payment ADD CONSTRAINT ap_payment_payment_ref_key UNIQUE (payment_ref);

ALTER TABLE ap_payment ADD CONSTRAINT ap_payment_pkey PRIMARY KEY (payment_id);

ALTER TABLE audit_trail_entry ADD CONSTRAINT audit_trail_entry_pkey PRIMARY KEY (audit_id);

ALTER TABLE credit_memo ADD CONSTRAINT credit_memo_pkey PRIMARY KEY (credit_memo_id);

ALTER TABLE customer_credit ADD CONSTRAINT customer_credit_pkey PRIMARY KEY (credit_id);

ALTER TABLE default_gl_mapping ADD CONSTRAINT default_gl_mapping_pkey PRIMARY KEY (mapping_id);

ALTER TABLE event_outbox ADD CONSTRAINT event_outbox_event_id_key UNIQUE (event_id);

ALTER TABLE event_outbox ADD CONSTRAINT event_outbox_pkey PRIMARY KEY (outbox_id);

ALTER TABLE gl_account ADD CONSTRAINT gl_account_account_code_key UNIQUE (account_code);

ALTER TABLE gl_account ADD CONSTRAINT gl_account_pkey PRIMARY KEY (gl_account_id);

ALTER TABLE gl_mapping ADD CONSTRAINT gl_mapping_pkey PRIMARY KEY (gl_mapping_id);

ALTER TABLE idempotency_keys ADD CONSTRAINT idempotency_keys_key_value_key UNIQUE (key_value);

ALTER TABLE idempotency_keys ADD CONSTRAINT idempotency_keys_pkey PRIMARY KEY (id);

ALTER TABLE invoice_status_views ADD CONSTRAINT invoice_status_views_invoice_id_key UNIQUE (invoice_id);

ALTER TABLE invoice_status_views ADD CONSTRAINT invoice_status_views_pkey PRIMARY KEY (id);

ALTER TABLE journal_entry_line ADD CONSTRAINT journal_entry_line_pkey PRIMARY KEY (line_id);

ALTER TABLE journal_entry ADD CONSTRAINT journal_entry_pkey PRIMARY KEY (journal_entry_id);

ALTER TABLE mapping_key ADD CONSTRAINT mapping_key_pkey PRIMARY KEY (mapping_key_id);

ALTER TABLE override_policy_threshold ADD CONSTRAINT override_policy_threshold_pkey PRIMARY KEY (policy_id);

ALTER TABLE payment_application ADD CONSTRAINT payment_application_pkey PRIMARY KEY (payment_application_id);

ALTER TABLE payment_application_reversal ADD CONSTRAINT payment_application_reversal_pkey PRIMARY KEY (reversal_id);

ALTER TABLE payment_applied_events ADD CONSTRAINT payment_applied_events_pkey PRIMARY KEY (id);

ALTER TABLE posting_category ADD CONSTRAINT posting_category_pkey PRIMARY KEY (posting_category_id);

ALTER TABLE posting_rule_set ADD CONSTRAINT posting_rule_set_pkey PRIMARY KEY (posting_rule_set_id);

ALTER TABLE posting_rule_version ADD CONSTRAINT posting_rule_version_pkey PRIMARY KEY (version_id);

ALTER TABLE receivable_payment ADD CONSTRAINT receivable_payment_pkey PRIMARY KEY (payment_id);

ALTER TABLE reconciliation ADD CONSTRAINT reconciliation_pkey PRIMARY KEY (reconciliation_id);

ALTER TABLE reconciliation_records ADD CONSTRAINT reconciliation_records_pkey PRIMARY KEY (id);

ALTER TABLE refund_policy_config ADD CONSTRAINT refund_policy_config_pkey PRIMARY KEY (config_id);

ALTER TABLE reprocessing_attempt_history ADD CONSTRAINT reprocessing_attempt_history_pkey PRIMARY KEY (attempt_id);

ALTER TABLE statement_line_mappings ADD CONSTRAINT statement_line_mappings_pkey PRIMARY KEY (mapping_id);

ALTER TABLE payment_application ADD CONSTRAINT uk_payment_application_idempotency UNIQUE (application_request_id, invoice_id);

ALTER TABLE posting_rule_version ADD CONSTRAINT uk_posting_rule_set_version UNIQUE (posting_rule_set_id, version_number);

ALTER TABLE receivable_payment ADD CONSTRAINT uk_receivable_payment_source_event UNIQUE (source_event_id);

ALTER TABLE payment_application_reversal ADD CONSTRAINT uk_reversal_original_application UNIQUE (original_payment_application_id);

ALTER TABLE vendor_bill_line ADD CONSTRAINT vendor_bill_line_pkey PRIMARY KEY (line_id);

ALTER TABLE vendor_bill_line ADD CONSTRAINT vendor_bill_line_vendor_bill_id_line_number_key UNIQUE (vendor_bill_id, line_number);

ALTER TABLE vendor_bill_match_candidate ADD CONSTRAINT vendor_bill_match_candidate_pkey PRIMARY KEY (candidate_id);

ALTER TABLE vendor_bill ADD CONSTRAINT vendor_bill_pkey PRIMARY KEY (vendor_bill_id);

CREATE INDEX idx_account_type ON gl_account USING btree (account_type);

CREATE INDEX idx_accounting_event_org_status ON accounting_event USING btree (organization_id, status);

CREATE INDEX idx_accounting_event_received_at ON accounting_event USING btree (received_at);

CREATE INDEX idx_accounting_event_source_system ON accounting_event USING btree (source_system);

CREATE INDEX idx_accounting_event_status ON accounting_event USING btree (status);

CREATE INDEX idx_accounting_event_transaction_date ON accounting_event USING btree (transaction_date);

CREATE INDEX idx_accounting_event_type ON accounting_event USING btree (event_type);

CREATE INDEX idx_activation_date ON gl_account USING btree (activation_date);

CREATE INDEX idx_actor_id ON audit_trail_entry USING btree (actor_id);

CREATE INDEX idx_ap_payment_allocation_bill ON ap_payment_allocation USING btree (vendor_bill_id);

CREATE INDEX idx_ap_payment_allocation_payment ON ap_payment_allocation USING btree (payment_id);

CREATE INDEX idx_ap_payment_date ON ap_payment USING btree (payment_date);

CREATE INDEX idx_ap_payment_status ON ap_payment USING btree (status);

CREATE INDEX idx_ap_payment_vendor ON ap_payment USING btree (vendor_id);

CREATE INDEX idx_ap_payment_vendor_bill ON ap_payment USING btree (vendor_bill_id);

CREATE INDEX idx_audit_log_entity ON accounting_audit_log USING btree (entity_type, entity_id);

CREATE INDEX idx_audit_log_operation ON accounting_audit_log USING btree (operation);

CREATE INDEX idx_audit_log_timestamp ON accounting_audit_log USING btree ("timestamp");

CREATE INDEX idx_audit_log_trace ON accounting_audit_log USING btree (trace_id);

CREATE INDEX idx_audit_log_user ON accounting_audit_log USING btree (user_id);

CREATE INDEX idx_category_name ON posting_category USING btree (category_name);

CREATE INDEX idx_credit_memo_customer ON credit_memo USING btree (customer_id);

CREATE INDEX idx_credit_memo_original_invoice ON credit_memo USING btree (original_invoice_id);

CREATE INDEX idx_credit_memo_posted_timestamp ON credit_memo USING btree (posted_timestamp);

CREATE INDEX idx_credit_memo_status ON credit_memo USING btree (status);

CREATE INDEX idx_customer_credit_created_at ON customer_credit USING btree (created_at);

CREATE INDEX idx_customer_credit_customer ON customer_credit USING btree (customer_id);

CREATE INDEX idx_customer_credit_payment ON customer_credit USING btree (source_payment_id);

CREATE INDEX idx_deactivation_date ON gl_account USING btree (deactivation_date);

CREATE INDEX idx_default_gl_mapping_active ON default_gl_mapping USING btree (active);

CREATE INDEX idx_default_gl_mapping_event_type ON default_gl_mapping USING btree (event_type, organization_id);

CREATE INDEX idx_exception_type ON audit_trail_entry USING btree (exception_type);

CREATE INDEX idx_gl_mapping_category_key ON gl_mapping USING btree (posting_category_id, mapping_key_id);

CREATE INDEX idx_gl_mapping_effective_from ON gl_mapping USING btree (effective_start_date);

CREATE INDEX idx_gl_mapping_effective_to ON gl_mapping USING btree (effective_end_date);

CREATE INDEX idx_gl_mapping_gl_account ON gl_mapping USING btree (gl_account_id);

CREATE INDEX idx_gl_mapping_source_code ON gl_mapping USING btree (source_system, external_code);

CREATE INDEX idx_invoice_id ON audit_trail_entry USING btree (invoice_id);

CREATE INDEX idx_journal_entry_entry_type ON journal_entry USING btree (entry_type);

CREATE INDEX idx_journal_entry_line_gl_account ON journal_entry_line USING btree (gl_account_id);

CREATE INDEX idx_journal_entry_line_je ON journal_entry_line USING btree (journal_entry_id);

CREATE INDEX idx_journal_entry_posted_at ON journal_entry USING btree (posted_at);

CREATE INDEX idx_journal_entry_source_event ON journal_entry USING btree (source_event_id);

CREATE INDEX idx_journal_entry_status ON journal_entry USING btree (status);

CREATE INDEX idx_journal_entry_transaction_date ON journal_entry USING btree (transaction_date);

CREATE INDEX idx_mapping_key_category ON mapping_key USING btree (posting_category_id);

CREATE INDEX idx_mapping_key_name ON mapping_key USING btree (key_name);

CREATE INDEX idx_match_candidate_bill ON vendor_bill_match_candidate USING btree (vendor_bill_id);

CREATE INDEX idx_match_candidate_invoice_event ON vendor_bill_match_candidate USING btree (invoice_event_id);

CREATE INDEX idx_match_candidate_resolved ON vendor_bill_match_candidate USING btree (resolved);

CREATE INDEX idx_order_id ON audit_trail_entry USING btree (order_id);

CREATE INDEX idx_outbox_aggregate ON event_outbox USING btree (aggregate_type, aggregate_id);

CREATE INDEX idx_outbox_event_type ON event_outbox USING btree (event_type);

CREATE INDEX idx_outbox_status_created ON event_outbox USING btree (status, created_at);

CREATE INDEX idx_payment_application_customer ON payment_application USING btree (customer_id);

CREATE INDEX idx_payment_application_payment ON payment_application USING btree (payment_id);

CREATE INDEX idx_payment_application_timestamp ON payment_application USING btree (application_timestamp);

CREATE INDEX idx_posting_rule_set_event_type ON posting_rule_set USING btree (event_type);

CREATE INDEX idx_posting_rule_set_name ON posting_rule_set USING btree (name);

CREATE INDEX idx_posting_rule_version_set ON posting_rule_version USING btree (posting_rule_set_id);

CREATE INDEX idx_posting_rule_version_state ON posting_rule_version USING btree (state);

CREATE INDEX idx_receivable_payment_cleared_at ON receivable_payment USING btree (cleared_at);

CREATE INDEX idx_receivable_payment_customer ON receivable_payment USING btree (customer_id);

CREATE INDEX idx_receivable_payment_status ON receivable_payment USING btree (status);

CREATE INDEX idx_reconciliation_account ON reconciliation USING btree (gl_account_id);

CREATE INDEX idx_reconciliation_period ON reconciliation USING btree (period_start_date, period_end_date);

CREATE INDEX idx_reconciliation_status ON reconciliation USING btree (status);

CREATE INDEX idx_reprocessing_attempted_at ON reprocessing_attempt_history USING btree (attempted_at);

CREATE INDEX idx_reprocessing_event_id ON reprocessing_attempt_history USING btree (event_id);

CREATE INDEX idx_reprocessing_outcome ON reprocessing_attempt_history USING btree (outcome);

CREATE INDEX idx_reversal_reversed_at ON payment_application_reversal USING btree (reversed_at);

CREATE INDEX idx_role_effective ON override_policy_threshold USING btree (role, effective_date);

CREATE INDEX idx_statement_line_mapping_account ON statement_line_mappings USING btree (gl_account_id);

CREATE INDEX idx_statement_line_mapping_code ON statement_line_mappings USING btree (statement_line_code);

CREATE INDEX idx_statement_line_mapping_type ON statement_line_mappings USING btree (statement_type);

CREATE INDEX idx_timestamp ON audit_trail_entry USING btree ("timestamp");

CREATE INDEX idx_vendor_bill_due_date ON vendor_bill USING btree (due_date);

CREATE INDEX idx_vendor_bill_line_bill ON vendor_bill_line USING btree (vendor_bill_id);

CREATE INDEX idx_vendor_bill_line_product ON vendor_bill_line USING btree (product_id);

CREATE INDEX idx_vendor_bill_number ON vendor_bill USING btree (bill_number);

CREATE INDEX idx_vendor_bill_origin_event ON vendor_bill USING btree (origin_event_id);

CREATE INDEX idx_vendor_bill_po ON vendor_bill USING btree (purchase_order_id);

CREATE INDEX idx_vendor_bill_status ON vendor_bill USING btree (status);

CREATE INDEX idx_vendor_bill_vendor ON vendor_bill USING btree (vendor_id);

ALTER TABLE payment_application ADD CONSTRAINT fk13tlgsck9ordhxmucwpq1j9l4 FOREIGN KEY (payment_id) REFERENCES receivable_payment(payment_id);

ALTER TABLE posting_rule_version ADD CONSTRAINT fk1k0vuemxkyhch04s7qbik3y2b FOREIGN KEY (posting_rule_set_id) REFERENCES posting_rule_set(posting_rule_set_id);

ALTER TABLE gl_mapping ADD CONSTRAINT fk20bd93ep5luf4mmod7rro9axb FOREIGN KEY (posting_category_id) REFERENCES posting_category(posting_category_id);

ALTER TABLE journal_entry_line ADD CONSTRAINT fk3kgo8dodt5qp8tmcyb14q67d FOREIGN KEY (journal_entry_id) REFERENCES journal_entry(journal_entry_id);

ALTER TABLE payment_application_reversal ADD CONSTRAINT fk5vhh1awc8ocuxhylxh3dklbsw FOREIGN KEY (original_payment_application_id) REFERENCES payment_application(payment_application_id);

ALTER TABLE default_gl_mapping ADD CONSTRAINT fk7iu7sn11e7uulcgqojaqv2ooa FOREIGN KEY (credit_account_id) REFERENCES gl_account(gl_account_id);

ALTER TABLE journal_entry ADD CONSTRAINT fk8fqoyfngcnwjn6y7sy3i0so3a FOREIGN KEY (reversal_journal_entry_id) REFERENCES journal_entry(journal_entry_id);

ALTER TABLE gl_mapping ADD CONSTRAINT fk8t41wk3huitw5tnvtuslisbqm FOREIGN KEY (mapping_key_id) REFERENCES mapping_key(mapping_key_id);

ALTER TABLE ap_payment_allocation ADD CONSTRAINT fkapnd2vwyt2vf9lrg7csh53cc7 FOREIGN KEY (vendor_bill_id) REFERENCES vendor_bill(vendor_bill_id);

ALTER TABLE vendor_bill_line ADD CONSTRAINT fkbfjj8yxlu90fkpyyee1us666p FOREIGN KEY (vendor_bill_id) REFERENCES vendor_bill(vendor_bill_id);

ALTER TABLE vendor_bill_match_candidate ADD CONSTRAINT fkcblnw1ni6kli051ybfxev7avm FOREIGN KEY (vendor_bill_id) REFERENCES vendor_bill(vendor_bill_id);

ALTER TABLE mapping_key ADD CONSTRAINT fkdyebuum5bfueyvg5iro4mwnpa FOREIGN KEY (posting_category_id) REFERENCES posting_category(posting_category_id);

ALTER TABLE gl_mapping ADD CONSTRAINT fkf8a3hgiabmsmxqds6vmaym8qr FOREIGN KEY (gl_account_id) REFERENCES gl_account(gl_account_id);

ALTER TABLE reconciliation ADD CONSTRAINT fkh8ebp0sl1i78d1qsxc6tsslfp FOREIGN KEY (gl_account_id) REFERENCES gl_account(gl_account_id);

ALTER TABLE journal_entry ADD CONSTRAINT fkhmynfi0fvnh0khs3ilm97st6s FOREIGN KEY (posting_rule_version_id) REFERENCES posting_rule_version(version_id);

ALTER TABLE journal_entry ADD CONSTRAINT fkjs8j9vdtoe98qy4jhb4aol5ma FOREIGN KEY (reversed_by_journal_entry_id) REFERENCES journal_entry(journal_entry_id);

ALTER TABLE statement_line_mappings ADD CONSTRAINT fkmg7jatkm8r484nhs63ny7nf4m FOREIGN KEY (gl_account_id) REFERENCES gl_account(gl_account_id);

ALTER TABLE vendor_bill ADD CONSTRAINT fkmkxa6je21m7vt2nbxvp1s3b1w FOREIGN KEY (journal_entry_id) REFERENCES journal_entry(journal_entry_id);

ALTER TABLE journal_entry_line ADD CONSTRAINT fkmtkt0lv332mn6up5drvrw4ebt FOREIGN KEY (gl_account_id) REFERENCES gl_account(gl_account_id);

ALTER TABLE ap_payment ADD CONSTRAINT fkpm45vmxsx01ge5tm2kqe959kf FOREIGN KEY (gl_journal_entry_id) REFERENCES journal_entry(journal_entry_id);

ALTER TABLE default_gl_mapping ADD CONSTRAINT fkpnfbixwea64vk90uhkfwpu2x1 FOREIGN KEY (debit_account_id) REFERENCES gl_account(gl_account_id);

ALTER TABLE journal_entry ADD CONSTRAINT fkps53tpo5honto09dkdgyosqgk FOREIGN KEY (posting_rule_set_id) REFERENCES posting_rule_set(posting_rule_set_id);

ALTER TABLE gl_account ADD CONSTRAINT fkqclauift0l4qc1gim5i71mc98 FOREIGN KEY (parent_account_id) REFERENCES gl_account(gl_account_id);

ALTER TABLE ap_payment ADD CONSTRAINT fkrqalxry8feddr5vnogddq4ylm FOREIGN KEY (vendor_bill_id) REFERENCES vendor_bill(vendor_bill_id);

ALTER TABLE reprocessing_attempt_history ADD CONSTRAINT fksdacgrbs8ygddvb8p1onw6bc9 FOREIGN KEY (event_id) REFERENCES accounting_event(event_id);

ALTER TABLE ap_payment_allocation ADD CONSTRAINT fksuxctgp80wk9fip2gtse0fynh FOREIGN KEY (payment_id) REFERENCES ap_payment(payment_id);
