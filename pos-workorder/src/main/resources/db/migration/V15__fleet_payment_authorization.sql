-- CAP-323 (#1346): fleet payment authorization — the payer's permission to do the work.
--
-- Named for what the domain calls it. "Fleet payment authorization" is a Commercial Customer
-- agreeing to pay; it is NOT workorder approval, NOT estimate customer consent, and NOT
-- change-request customer consent. AWAITING_APPROVAL keeps its existing meaning (customer consent
-- for added scope during execution) and is deliberately untouched.

-- The policy flag, replicated from pos-invoice's billing rules (ADR-0044 R3).
-- A policy of the payer rather than of the job: the same commercial account answers the same way for
-- every workorder it pays for.
ALTER TABLE ext_billing_rules
    ADD COLUMN IF NOT EXISTS fleet_authorization_required BOOLEAN NOT NULL DEFAULT FALSE;

-- Which fleet program authorises for this party. Useless apart from the flag: knowing authorization
-- is required without knowing who to ask blocks every workorder with nowhere to go.
ALTER TABLE ext_billing_rules
    ADD COLUMN IF NOT EXISTS fleet_supplier_ref VARCHAR(100);


-- Vehicle identity, replicated from vehicle.events.v1 (ADR-0044 R3).
--
-- Needed because a fleet program identifies a vehicle by plate, VIN or fleet unit number, and this
-- domain holds only a vehicleId UUID. pos-vehicle-inventory is a Domain module under ADR-0044, so it
-- is read through its published facts rather than called — the same way pos-warranty already reads
-- vehicle data.
--
-- Only the identifying fields are replicated. This is not a second vehicle registry: nothing here is
-- authoritative, nothing is written back, and anything not needed to name a vehicle to a fleet
-- program is deliberately absent.
CREATE TABLE IF NOT EXISTS ext_vehicle (
    vehicle_id        UUID         PRIMARY KEY,
    vin               VARCHAR(64),
    license_plate     VARCHAR(32),
    unit_number       VARCHAR(64),
    odometer_value    INTEGER,
    odometer_unit     VARCHAR(16),
    aggregate_version BIGINT       NOT NULL,
    updated_at        timestamp(6) with time zone NOT NULL
);


-- One authorization lifecycle per workorder. Separate from workorder_status on purpose: the domain
-- ruled this is a payer-authorization lifecycle, not an execution state, and folding it into the
-- state machine would have made "who is paying" and "how far along is the work" the same question.
CREATE TABLE workorder_fleet_authorization (
    workorder_fleet_authorization_id UUID         PRIMARY KEY,
    workorder_id                     UUID         NOT NULL,

    -- The fleet program asked, and its profile id once a decision names one.
    supplier_ref                     VARCHAR(100) NOT NULL,
    vendor_profile_id                UUID,

    -- NOT_REQUESTED, PENDING, GRANTED, REFUSED, MANUAL_REVIEW, CANCELLED.
    --
    -- GRANTED, REFUSED and PENDING are the vendor's three answers and are preserved without
    -- reinterpretation, per the domain ruling. MANUAL_REVIEW is a *pending disposition*, never a
    -- denial: it means nobody could get an answer, and it keeps the start gate closed exactly as
    -- PENDING does. A timeout or a communication failure must never manufacture a refusal.
    status                           VARCHAR(20)  NOT NULL,

    -- The vendor's own words, kept unchanged. A refusal reason is what an advisor quotes back to a
    -- fleet manager, so translating it would cost the conversation its evidence.
    vendor_reason_code               VARCHAR(50),
    vendor_reason_text               VARCHAR(1000),

    -- Why this needs a person, when no vendor answer could be obtained. Never presented as a
    -- vendor decision.
    review_reason                    VARCHAR(1000),

    contract_reference               VARCHAR(100),
    authorized_amount                NUMERIC(19, 4),
    currency                         VARCHAR(3),

    -- Advisor resolution of a refusal: what they did and why. Nothing is automatic — the domain
    -- ruled a refusal leaves the workorder open for an explicit decision.
    resolution                       VARCHAR(30),
    resolution_reason                VARCHAR(1000),
    resolved_by                      VARCHAR(100),
    resolved_at                      timestamp(6) with time zone,

    requested_at                     timestamp(6) with time zone,
    decided_at                       timestamp(6) with time zone,

    -- When the start gate first refused a start on this workorder. The delayed release of held
    -- resources is measured from here rather than from the request, because a job nobody has tried
    -- to start is not holding anything up.
    first_blocked_at                 timestamp(6) with time zone,
    resources_released_at            timestamp(6) with time zone,

    created_at                       timestamp(6) with time zone NOT NULL,
    updated_at                       timestamp(6) with time zone NOT NULL,
    created_by                       VARCHAR(100),
    updated_by                       VARCHAR(100),

    CONSTRAINT ck_wfa_status CHECK (
        status IN ('NOT_REQUESTED', 'PENDING', 'GRANTED', 'REFUSED', 'MANUAL_REVIEW', 'CANCELLED')
    ),
    CONSTRAINT ck_wfa_resolution CHECK (
        resolution IS NULL OR resolution IN ('SCOPE_REVISED', 'CANCELLED')
    ),
    -- An amount without a currency is not a sum of money.
    CONSTRAINT ck_wfa_amount_currency CHECK (authorized_amount IS NULL OR currency IS NOT NULL)
);

-- One live authorization per workorder. This is what makes a re-request an update rather than a
-- second opinion: two rows would let a refusal and a grant coexist with nothing to say which one
-- the start gate should honour.
CREATE UNIQUE INDEX ux_wfa_workorder ON workorder_fleet_authorization (workorder_id);

-- The gate's lookup, and the release runner's working set.
CREATE INDEX ix_wfa_status ON workorder_fleet_authorization (status, first_blocked_at);
