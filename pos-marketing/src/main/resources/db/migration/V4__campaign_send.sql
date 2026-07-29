-- Story #1149: per-recipient send records.
-- The (campaign_id, recipient_party_id, channel) uniqueness constraint is the idempotency
-- guarantee: re-invoking /send, retrying a batch, or replaying after a crash can never contact
-- the same person twice on the same channel for the same campaign.
--
-- Only address_hash is retained, never the resolved address. A send log accumulating every
-- customer's email in plaintext would become the most sensitive table in the system for no
-- operational benefit — the hash is enough to correlate a provider bounce back to a row.
CREATE TABLE campaign_send (
    campaign_send_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    recipient_party_id uuid NOT NULL,
    contact_id uuid,
    channel character varying(20) NOT NULL,
    address_hash character varying(64),
    status character varying(20) NOT NULL,
    failure_reason character varying(500),
    provider_message_id character varying(200),
    attempts integer NOT NULL DEFAULT 0,
    queued_at timestamp(6) with time zone NOT NULL,
    sent_at timestamp(6) with time zone,
    delivered_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT campaign_send_pkey PRIMARY KEY (campaign_send_id),
    CONSTRAINT uq_campaign_send_recipient UNIQUE (campaign_id, recipient_party_id, channel),
    CONSTRAINT campaign_send_campaign_fk FOREIGN KEY (campaign_id) REFERENCES campaign (campaign_id),
    CONSTRAINT campaign_send_channel_chk CHECK (channel IN ('EMAIL', 'SMS')),
    CONSTRAINT campaign_send_status_chk CHECK (status IN (
        'PENDING', 'SENT', 'DELIVERED', 'BOUNCED', 'COMPLAINED', 'SUPPRESSED', 'FAILED'))
);

CREATE INDEX idx_campaign_send_campaign ON campaign_send (campaign_id, status);
CREATE INDEX idx_campaign_send_provider_message ON campaign_send (provider_message_id);
-- The worker's drain scan: oldest queued recipients first.
CREATE INDEX idx_campaign_send_pending ON campaign_send (status, queued_at);
