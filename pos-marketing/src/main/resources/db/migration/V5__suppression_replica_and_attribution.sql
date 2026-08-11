-- Story #1140 consumer side: read-only replica of the CRM suppression list, fed exclusively by
-- customer.events.v1 (ADR-0044 R3). The send worker checks suppression once per recipient;
-- doing that synchronously would put a network round trip in the inner loop of every campaign
-- and make CRM availability a hard dependency of sending. pos-customer remains the owner —
-- nothing in this module may write this table except the event consumer.
CREATE TABLE ext_suppression (
    suppression_key character varying(80) NOT NULL,
    channel character varying(20) NOT NULL,
    address_hash character varying(64) NOT NULL,
    party_id uuid,
    reason character varying(30),
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_suppression_pkey PRIMARY KEY (suppression_key)
);

CREATE INDEX idx_ext_suppression_party ON ext_suppression (party_id);
CREATE INDEX idx_ext_suppression_lookup ON ext_suppression (channel, address_hash);

-- Story #1152: redemptions credited to a campaign.
-- The primary key is the CRM's redemption_id, not a locally generated id. That is the whole
-- anti-double-count mechanism: customer.events.v1 is at-least-once, so the same redemption fact
-- will arrive more than once, and a generated key would turn each replay into another conversion.
CREATE TABLE campaign_attribution (
    redemption_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    campaign_code character varying(100) NOT NULL,
    party_id uuid NOT NULL,
    workorder_id uuid,
    discount_amount numeric(19, 4),
    attributed_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT campaign_attribution_pkey PRIMARY KEY (redemption_id),
    CONSTRAINT campaign_attribution_campaign_fk FOREIGN KEY (campaign_id) REFERENCES campaign (campaign_id)
);

CREATE INDEX idx_campaign_attribution_campaign ON campaign_attribution (campaign_id);
