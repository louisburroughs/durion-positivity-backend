-- Collapsed baseline for pos-accounting generated from emitted Postgres schema.

-- Source dump: /tmp/pos_invoice_baseline_emitted.sql

-- Repeatable seed/data migrations remain separate and are not folded into this file.



SET TIME ZONE 'UTC';



CREATE TABLE billing_rules ( purchase_order_required boolean NOT NULL, version integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, invoice_delivery_method character varying(20) NOT NULL, invoice_grouping_strategy character varying(30) NOT NULL, party_id character varying(36) NOT NULL, updated_by character varying(36) NOT NULL, payment_terms_code character varying(50) NOT NULL, CONSTRAINT billing_rules_invoice_delivery_method_check CHECK (((invoice_delivery_method)::text = ANY ((ARRAY['EMAIL'::character varying, 'PORTAL'::character varying, 'MAIL'::character varying])::text[]))), CONSTRAINT billing_rules_invoice_grouping_strategy_check CHECK (((invoice_grouping_strategy)::text = ANY ((ARRAY['PER_WORKORDER'::character varying, 'PER_VEHICLE'::character varying, 'SINGLE_INVOICE'::character varying])::text[]))) );

CREATE TABLE invoice_adjustments ( amount numeric(19,4) NOT NULL, created_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, invoice_id uuid NOT NULL, type character varying(20) NOT NULL, authorized_by character varying(64) NOT NULL, reason character varying(512) NOT NULL, CONSTRAINT invoice_adjustments_type_check CHECK (((type)::text = ANY ((ARRAY['DISCOUNT'::character varying, 'FEE'::character varying, 'CORRECTION'::character varying])::text[]))) );

CREATE TABLE invoice_items ( line_total numeric(19,4) NOT NULL, quantity numeric(19,4) NOT NULL, unit_price numeric(19,4) NOT NULL, id uuid NOT NULL, invoice_id uuid NOT NULL, workorder_item_id uuid, description character varying(512) NOT NULL );

CREATE TABLE invoices ( adjustments_amount numeric(19,4) NOT NULL, subtotal numeric(19,4) NOT NULL, tax_amount numeric(19,4) NOT NULL, total_amount numeric(19,4) NOT NULL, version integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, finalized_at timestamp(6) with time zone, updated_at timestamp(6) with time zone NOT NULL, approval_id uuid, estimate_id uuid, id uuid NOT NULL, workorder_id uuid NOT NULL, status character varying(20) NOT NULL, customer_id character varying(64), finalized_by character varying(64), invoice_number character varying(64), CONSTRAINT invoices_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'FINALIZED'::character varying, 'POSTED'::character varying, 'ERROR'::character varying])::text[]))) );

CREATE TABLE payment_intents ( authorized_amount numeric(15,2), captured_amount numeric(15,2), voided_remainder_amount numeric(15,2), created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, invoice_id uuid NOT NULL, payment_flow character varying(20) NOT NULL, status character varying(20) NOT NULL, gateway_provider character varying(50), gateway_reference character varying(100), idempotency_key character varying(128) NOT NULL, gateway_response text, payment_token character varying(255) NOT NULL, CONSTRAINT payment_intents_payment_flow_check CHECK (((payment_flow)::text = ANY ((ARRAY['SALE_CAPTURE'::character varying, 'AUTH_ONLY'::character varying])::text[]))), CONSTRAINT payment_intents_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'AUTHORIZED'::character varying, 'CAPTURED'::character varying, 'CAPTURE_FAILED'::character varying, 'VOIDED'::character varying, 'EXPIRED'::character varying])::text[]))) );

CREATE TABLE receipts ( reprint_count integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, delivery_method character varying(10), delivery_status character varying(10), id uuid NOT NULL, invoice_id uuid NOT NULL, payment_intent_id uuid NOT NULL, status character varying(20) NOT NULL, template_version character varying(32) NOT NULL, reference character varying(64) NOT NULL, terminal_id character varying(64) NOT NULL, cashier_id character varying(128) NOT NULL, last_reprinted_by character varying(128), template_id character varying(128) NOT NULL, delivery_email_address character varying(256), last_reprint_reason character varying(256), CONSTRAINT receipts_delivery_method_check CHECK (((delivery_method)::text = ANY ((ARRAY['PRINT'::character varying, 'EMAIL'::character varying])::text[]))), CONSTRAINT receipts_delivery_status_check CHECK (((delivery_status)::text = ANY ((ARRAY['SUCCESS'::character varying, 'FAILED'::character varying])::text[]))), CONSTRAINT receipts_status_check CHECK (((status)::text = 'GENERATED'::text)) );

CREATE TABLE refund_records ( amount numeric(19,4) NOT NULL, completed_at timestamp(6) with time zone, created_at timestamp(6) with time zone NOT NULL, requested_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, invoice_id uuid NOT NULL, payment_intent_id uuid NOT NULL, status character varying(20) NOT NULL, reason character varying(30) NOT NULL, requested_by character varying(128) NOT NULL, gateway_reference character varying(256), notes character varying(1024), CONSTRAINT refund_records_reason_check CHECK (((reason)::text = ANY ((ARRAY['CUSTOMER_RETURN'::character varying, 'SERVICE_ERROR'::character varying, 'OVERCHARGE'::character varying, 'DAMAGED_GOODS'::character varying, 'GOODWILL'::character varying, 'CHARGEBACK_AVOIDANCE'::character varying, 'FRAUD_PREVENTION'::character varying, 'MANAGER_DISCRETION'::character varying, 'OTHER'::character varying])::text[]))), CONSTRAINT refund_records_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[]))) );

ALTER TABLE billing_rules ADD CONSTRAINT billing_rules_party_id_key UNIQUE (party_id);

ALTER TABLE billing_rules ADD CONSTRAINT billing_rules_pkey PRIMARY KEY (id);

ALTER TABLE invoice_adjustments ADD CONSTRAINT invoice_adjustments_pkey PRIMARY KEY (id);

ALTER TABLE invoice_items ADD CONSTRAINT invoice_items_pkey PRIMARY KEY (id);

ALTER TABLE invoices ADD CONSTRAINT invoices_pkey PRIMARY KEY (id);

ALTER TABLE payment_intents ADD CONSTRAINT payment_intents_idempotency_key_key UNIQUE (idempotency_key);

ALTER TABLE payment_intents ADD CONSTRAINT payment_intents_pkey PRIMARY KEY (id);

ALTER TABLE receipts ADD CONSTRAINT receipts_pkey PRIMARY KEY (id);

ALTER TABLE receipts ADD CONSTRAINT receipts_reference_key UNIQUE (reference);

ALTER TABLE refund_records ADD CONSTRAINT refund_records_pkey PRIMARY KEY (id);

ALTER TABLE receipts ADD CONSTRAINT fk3hmid8b40s5yd0jo2s36684ql FOREIGN KEY (invoice_id) REFERENCES invoices(id);

ALTER TABLE invoice_items ADD CONSTRAINT fk46ae0lhu1oqs7cv91fn6y9n7w FOREIGN KEY (invoice_id) REFERENCES invoices(id);

ALTER TABLE refund_records ADD CONSTRAINT fk5g4w2npjrmc4r1ktid7kssfpi FOREIGN KEY (invoice_id) REFERENCES invoices(id);

ALTER TABLE payment_intents ADD CONSTRAINT fk8d5o0e14e7pi75wj8tiddlcvr FOREIGN KEY (invoice_id) REFERENCES invoices(id);

ALTER TABLE invoice_adjustments ADD CONSTRAINT fkd0x10ud3y581kk2luh2g58jce FOREIGN KEY (invoice_id) REFERENCES invoices(id);

ALTER TABLE refund_records ADD CONSTRAINT fker3axvi18hhhp3flqraoxg3ay FOREIGN KEY (payment_intent_id) REFERENCES payment_intents(id);

ALTER TABLE receipts ADD CONSTRAINT fkfbqduvxs5411vrk6du06vwv4s FOREIGN KEY (payment_intent_id) REFERENCES payment_intents(id);
