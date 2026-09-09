-- CAP-320 / #1226 (+ #1318): purchase-order transmission intents and their lines.
--
-- WHAT THIS TABLE IS
--
-- The ledger of what we told a vendor to deliver, and the queue that tells it. ADR-0052 §2 requires
-- the transmission intent and its outbox row to be written in one transaction; they are written as
-- ONE ROW, which satisfies that by construction rather than by care -- there is no window in which
-- an intent exists without work queued, or work queued for an intent that rolled back. Dispatch
-- drains attempt_state = 'PENDING' in transmission_intent_id order, and UUIDv7 ids are time-ordered,
-- so a vendor receives one customer's orders in the order they were placed.
--
-- WHAT IT DELIBERATELY DOES NOT HOLD
--
-- No vendor payloads. Raw request and response documents live in supplier_exchange_audit, where
-- ADR-0050 §7 governs their encryption, redaction, retention and access. A second copy alongside the
-- ledger would be a second copy under none of those rules. last_status_fingerprint -- a hash -- is
-- kept instead, which is enough to publish supplier.orderstatus.changed on real transitions without
-- holding a commercial document per poll.
--
-- H2(MODE=PostgreSQL)-compatible, matching V2/V3/V8/V9/V10.
--
-- No foreign key to supplier_profile, for the reason V3 records: ADR-0050 §6/§7 require the
-- transmission record to outlive the configuration that produced it. An order we placed last quarter
-- is still an order we placed, whatever happened to the profile since.
CREATE TABLE supplier_transmission_intent (
    transmission_intent_id uuid NOT NULL,

    vendor_profile_id uuid NOT NULL,
    supplier_ref character varying(100) NOT NULL,

    purchase_order_id uuid NOT NULL,
    purchase_order_number character varying(64),
    intent_type character varying(32) NOT NULL,
    revision integer DEFAULT 0 NOT NULL,

    -- The wire DocumentID/CustomerReference, derived deterministically from
    -- transmission_intent_id (ADR-0052 §1). 35 characters is the C1.0 cap on the field; sending 36
    -- would risk silent truncation at the vendor, and a truncated reference is one an order-status
    -- query can no longer find.
    document_id character varying(35) NOT NULL,

    -- ADR-0052 §1 requires at most one ACTIVE intent per
    -- (vendor_profile_id, intent_type, purchase_order_id, revision). Enforced as a plain unique
    -- index over this one derived column rather than a partial index over four, because partial
    -- unique indexes are not portable to the H2 the dev profile runs on -- and a constraint that
    -- only exists in production is not a constraint, it is a hope.
    --
    -- The column holds the tuple while the intent has a claim on it and is set NULL when the intent
    -- ends in a way that makes re-ordering legitimate (REJECTED, FAILED, CANCELLED); NULLs do not
    -- collide in a unique index on either database. CONFIRMED keeps its key FOREVER: a vendor that
    -- accepted an order holds that tuple for good, so a re-issued command for it is recognised as
    -- the repeat it is instead of becoming a second truckload.
    active_intent_key character varying(255),

    attempt_state character varying(32) NOT NULL,

    delivery_location_id uuid,
    billing_account_number character varying(64),
    protocol_version character varying(64),

    -- Vendor-native reference. An attribute, never a key (ADR-0013/0027).
    supplier_order_number character varying(64),
    vendor_error_code character varying(16),
    vendor_reason character varying(2000),

    dispatch_attempts integer DEFAULT 0 NOT NULL,
    dispatched_at timestamp(6) with time zone,
    result_at timestamp(6) with time zone,

    last_status_fingerprint character varying(64),
    -- When the vendor's answer last CHANGED, not when it was last asked for: staleness is measured
    -- against this, so a vendor answering identically every hour does not keep an abandoned order
    -- alive forever.
    last_status_at timestamp(6) with time zone,
    -- When the vendor was last asked. Drives poll ordering, and nothing else.
    last_polled_at timestamp(6) with time zone,
    last_status_state character varying(32),
    event_sequence bigint DEFAULT 0 NOT NULL,

    latest_scheduled_delivery_date date,

    -- Polling continues well past CONFIRMED. Confirmation is where the delivery story STARTS --
    -- every fact receiving needs (confirmed dates, despatches, ASN references) arrives after it --
    -- so stopping there would produce a purchase-order timeline with no arrivals on it (#1318).
    status_polling_active boolean DEFAULT false NOT NULL,
    polling_stopped_reason character varying(64),

    resolution_action character varying(32),
    resolved_by character varying(100),
    resolved_at timestamp(6) with time zone,
    -- The operator's free-text account of what they established (ADR-0052 §4). Weeks later this is
    -- the only record of why the system believes a vendor did or did not receive an order.
    resolution_evidence character varying(2000),

    correlation_id character varying(100),
    last_error character varying(2000),

    created_at timestamp(6) with time zone NOT NULL,
    created_by character varying(100),
    updated_at timestamp(6) with time zone,

    CONSTRAINT pk_supplier_transmission_intent PRIMARY KEY (transmission_intent_id),
    CONSTRAINT uq_stintent_document_id UNIQUE (document_id),
    CONSTRAINT uq_stintent_active_key UNIQUE (active_intent_key),
    CONSTRAINT ck_stintent_intent_type
        CHECK (intent_type IN ('INITIAL', 'REVISION', 'REPLACEMENT', 'SPLIT')),
    -- DISPATCHING is listed like any other state, and it is the dangerous one: it means the request
    -- body may have reached the vendor. Nothing in this module automatically re-sends from it
    -- (ADR-0052 §2) -- recovery asks the vendor, or escalates to MANUAL_REVIEW.
    CONSTRAINT ck_stintent_attempt_state
        CHECK (attempt_state IN ('PENDING', 'DISPATCHING', 'SENT_AWAITING_RESULT', 'CONFIRMED',
                                 'REJECTED', 'MANUAL_REVIEW', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_stintent_revision CHECK (revision >= 0)
);

CREATE INDEX idx_stintent_purchase_order ON supplier_transmission_intent (purchase_order_id);
CREATE INDEX idx_stintent_state ON supplier_transmission_intent (attempt_state, transmission_intent_id);
CREATE INDEX idx_stintent_polling ON supplier_transmission_intent (status_polling_active, last_polled_at);
CREATE INDEX idx_stintent_profile ON supplier_transmission_intent (vendor_profile_id);

-- ── Transmitted lines ───────────────────────────────────────────────────────────────────────
--
-- A command arrives, an intent is minted, and dispatch happens later -- possibly after a restart,
-- possibly after a circuit breaker closes. The document has to be reconstructible at that point from
-- state that committed WITH the intent, or a re-dispatch would either need the original Kafka
-- message back or would send a different order under the same document id.
--
-- requested_delivery_date is the date we ASKED for, and stays that forever. What the vendor confirms
-- is a different fact that arrives on the status feed and is never written back here: a missing
-- confirmed date is not the requested date, and merging them would show receiving a vendor
-- commitment that only ever existed as our own request (#1318).
CREATE TABLE supplier_transmission_line (
    line_id uuid NOT NULL,

    transmission_intent_id uuid NOT NULL,

    line_number integer NOT NULL,
    article_ean character varying(64),
    -- Display alias only. Never an identifier (ADR-0013/0027).
    supplier_article_code character varying(64),
    quantity integer NOT NULL,
    requested_delivery_date date,

    created_at timestamp(6) with time zone NOT NULL,

    CONSTRAINT pk_supplier_transmission_line PRIMARY KEY (line_id),
    CONSTRAINT fk_stline_intent
        FOREIGN KEY (transmission_intent_id) REFERENCES supplier_transmission_intent (transmission_intent_id),
    CONSTRAINT ck_stline_line_number CHECK (line_number >= 1),
    CONSTRAINT ck_stline_quantity CHECK (quantity >= 1)
);

CREATE INDEX idx_stline_intent ON supplier_transmission_line (transmission_intent_id, line_number);
