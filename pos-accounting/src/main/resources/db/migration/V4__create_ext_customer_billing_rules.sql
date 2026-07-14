-- ADR-0044 / issue #842 Phase 1: read-only replica of pos-customer billing rules, populated
-- exclusively by the customer.events.v1 consumer. Never written by accounting business logic.
CREATE TABLE ext_customer_billing_rules (
    party_id uuid NOT NULL,
    po_required boolean,
    tax_exempt boolean,
    credit_hold boolean,
    auto_pay_enabled boolean,
    payment_terms character varying(255),
    credit_limit numeric(19,2),
    currency character varying(16),
    invoice_delivery_method character varying(64),
    billing_address_id uuid,
    discount_policy_ref character varying(255),
    aggregate_version bigint NOT NULL DEFAULT 0,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_customer_billing_rules_pkey PRIMARY KEY (party_id)
);

-- ADR-0044 §4: consumer idempotency — one row per processed eventId, inserted in the same
-- transaction as the replica update so redelivery is harmless.
CREATE TABLE processed_events (
    event_id character varying(36) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id)
);
