-- ADR-0044 R3: the whole of this module's CRM integration is replicated state fed by
-- customer.events.v1 plus one asynchronous request/reply. No synchronous reads cross the
-- domain wall, and nothing here may be written except by the event consumer.

-- Resolved marketing eligibility per party and channel (Story #1138).
-- Stores the CRM's *decision*, not its inputs: the commercial rule (account master gate, else
-- the primary business contact's consent) belongs to pos-customer, and re-deriving it here
-- would guarantee the two copies drift.
--
-- decided_at is the owner's decision time, not our arrival time, so the send worker can refuse
-- to act on stale data. Consent is the one replicated signal where being behind means
-- contacting someone who asked not to be.
CREATE TABLE ext_party_consent (
    consent_key character varying(60) NOT NULL,
    party_id uuid NOT NULL,
    channel character varying(20) NOT NULL,
    allowed boolean NOT NULL,
    reason character varying(40) NOT NULL,
    governing_party_id uuid,
    decided_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_party_consent_pkey PRIMARY KEY (consent_key),
    CONSTRAINT ext_party_consent_channel_chk CHECK (channel IN ('EMAIL', 'SMS'))
);

CREATE INDEX idx_ext_party_consent_lookup ON ext_party_consent (party_id, channel);
CREATE INDEX idx_ext_party_consent_decided ON ext_party_consent (decided_at);

-- Segment definitions (Story #1137). Metadata only, deliberately never membership: a dynamic
-- segment's members are derived from party data and change with no event to replicate from.
CREATE TABLE ext_segment (
    segment_id uuid NOT NULL,
    name character varying(150),
    audience_type character varying(20),
    type character varying(20),
    active boolean NOT NULL DEFAULT FALSE,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_segment_pkey PRIMARY KEY (segment_id)
);

-- A campaign's audience snapshot, materialized from a customer.segment.resolved reply
-- (Story #1148). Because membership arrives asynchronously, an audience has a known age rather
-- than being a live query; resolved_at is surfaced in the preview so a marketer can see how
-- current the numbers are before committing to a send.
CREATE TABLE campaign_audience_member (
    audience_member_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    party_id uuid NOT NULL,
    resolved_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT campaign_audience_member_pkey PRIMARY KEY (audience_member_id),
    CONSTRAINT uq_campaign_audience_member UNIQUE (campaign_id, party_id),
    CONSTRAINT campaign_audience_member_campaign_fk
        FOREIGN KEY (campaign_id) REFERENCES campaign (campaign_id) ON DELETE CASCADE
);

CREATE INDEX idx_campaign_audience_campaign ON campaign_audience_member (campaign_id);
