-- Baseline schema for pos-warranty (PRD docs/PRD-warranty-claims-module.md §3, §4, §10).
-- One database per service (pos_warranty_db); UUID v7 primary keys generated in the JVM
-- (@UUIDv7Id, ADR-0013); no cross-service foreign keys — other services are referenced by
-- bare UUID columns plus denormalized snapshots frozen at claim time.
-- DDL is kept H2(MODE=PostgreSQL)-compatible so the dev profile and @DataJpaTest slices
-- can run these migrations too (no partial indexes, no Postgres-only syntax).

SET TIME ZONE 'UTC';

-- §3.1 WarrantyProvider — who pays.
CREATE TABLE warranty_provider (
    id uuid NOT NULL,
    name character varying(255) NOT NULL,
    status character varying(32) NOT NULL,
    provider_type character varying(32) NOT NULL,
    ap_vendor_id uuid,
    manufacturer_id uuid,
    claim_submission_method character varying(255),
    portal_url character varying(1024),
    contact_name character varying(255),
    contact_phone character varying(50),
    contact_email character varying(255),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    created_by character varying(255),
    updated_by character varying(255),
    version bigint NOT NULL,
    CONSTRAINT warranty_provider_pkey PRIMARY KEY (id)
);

CREATE INDEX idx_wprovider_manufacturer ON warranty_provider (manufacturer_id);
CREATE INDEX idx_wprovider_ap_vendor ON warranty_provider (ap_vendor_id);

-- §3.2 WarrantyPolicy — structured coverage terms.
CREATE TABLE warranty_policy (
    id uuid NOT NULL,
    provider_id uuid NOT NULL,
    name character varying(255) NOT NULL,
    coverage_type character varying(32) NOT NULL,
    applies_to_type character varying(32) NOT NULL,
    applies_to_product_entity_ids jsonb,
    applies_to_category_id uuid,
    applies_to_manufacturer_id uuid,
    effective_from date NOT NULL,
    effective_to date,
    duration_months integer,
    mileage_limit integer,
    tread_pull_point_thirty_seconds integer,
    labor_covered boolean NOT NULL DEFAULT false,
    labor_hours_cap numeric(9,2),
    labor_rate_cap numeric(19,4),
    proration_method character varying(32) NOT NULL,
    requires_part_return boolean NOT NULL DEFAULT false,
    requires_photo_evidence boolean NOT NULL DEFAULT false,
    transferable boolean NOT NULL DEFAULT false,
    terms_text text,
    document_urls jsonb,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    created_by character varying(255),
    updated_by character varying(255),
    version bigint NOT NULL,
    CONSTRAINT warranty_policy_pkey PRIMARY KEY (id),
    CONSTRAINT fk_wpolicy_provider FOREIGN KEY (provider_id) REFERENCES warranty_provider (id)
);

CREATE INDEX idx_wpolicy_provider ON warranty_policy (provider_id);
CREATE INDEX idx_wpolicy_coverage_type ON warranty_policy (coverage_type);

-- §3.3 WarrantyRegistration — sold/instantiated coverage.
CREATE TABLE warranty_registration (
    id uuid NOT NULL,
    policy_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    vehicle_id uuid,
    source_invoice_id uuid,
    source_invoice_line_id uuid,
    contract_number character varying(100),
    purchase_date date NOT NULL,
    expires_at date,
    status character varying(32) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    created_by character varying(255),
    updated_by character varying(255),
    version bigint NOT NULL,
    CONSTRAINT warranty_registration_pkey PRIMARY KEY (id),
    CONSTRAINT fk_wreg_policy FOREIGN KEY (policy_id) REFERENCES warranty_policy (id)
);

CREATE INDEX idx_wreg_customer ON warranty_registration (customer_id);
CREATE INDEX idx_wreg_vehicle ON warranty_registration (vehicle_id);
CREATE INDEX idx_wreg_policy ON warranty_registration (policy_id);
CREATE INDEX idx_wreg_status ON warranty_registration (status);

-- §3.4 WarrantyClaim — the aggregate root.
CREATE TABLE warranty_claim (
    id uuid NOT NULL,
    claim_code character varying(20) NOT NULL,
    claim_type character varying(32) NOT NULL,
    status character varying(32) NOT NULL,
    location_id uuid,
    customer_id uuid NOT NULL,
    vehicle_id uuid,
    vin character varying(17),
    odometer_at_claim bigint,
    odometer_unit character varying(16),
    origin_workorder_id uuid,
    origin_invoice_id uuid,
    origin_sale_date date,
    origin_unverified boolean NOT NULL DEFAULT false,
    provider_id uuid,
    policy_id uuid,
    registration_id uuid,
    failure_description text,
    failure_date date,
    photo_evidence_urls jsonb,
    eligibility_result character varying(32),
    eligibility_reasons jsonb,
    suggested_outcome jsonb,
    decision character varying(32),
    decision_reason text,
    decided_by character varying(255),
    decided_at timestamp(6) with time zone,
    overrode_suggestion boolean NOT NULL DEFAULT false,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    created_by character varying(255),
    updated_by character varying(255),
    version bigint NOT NULL,
    CONSTRAINT warranty_claim_pkey PRIMARY KEY (id),
    CONSTRAINT warranty_claim_claim_code_key UNIQUE (claim_code),
    CONSTRAINT fk_wclaim_provider FOREIGN KEY (provider_id) REFERENCES warranty_provider (id),
    CONSTRAINT fk_wclaim_policy FOREIGN KEY (policy_id) REFERENCES warranty_policy (id),
    CONSTRAINT fk_wclaim_registration FOREIGN KEY (registration_id) REFERENCES warranty_registration (id)
);

CREATE INDEX idx_wclaim_customer ON warranty_claim (customer_id);
CREATE INDEX idx_wclaim_vehicle ON warranty_claim (vehicle_id);
CREATE INDEX idx_wclaim_status ON warranty_claim (status);
CREATE INDEX idx_wclaim_location ON warranty_claim (location_id);

-- §3.5 WarrantyClaimLine — one per failed part or service (SalesOrderLine provenance pattern).
CREATE TABLE warranty_claim_line (
    id uuid NOT NULL,
    claim_id uuid NOT NULL,
    source_type character varying(32) NOT NULL,
    source_id uuid,
    source_line_id uuid,
    product_entity_id uuid,
    sku character varying(100),
    description character varying(512),
    serial_number character varying(100),
    dot_number character varying(20),
    quantity numeric(19,4) NOT NULL,
    original_unit_price numeric(19,4),
    original_tread_depth integer,
    measured_tread_depth integer,
    proration_pct numeric(9,6),
    proration_inputs jsonb,
    amount_requested numeric(19,4),
    amount_approved numeric(19,4),
    line_disposition character varying(32),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    created_by character varying(255),
    updated_by character varying(255),
    CONSTRAINT warranty_claim_line_pkey PRIMARY KEY (id),
    CONSTRAINT fk_wline_claim FOREIGN KEY (claim_id) REFERENCES warranty_claim (id)
);

CREATE INDEX idx_wline_claim ON warranty_claim_line (claim_id);

-- §3.6 ClaimSettlement — how the customer was made whole (a claim can have several).
CREATE TABLE claim_settlement (
    id uuid NOT NULL,
    claim_id uuid NOT NULL,
    settlement_type character varying(32) NOT NULL,
    status character varying(32) NOT NULL,
    replacement_workorder_id uuid,
    invoice_id uuid,
    invoice_adjustment_id uuid,
    refund_record_id uuid,
    covered_amount numeric(19,4),
    customer_amount numeric(19,4),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    created_by character varying(255),
    updated_by character varying(255),
    CONSTRAINT claim_settlement_pkey PRIMARY KEY (id),
    CONSTRAINT fk_csettlement_claim FOREIGN KEY (claim_id) REFERENCES warranty_claim (id)
);

CREATE INDEX idx_csettlement_claim ON claim_settlement (claim_id);

-- §3.7 VendorReimbursement — back-office money lifecycle (one per claim per provider).
CREATE TABLE vendor_reimbursement (
    id uuid NOT NULL,
    claim_id uuid NOT NULL,
    provider_id uuid NOT NULL,
    status character varying(32) NOT NULL,
    amount_requested numeric(19,4),
    amount_approved numeric(19,4),
    vendor_claim_reference character varying(100),
    submitted_at timestamp(6) with time zone,
    submitted_by character varying(255),
    resolved_at timestamp(6) with time zone,
    credit_reference character varying(100),
    credit_received_at timestamp(6) with time zone,
    notes text,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    created_by character varying(255),
    updated_by character varying(255),
    CONSTRAINT vendor_reimbursement_pkey PRIMARY KEY (id),
    CONSTRAINT fk_vreimb_claim FOREIGN KEY (claim_id) REFERENCES warranty_claim (id),
    CONSTRAINT fk_vreimb_provider FOREIGN KEY (provider_id) REFERENCES warranty_provider (id)
);

CREATE INDEX idx_vreimb_claim ON vendor_reimbursement (claim_id);
CREATE INDEX idx_vreimb_status ON vendor_reimbursement (status);
CREATE INDEX idx_vreimb_provider ON vendor_reimbursement (provider_id);

-- §3.8 PartReturn — defective-part RMA (at most one per claim line).
CREATE TABLE part_return (
    id uuid NOT NULL,
    claim_id uuid NOT NULL,
    claim_line_id uuid NOT NULL,
    rma_number character varying(100),
    disposition character varying(32) NOT NULL,
    status character varying(32) NOT NULL,
    carrier character varying(100),
    tracking_number character varying(100),
    shipped_at timestamp(6) with time zone,
    hold_location_note text,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    created_by character varying(255),
    updated_by character varying(255),
    CONSTRAINT part_return_pkey PRIMARY KEY (id),
    CONSTRAINT part_return_claim_line_key UNIQUE (claim_line_id),
    CONSTRAINT fk_preturn_claim FOREIGN KEY (claim_id) REFERENCES warranty_claim (id),
    CONSTRAINT fk_preturn_claim_line FOREIGN KEY (claim_line_id) REFERENCES warranty_claim_line (id)
);

CREATE INDEX idx_preturn_claim ON part_return (claim_id);
CREATE INDEX idx_preturn_status ON part_return (status);

-- §3.9 ClaimStatusHistory — append-only status transitions.
CREATE TABLE claim_status_history (
    id uuid NOT NULL,
    claim_id uuid NOT NULL,
    from_status character varying(32),
    to_status character varying(32) NOT NULL,
    actor character varying(255),
    reason text,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT claim_status_history_pkey PRIMARY KEY (id),
    CONSTRAINT fk_chistory_claim FOREIGN KEY (claim_id) REFERENCES warranty_claim (id)
);

CREATE INDEX idx_chistory_claim ON claim_status_history (claim_id);

-- §3.9 ClaimNote — free-form staff notes.
CREATE TABLE claim_note (
    id uuid NOT NULL,
    claim_id uuid NOT NULL,
    note text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    created_by character varying(255),
    CONSTRAINT claim_note_pkey PRIMARY KEY (id),
    CONSTRAINT fk_cnote_claim FOREIGN KEY (claim_id) REFERENCES warranty_claim (id)
);

CREATE INDEX idx_cnote_claim ON claim_note (claim_id);

-- §4 Claim-code allocation: WC-{yyyy}-{seq}, 6-digit zero-padded sequence resetting yearly.
-- Table-based (not a native sequence) so allocation works identically on H2 (dev) and
-- Postgres, and can be locked pessimistically (SELECT ... FOR UPDATE) by ClaimCodeService.
-- NOTE: column is code_year because YEAR is a reserved word in H2.
CREATE TABLE claim_code_sequence (
    code_year integer NOT NULL,
    next_seq bigint NOT NULL,
    CONSTRAINT claim_code_sequence_pkey PRIMARY KEY (code_year)
);

-- Pre-seed a generous year range so concurrent first-claim-of-a-year allocations never race
-- on the bootstrap insert (ClaimCodeService still self-heals for years beyond this range).
INSERT INTO claim_code_sequence (code_year, next_seq) VALUES
    (2024, 1), (2025, 1), (2026, 1), (2027, 1), (2028, 1), (2029, 1), (2030, 1), (2031, 1),
    (2032, 1), (2033, 1), (2034, 1), (2035, 1), (2036, 1), (2037, 1), (2038, 1), (2039, 1),
    (2040, 1), (2041, 1), (2042, 1), (2043, 1), (2044, 1), (2045, 1), (2046, 1), (2047, 1),
    (2048, 1), (2049, 1), (2050, 1), (2051, 1), (2052, 1), (2053, 1), (2054, 1), (2055, 1),
    (2056, 1), (2057, 1), (2058, 1), (2059, 1), (2060, 1), (2061, 1), (2062, 1), (2063, 1),
    (2064, 1), (2065, 1), (2066, 1), (2067, 1), (2068, 1), (2069, 1), (2070, 1), (2071, 1),
    (2072, 1), (2073, 1), (2074, 1), (2075, 1);

-- ADR-0044 §4: transactional outbox for domain events (warranty.events.v1, PRD §9.3).
-- Rows are written in the same transaction as the business state change and
-- drained to Kafka by a background publisher (at-least-once delivery).
CREATE TABLE event_outbox (
    id uuid NOT NULL,
    topic character varying(255) NOT NULL,
    record_key character varying(255) NOT NULL,
    payload text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    published_at timestamp(6) with time zone,
    attempts integer NOT NULL DEFAULT 0,
    last_error text,
    CONSTRAINT event_outbox_pkey PRIMARY KEY (id)
);

-- Drain scans: unpublished rows in id order (UUIDv7 ids are time-ordered). Plain composite
-- index instead of the Postgres partial index used elsewhere, for H2 (dev) compatibility.
CREATE INDEX idx_event_outbox_unpublished ON event_outbox (published_at, id);

-- ADR-0044 §4: consumer-side idempotency log, one row per applied envelope eventId,
-- written in the same transaction as the replica update (used when pos-warranty consumes
-- other domains' topics; owner = producing domain).
CREATE TABLE processed_events (
    event_id character varying(36) NOT NULL,
    owner character varying(64) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id)
);

CREATE INDEX idx_processed_events_owner_event ON processed_events (owner, event_id);
