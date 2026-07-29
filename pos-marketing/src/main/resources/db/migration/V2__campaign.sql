-- Story #1146: campaign definition and lifecycle.
-- segment_id, promotion_offer_id, and catalog_focus_ref reference aggregates owned by
-- pos-customer, pos-price, and pos-catalog. No foreign keys: ADR-0044 forbids a database-level
-- tie across the domain wall. They are validated over REST at schedule time instead, which is
-- the moment correctness actually matters.
CREATE TABLE campaign (
    campaign_id uuid NOT NULL,
    code character varying(100) NOT NULL,
    name character varying(200) NOT NULL,
    description character varying(1000),
    audience_type character varying(20) NOT NULL,
    campaign_program_id uuid,
    status character varying(20) NOT NULL,
    segment_id uuid,
    promotion_offer_id uuid,
    catalog_focus_ref character varying(200),
    window_start timestamp(6) with time zone,
    window_end timestamp(6) with time zone,
    schedule_type character varying(20) NOT NULL,
    scheduled_at timestamp(6) with time zone,
    email_template_id uuid,
    sms_template_id uuid,
    created_by character varying(255),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT campaign_pkey PRIMARY KEY (campaign_id),
    CONSTRAINT campaign_audience_type_chk CHECK (audience_type IN ('COMMERCIAL', 'INDIVIDUAL')),
    CONSTRAINT campaign_status_chk CHECK (status IN (
        'DRAFT', 'SCHEDULED', 'SENDING', 'SENT', 'PAUSED', 'CANCELLED', 'CLOSED')),
    CONSTRAINT campaign_schedule_type_chk CHECK (schedule_type IN ('IMMEDIATE', 'SCHEDULED')),
    -- A SCHEDULED-type campaign without a moment has no schedule at all.
    CONSTRAINT campaign_scheduled_at_chk CHECK (schedule_type <> 'SCHEDULED' OR scheduled_at IS NOT NULL),
    CONSTRAINT campaign_window_chk CHECK (window_end IS NULL OR window_start IS NULL OR window_end >= window_start)
);

-- The campaign code is the attribution key stamped onto redemptions, so it must be unique
-- regardless of casing or two campaigns would share one set of conversions.
CREATE UNIQUE INDEX uq_campaign_code ON campaign (lower(code));
CREATE INDEX idx_campaign_status ON campaign (status);
CREATE INDEX idx_campaign_program ON campaign (campaign_program_id);
CREATE INDEX idx_campaign_segment ON campaign (segment_id);

CREATE TABLE campaign_channel (
    campaign_id uuid NOT NULL,
    channel character varying(20) NOT NULL,
    CONSTRAINT campaign_channel_pkey PRIMARY KEY (campaign_id, channel),
    CONSTRAINT campaign_channel_fk FOREIGN KEY (campaign_id) REFERENCES campaign (campaign_id) ON DELETE CASCADE,
    CONSTRAINT campaign_channel_chk CHECK (channel IN ('EMAIL', 'SMS'))
);
