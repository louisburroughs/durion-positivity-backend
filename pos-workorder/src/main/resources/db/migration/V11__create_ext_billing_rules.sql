-- ADR-0044 §6 (#902): read-only billing-rules replica fed by invoice.billing-rules.updated
-- facts on invoice.events.v1. Replaces the synchronous billing-rules read that backed
-- purchase-order enforcement (CAP:092 #98).
CREATE TABLE ext_billing_rules (
    party_id varchar(36) NOT NULL,
    purchase_order_required boolean NOT NULL DEFAULT false,
    payment_terms_code varchar(50),
    invoice_delivery_method varchar(20),
    invoice_grouping_strategy varchar(30),
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_billing_rules_pkey PRIMARY KEY (party_id)
);
