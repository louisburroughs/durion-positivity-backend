-- Story T3 (decision D-T1): the tax exemption-certificate registry lives in pos-tax
-- because an exemption is tax semantics, not CRM identity. This is the first table in
-- pos-tax (previously a stateless calculator). An active, effective, non-expired
-- certificate drives an honored exemption on the calculate path; a claimed exemption with
-- no valid certificate is denied and the line taxed anyway (D-T2). Written Postgres-style
-- and validated on H2 (PostgreSQL mode) in tests.
CREATE TABLE exemption_certificate (
    id                 uuid NOT NULL,
    customer_id        character varying(100) NOT NULL,
    state_scope        character varying(10),
    reason_code        character varying(32) NOT NULL,
    certificate_number character varying(100),
    effective_from     date NOT NULL,
    expires_at         date,
    status             character varying(16) NOT NULL,
    created_at         timestamp(6) with time zone NOT NULL,
    updated_at         timestamp(6) with time zone NOT NULL,
    CONSTRAINT exemption_certificate_pkey PRIMARY KEY (id)
);

-- Calculate-path lookup: active certificates for a customer effective on a given date.
CREATE INDEX idx_exemption_certificate_customer
    ON exemption_certificate (customer_id, status, effective_from);
