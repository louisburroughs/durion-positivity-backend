CREATE TABLE self_registration_attempts (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    request_fingerprint VARCHAR(128) NOT NULL,
    email VARCHAR(255) NOT NULL,
    username VARCHAR(255),
    status VARCHAR(20) NOT NULL,
    user_id UUID,
    person_id UUID,
    link_status VARCHAR(50),
    matched_existing_person BOOLEAN NOT NULL DEFAULT FALSE,
    issued_tokens BOOLEAN NOT NULL DEFAULT FALSE,
    crm_candidate_count INT,
    crm_any_matches BOOLEAN,
    crm_individual_customer_candidate_count INT,
    crm_commercial_contact_candidate_count INT,
    crm_shared_identity_candidate_count INT,
    crm_exact_email_match BOOLEAN,
    crm_exact_phone_match BOOLEAN,
    crm_exact_name_match BOOLEAN,
    crm_review_required BOOLEAN,
    conflict_code VARCHAR(100),
    conflict_message VARCHAR(1000),
    reference_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_self_registration_attempts_email
    ON self_registration_attempts(email);

CREATE TABLE self_registration_review_cases (
    id UUID PRIMARY KEY,
    case_type VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason_code VARCHAR(100) NOT NULL,
    reason_message VARCHAR(1000) NOT NULL,
    email VARCHAR(255) NOT NULL,
    requested_username VARCHAR(255),
    person_id UUID,
    linked_user_id UUID,
    crm_candidate_count INT,
    crm_shared_identity_candidate_count INT,
    crm_exact_email_match BOOLEAN,
    crm_exact_phone_match BOOLEAN,
    crm_exact_name_match BOOLEAN,
    notes VARCHAR(1000),
    resolved_at TIMESTAMP WITH TIME ZONE,
    resolved_by VARCHAR(255),
    resolution_notes VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_self_registration_review_cases_status
    ON self_registration_review_cases(status);

CREATE INDEX idx_self_registration_review_cases_case_type_status
    ON self_registration_review_cases(case_type, status);
