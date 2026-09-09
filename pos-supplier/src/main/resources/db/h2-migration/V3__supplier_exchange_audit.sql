-- ADR-0050 §7: the exchange audit trail — one row per outbound ATTEMPT, including failures,
-- retries and attempts suppressed by an open breaker. Plus the scheduler lease table
-- (binding decision 4) that keeps one binding's scheduled run from double-firing across
-- instances.
--
-- DDL is kept H2(MODE=PostgreSQL)-compatible so the dev profile and @DataJpaTest slices can
-- run these migrations, matching V2's constraint.

-- ── Exchange audit ─────────────────────────────────────────────────────────────────────
--
-- DELIBERATELY NO FOREIGN KEY to supplier_profile (binding decision: no FK; snapshot
-- vendor_profile_id + supplier_ref). Two reasons, and they pull the same way:
--
--   1. ADR-0050 §6/§7 require history to outlive configuration. Admin deleteProfile is a hard
--      delete of the profile and its children; an FK would either block that delete or cascade
--      it and destroy commercial evidence of exchanges that really happened.
--   2. An audit row must remain interpretable after its profile is gone, so supplier_ref is
--      snapshotted here as it was AT THE TIME of the exchange. supplier_ref is renameable
--      (ADR-0050 §1), so it is descriptive only and must never be used as a join or filter
--      key -- vendor_profile_id is the identity for every query, grouping and metric.
--
-- Payload columns are bytea holding an AES-256-GCM envelope (binding decision 1). They are
-- NULLABLE, and null is a normal state, not a defect: a METADATA_ONLY binding never captures
-- payloads, and the retention purge nulls them while keeping the metadata row. Never add a
-- NOT NULL here.
CREATE TABLE supplier_exchange_audit (
    exchange_audit_id uuid NOT NULL,

    -- Identity + descriptive snapshot.
    vendor_profile_id uuid NOT NULL,
    supplier_ref character varying(100) NOT NULL,
    binding_id uuid,

    -- What was attempted.
    capability character varying(64) NOT NULL,
    protocol_family character varying(64) NOT NULL,
    protocol_version character varying(32) NOT NULL,
    http_method character varying(16) NOT NULL,
    endpoint_uri character varying(2048) NOT NULL,

    -- How it went.
    attempt integer NOT NULL,
    correlation_id character varying(100) NOT NULL,
    outcome character varying(32) NOT NULL,
    http_status integer,
    started_at timestamp(6) with time zone NOT NULL,
    duration_ms bigint NOT NULL,
    failure_detail character varying(2048),

    -- Capture level actually applied, so a reader can tell "no payload captured" from
    -- "payload purged" from "payload absent because the vendor sent none".
    capture_level character varying(16) NOT NULL,
    request_payload bytea,
    response_payload bytea,
    payloads_purged_at timestamp(6) with time zone,

    -- Reserved per ADR-0051 §4: canonical fields the bound norm could not express, recorded so
    -- nothing is silently dropped. Populated once codecs land (CAP-318).
    unmapped_fields character varying(4000),

    created_at timestamp(6) with time zone NOT NULL,
    created_by character varying(255),

    CONSTRAINT supplier_exchange_audit_pkey PRIMARY KEY (exchange_audit_id),
    CONSTRAINT chk_saudit_attempt CHECK (attempt >= 1),
    CONSTRAINT chk_saudit_duration CHECK (duration_ms >= 0),
    CONSTRAINT chk_saudit_capture_level
        CHECK (capture_level IN ('FULL', 'REDACTED', 'METADATA_ONLY')),
    CONSTRAINT chk_saudit_outcome CHECK (outcome IN (
        'OK',
        'PRE_SEND_FAILURE',
        'POST_SEND_AMBIGUOUS',
        'DEFINITIVE_REJECTION',
        'CONFIGURATION_ERROR')),
    -- METADATA_ONLY must never carry payloads. Enforced in the schema rather than only in the
    -- writer, because the whole point of a capture level is that it cannot be bypassed.
    CONSTRAINT chk_saudit_metadata_only_has_no_payload CHECK (
        capture_level <> 'METADATA_ONLY'
        OR (request_payload IS NULL AND response_payload IS NULL))
);

-- The audit read API's primary access path: one supplier's recent exchanges, newest first.
CREATE INDEX idx_saudit_profile_started ON supplier_exchange_audit (vendor_profile_id, started_at);

-- Narrowing that path by capability, which is how an operator actually investigates ("what
-- happened to Michelin stock inquiries last night").
CREATE INDEX idx_saudit_profile_capability_started
    ON supplier_exchange_audit (vendor_profile_id, capability, started_at);

-- Tracing one operator action or one scheduled run across every attempt it caused.
CREATE INDEX idx_saudit_correlation ON supplier_exchange_audit (correlation_id);

-- The retention purge scans by age; without this it table-scans 400 days of rows nightly.
CREATE INDEX idx_saudit_started_at ON supplier_exchange_audit (started_at);

-- ── Scheduler lease (binding decision 4) ───────────────────────────────────────────────
--
-- One row per schedulable binding. Overlap protection is an atomic compare-and-claim UPDATE
-- against DB now(), never JVM time: instances' clocks drift, and a lease decided by the
-- claimant's own clock is not a lease at all.
CREATE TABLE supplier_schedule_lease (
    binding_id uuid NOT NULL,
    vendor_profile_id uuid NOT NULL,
    capability character varying(64) NOT NULL,

    -- Claim state. owner_token identifies the claiming run so heartbeat, checkpoint and release
    -- can all be owner-guarded: a run whose lease was stolen must not keep writing.
    owner_token character varying(100),
    leased_until timestamp(6) with time zone,
    last_heartbeat_at timestamp(6) with time zone,

    -- Checkpoint: the end of the last window successfully processed. Advances only on success,
    -- so a missed night self-heals by reprocessing from here.
    checkpoint_at timestamp(6) with time zone,
    last_run_started_at timestamp(6) with time zone,
    last_run_outcome character varying(32),

    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,

    CONSTRAINT supplier_schedule_lease_pkey PRIMARY KEY (binding_id),
    CONSTRAINT fk_slease_binding FOREIGN KEY (binding_id)
        REFERENCES supplier_endpoint_binding (id) ON DELETE CASCADE,
    -- A lease row is meaningless once its binding is gone, so unlike the audit trail this one
    -- DOES cascade: it is transient coordination state, not history.
    CONSTRAINT chk_slease_claim_consistent CHECK (
        (owner_token IS NULL AND leased_until IS NULL)
        OR (owner_token IS NOT NULL AND leased_until IS NOT NULL))
);

-- The claim query orders due bindings by checkpoint age.
CREATE INDEX idx_slease_leased_until ON supplier_schedule_lease (leased_until);
