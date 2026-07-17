-- Issue #927: expected-credit tracking for warranty vendor reimbursements.
-- Fed by warranty.events.v1 (warranty.reimbursement.submitted / .resolved); pos-warranty
-- owns the reimbursement lifecycle — this table is accounting's read-side expectation
-- record for AP credit matching. Never written by accounting business logic.
CREATE TABLE warranty_reimbursement_expectation (
    reimbursement_id uuid NOT NULL,
    claim_id uuid NOT NULL,
    claim_code character varying(64),
    provider_id uuid,
    ap_vendor_id uuid,
    amount_requested numeric(12, 2),
    vendor_claim_reference character varying(128),
    status character varying(32) NOT NULL,
    amount_approved numeric(12, 2),
    credit_reference character varying(128),
    submitted_at timestamp(6) with time zone,
    resolved_at timestamp(6) with time zone,
    aggregate_version bigint NOT NULL DEFAULT 0,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT warranty_reimbursement_expectation_pkey PRIMARY KEY (reimbursement_id)
);

CREATE INDEX idx_warranty_reimb_expectation_ap_vendor ON warranty_reimbursement_expectation (ap_vendor_id);
CREATE INDEX idx_warranty_reimb_expectation_status ON warranty_reimbursement_expectation (status);
