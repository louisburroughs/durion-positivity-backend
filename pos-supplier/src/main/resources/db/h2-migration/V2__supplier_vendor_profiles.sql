-- ADR-0050: vendor profile configuration model — one profile per supplier account per
-- deployment (§2), with three child collections: auth configs (§4), commercial accounts
-- (§5), and capability endpoint bindings (§3). Identity is vendor_profile_id (UUIDv7 minted
-- in the JVM via @UUIDv7Id, ADR-0013/0027); supplier_ref is a unique human-readable alias
-- (§1), never an identifier crossing a contract boundary.
--
-- DDL is kept H2(MODE=PostgreSQL)-compatible so the dev profile and @DataJpaTest slices can
-- run these migrations (no partial indexes; UNIQUE NULLS NOT DISTINCT is supported by both
-- H2 2.2+ and PostgreSQL 15+ — deployment runs pg16).

-- §1/§2: the vendor profile — identity, display name, protocol defaults, sandbox overlay,
-- and the sourceOfTruth marker (§6: YAML-managed profiles are reconciled from deployment
-- configuration on every startup and reject admin mutations).
CREATE TABLE supplier_profile (
    vendor_profile_id uuid NOT NULL,
    supplier_ref character varying(100) NOT NULL,
    display_name character varying(255) NOT NULL,
    enabled boolean NOT NULL,
    sandbox boolean NOT NULL DEFAULT false,
    sandbox_base_url_override character varying(1024),
    connect_timeout_ms integer,
    read_timeout_ms integer,
    retry_max_attempts integer,
    retry_backoff character varying(32),
    source_of_truth character varying(16) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    created_by character varying(255),
    updated_by character varying(255),
    version bigint NOT NULL,
    CONSTRAINT supplier_profile_pkey PRIMARY KEY (vendor_profile_id),
    CONSTRAINT supplier_profile_supplier_ref_key UNIQUE (supplier_ref),
    CONSTRAINT chk_sprofile_source_of_truth CHECK (source_of_truth IN ('YAML', 'ADMIN')),
    CONSTRAINT chk_sprofile_retry_backoff
        CHECK (retry_backoff IS NULL OR retry_backoff IN ('FIXED', 'EXPONENTIAL')),
    CONSTRAINT chk_sprofile_connect_timeout
        CHECK (connect_timeout_ms IS NULL OR connect_timeout_ms > 0),
    CONSTRAINT chk_sprofile_read_timeout CHECK (read_timeout_ms IS NULL OR read_timeout_ms > 0),
    CONSTRAINT chk_sprofile_retry_max CHECK (retry_max_attempts IS NULL OR retry_max_attempts >= 0)
);

-- §4: auth configs — secret *references* only (env: / secret-store keys) resolved at call
-- time; plaintext credentials never persist. api_key_header is the plain header NAME the
-- API key is sent under, not a secret.
CREATE TABLE supplier_auth_config (
    id uuid NOT NULL,
    vendor_profile_id uuid NOT NULL,
    name character varying(100) NOT NULL,
    type character varying(64) NOT NULL,
    username_ref character varying(512),
    password_ref character varying(512),
    api_key_ref character varying(512),
    api_key_header character varying(100),
    token_url_ref character varying(512),
    client_id_ref character varying(512),
    client_secret_ref character varying(512),
    bearer_token_ref character varying(512),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    created_by character varying(255),
    updated_by character varying(255),
    version bigint NOT NULL,
    CONSTRAINT supplier_auth_config_pkey PRIMARY KEY (id),
    CONSTRAINT fk_sauth_profile FOREIGN KEY (vendor_profile_id)
        REFERENCES supplier_profile (vendor_profile_id),
    CONSTRAINT supplier_auth_config_profile_name_key UNIQUE (vendor_profile_id, name),
    CONSTRAINT chk_sauth_type
        CHECK (type IN ('BASIC_PLUS_APIKEY', 'OAUTH2_CLIENT_CREDENTIALS', 'BEARER'))
);

CREATE INDEX idx_sauth_profile ON supplier_auth_config (vendor_profile_id);

-- §5: commercial accounts — billing (one per profile, legal-entity level) and delivery
-- (one per pos-location). DELIVERY requires a location; BILLING forbids one. The
-- NULLS NOT DISTINCT unique constraint enforces both "one BILLING per profile" (its
-- delivery_location_id is NULL) and "one DELIVERY per (profile, location)".
CREATE TABLE supplier_account (
    id uuid NOT NULL,
    vendor_profile_id uuid NOT NULL,
    role character varying(16) NOT NULL,
    account_number character varying(100) NOT NULL,
    agency_code character varying(50),
    delivery_location_id uuid,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    created_by character varying(255),
    updated_by character varying(255),
    version bigint NOT NULL,
    CONSTRAINT supplier_account_pkey PRIMARY KEY (id),
    CONSTRAINT fk_saccount_profile FOREIGN KEY (vendor_profile_id)
        REFERENCES supplier_profile (vendor_profile_id),
    CONSTRAINT chk_saccount_role CHECK (role IN ('BILLING', 'DELIVERY')),
    CONSTRAINT chk_saccount_role_location CHECK (
        (role = 'DELIVERY' AND delivery_location_id IS NOT NULL)
        OR (role = 'BILLING' AND delivery_location_id IS NULL)),
    CONSTRAINT supplier_account_profile_role_location_key
        UNIQUE NULLS NOT DISTINCT (vendor_profile_id, role, delivery_location_id)
);

CREATE INDEX idx_saccount_profile ON supplier_account (vendor_profile_id);

-- §3: capability endpoint bindings — at most one binding per (profile, capability); an
-- absent (or disabled) binding means the capability is disabled for the profile.
-- protocol_version is data, not an enum (ADR-0051 §3): vendors add norm versions without a
-- code change. auth_config_name references supplier_auth_config.name within the same
-- profile; the pair is validated on write at the service layer (no cross-name DB FK by
-- design — renames are rejected while referenced). capture_level is the exchange-audit
-- payload capture level (§7); NULL means the deployment default.
CREATE TABLE supplier_endpoint_binding (
    id uuid NOT NULL,
    vendor_profile_id uuid NOT NULL,
    capability character varying(64) NOT NULL,
    protocol_family character varying(64) NOT NULL,
    protocol_version character varying(64) NOT NULL,
    base_url character varying(1024) NOT NULL,
    path character varying(512) NOT NULL,
    auth_config_name character varying(100) NOT NULL,
    schedule_cron character varying(100),
    enabled boolean NOT NULL,
    capture_level character varying(32),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    created_by character varying(255),
    updated_by character varying(255),
    version bigint NOT NULL,
    CONSTRAINT supplier_endpoint_binding_pkey PRIMARY KEY (id),
    CONSTRAINT fk_sbinding_profile FOREIGN KEY (vendor_profile_id)
        REFERENCES supplier_profile (vendor_profile_id),
    CONSTRAINT supplier_endpoint_binding_profile_capability_key UNIQUE (vendor_profile_id, capability),
    CONSTRAINT chk_sbinding_capability CHECK (capability IN (
        'ORDER_CREATE', 'ORDER_STATUS', 'STOCK_INQUIRY', 'STOCK_REPORT', 'PRICE_CATALOG',
        'INVOICE_FETCH', 'SHIPMENT_TRACKING', 'WORKORDER_AUTHORIZATION', 'MARKETING_CATALOG',
        'TIRE_IDENTIFICATION')),
    CONSTRAINT chk_sbinding_family CHECK (protocol_family IN (
        'EDIWHEEL_A25', 'EDIWHEEL_C1', 'EDIWHEEL_B', 'EDIWHEEL_JSON', 'MICHELIN_S2S', 'TEST')),
    CONSTRAINT chk_sbinding_capture_level
        CHECK (capture_level IS NULL OR capture_level IN ('FULL', 'REDACTED', 'METADATA_ONLY'))
);

CREATE INDEX idx_sbinding_profile ON supplier_endpoint_binding (vendor_profile_id);
