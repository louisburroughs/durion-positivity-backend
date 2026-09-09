-- CAP-323 (#1229): fleet workorder authorization at the vendor (Michelin S2S).
--
-- One row per workorder authorization. The vendor's decision is an input to pos-workorder's
-- workflow, never a copy of workorder state: nothing here records what the workorder is doing,
-- only what the fleet program said about it (ADR-0049 §2).
--
-- The row exists from the moment the request is made, not from the moment a decision arrives.
-- An authorization requested and never answered is exactly the case an operator needs to see,
-- and a table that only holds decisions cannot show it.

CREATE TABLE supplier_workorder_authorization (
    supplier_workorder_authorization_id UUID         PRIMARY KEY,
    vendor_profile_id                   UUID         NOT NULL,
    supplier_ref                        VARCHAR(100) NOT NULL,
    workorder_id                        UUID         NOT NULL,

    -- PENDING, GRANTED, DENIED, NOT_FOUND, MANUAL_REVIEW.
    -- MANUAL_REVIEW is deliberately not a vendor answer: it is this side giving up on getting one.
    status                              VARCHAR(20)  NOT NULL,

    -- The vendor's own id for the authorization. Needed to approve completion later, so a GRANTED
    -- row without one is a defect worth being able to query for.
    vendor_authorization_id             VARCHAR(100),

    -- Where an asynchronously accepted request said its result would appear (HTTP 202 Location).
    -- Null on the synchronous path. Without this a 202 is indistinguishable from a lost request.
    poll_location                       VARCHAR(1000),

    contract_reference                  VARCHAR(100),
    authorized_amount                   NUMERIC(19, 4),
    currency                            VARCHAR(3),

    -- The vendor's refusal, as stated. Not translated: the code is what a shop quotes back.
    reason_code                         VARCHAR(50),
    reason_text                         VARCHAR(1000),

    -- Why this row needs a human. Set with MANUAL_REVIEW and never on its own.
    review_reason                       VARCHAR(1000),

    -- Completion approval (PATCH .../approve), tracked separately from the authorization decision
    -- because they fail independently: work can be authorized and still fail to be signed off.
    approval_status                     VARCHAR(20)  NOT NULL DEFAULT 'NOT_REQUESTED',
    approval_attempts                   INTEGER      NOT NULL DEFAULT 0,
    approved_at                         timestamp(6) with time zone,

    requested_at                        timestamp(6) with time zone  NOT NULL,
    decided_at                          timestamp(6) with time zone,
    last_polled_at                      timestamp(6) with time zone,

    created_at                          timestamp(6) with time zone  NOT NULL,
    updated_at                          timestamp(6) with time zone  NOT NULL,

    CONSTRAINT ck_swa_status CHECK (status IN ('PENDING', 'GRANTED', 'DENIED', 'NOT_FOUND', 'MANUAL_REVIEW')),
    CONSTRAINT ck_swa_approval_status CHECK (
        approval_status IN ('NOT_REQUESTED', 'PENDING', 'APPROVED', 'MANUAL_REVIEW')
    ),
    -- An amount without a currency is not a sum of money.
    CONSTRAINT ck_swa_amount_currency CHECK (authorized_amount IS NULL OR currency IS NOT NULL)
);

-- One live authorization per workorder per vendor. This is what makes a re-requested authorization
-- an update rather than a second opinion: two rows would let a denial and a grant coexist with
-- nothing to say which one the shop should act on.
CREATE UNIQUE INDEX ux_swa_profile_workorder
    ON supplier_workorder_authorization (vendor_profile_id, workorder_id);

-- The poller's working set: rows still waiting on a vendor, least recently polled first.
-- Not a partial index, though only PENDING rows are ever read through it: H2 backs the dev profile
-- and does not support them, and an index that exists on Postgres and not in test is an index whose
-- query plan nothing exercises before production.
CREATE INDEX ix_swa_status_polled ON supplier_workorder_authorization (status, last_polled_at);

-- The approver's working set, and an operator's queue of stuck approvals.
CREATE INDEX ix_swa_approval_status ON supplier_workorder_authorization (approval_status, updated_at);

-- Answering "what needs a human at this vendor" without scanning the table.
CREATE INDEX ix_swa_profile_status ON supplier_workorder_authorization (vendor_profile_id, status);
