-- Story #1154: prospect lifecycle and inbound inquiry capture.

-- lifecycle_stage is distinct from status: status says whether a record is usable, this says
-- how far the party has progressed toward being a real customer. A PROSPECT is an active,
-- valid record that has never bought anything, and counting it as a customer inflates every
-- retention and churn figure. Existing rows are all real customers, so they backfill to ACTIVE.
-- TABLE_PER_CLASS inheritance means both party tables carry the column.
ALTER TABLE commercial_party
    ADD COLUMN lifecycle_stage character varying(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE person_party
    ADD COLUMN lifecycle_stage character varying(20) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE commercial_party
    ADD CONSTRAINT commercial_party_lifecycle_stage_chk
        CHECK (lifecycle_stage IN ('PROSPECT', 'ACTIVE', 'DORMANT'));
ALTER TABLE person_party
    ADD CONSTRAINT person_party_lifecycle_stage_chk
        CHECK (lifecycle_stage IN ('PROSPECT', 'ACTIVE', 'DORMANT'));

CREATE INDEX idx_commercial_party_lifecycle ON commercial_party (lifecycle_stage);
CREATE INDEX idx_person_party_lifecycle ON person_party (lifecycle_stage);

-- Contact details are held flat here rather than being pushed into pos-people-contact on
-- arrival: an inquiry is unverified, often duplicated, and frequently abandoned, and creating a
-- canonical person for every one would fill the identity store with junk. Identity is created
-- at conversion, which is where duplicate detection runs.
CREATE TABLE inquiry (
    inquiry_id uuid NOT NULL,
    channel character varying(20) NOT NULL,
    audience_type character varying(20) NOT NULL,
    contact_name character varying(200) NOT NULL,
    organization_name character varying(200),
    email character varying(320),
    phone character varying(40),
    vehicle_of_interest character varying(300),
    service_of_interest character varying(300),
    message character varying(2000),
    campaign_code character varying(100),
    status character varying(20) NOT NULL,
    party_id uuid,
    assigned_to character varying(255),
    resolution_note character varying(1000),
    -- Truncated one-way fingerprint of a public submitter's origin, for abuse triage only.
    -- Never a raw address: a public form must not build a visitor log as a side effect.
    submitted_from character varying(64),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT inquiry_pkey PRIMARY KEY (inquiry_id),
    CONSTRAINT inquiry_channel_chk CHECK (channel IN (
        'WEB_FORM', 'PHONE', 'WALK_IN', 'EMAIL', 'REFERRAL', 'CAMPAIGN')),
    CONSTRAINT inquiry_audience_type_chk CHECK (audience_type IN ('COMMERCIAL', 'INDIVIDUAL')),
    CONSTRAINT inquiry_status_chk CHECK (status IN ('NEW', 'CONTACTED', 'CONVERTED', 'CLOSED')),
    -- A converted inquiry without a party is a claim that a customer exists when none does.
    CONSTRAINT inquiry_converted_party_chk CHECK (status <> 'CONVERTED' OR party_id IS NOT NULL),
    -- An inquiry with no way to reach the enquirer cannot be actioned.
    CONSTRAINT inquiry_contactable_chk CHECK (email IS NOT NULL OR phone IS NOT NULL)
);

CREATE INDEX idx_inquiry_status ON inquiry (status, created_at DESC);
CREATE INDEX idx_inquiry_party ON inquiry (party_id);
CREATE INDEX idx_inquiry_campaign ON inquiry (campaign_code) WHERE campaign_code IS NOT NULL;
-- Backs the public form's per-source ceiling.
CREATE INDEX idx_inquiry_source_window ON inquiry (submitted_from, created_at) WHERE submitted_from IS NOT NULL;
