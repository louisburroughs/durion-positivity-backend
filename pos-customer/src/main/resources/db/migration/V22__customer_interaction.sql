-- Story #1141: unified party interaction timeline.
-- Generalizes the one-way party_note workorder projection so campaign sends, CSR calls,
-- follow-ups, and workorder notes share one queryable history. party_note is retained: it
-- remains the raw workorder projection, and existing consumers of it are unaffected.
CREATE TABLE customer_interaction (
    interaction_id uuid NOT NULL,
    party_id uuid NOT NULL,
    contact_id uuid,
    type character varying(30) NOT NULL,
    channel character varying(20),
    direction character varying(20) NOT NULL,
    campaign_id uuid,
    campaign_code character varying(100),
    subject character varying(255),
    summary character varying(1000),
    body character varying(4000),
    actor character varying(255),
    source_workorder_id character varying(64),
    source_event_id character varying(64),
    occurred_at timestamp(6) with time zone NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT customer_interaction_pkey PRIMARY KEY (interaction_id),
    CONSTRAINT customer_interaction_type_chk CHECK (type IN (
        'CAMPAIGN_SEND', 'EMAIL', 'SMS', 'CALL', 'FOLLOW_UP', 'NOTE', 'WORKORDER_NOTE')),
    CONSTRAINT customer_interaction_channel_chk CHECK (channel IS NULL OR channel IN ('EMAIL', 'SMS')),
    CONSTRAINT customer_interaction_direction_chk CHECK (direction IN ('OUTBOUND', 'INBOUND'))
);

CREATE INDEX idx_customer_interaction_party ON customer_interaction (party_id, occurred_at DESC);
CREATE INDEX idx_customer_interaction_campaign ON customer_interaction (campaign_id);

-- Ingestion idempotency: a replayed campaign-send or workorder-note event must not append a
-- second row. Partial, because CSR-recorded touches carry no source event.
CREATE UNIQUE INDEX uq_customer_interaction_source_event
    ON customer_interaction (source_event_id)
    WHERE source_event_id IS NOT NULL;
