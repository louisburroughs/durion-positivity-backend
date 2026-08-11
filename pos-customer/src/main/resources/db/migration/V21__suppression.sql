-- Story #1140: hard address-level marketing suppression.
-- Keyed by a hash of the normalized address rather than by party: a bounced mailbox must
-- stay blocked even if the address later attaches to a different party, and a mailbox
-- shared by two contacts must block both. Only the hash and a masked hint are stored.
CREATE TABLE suppression_entry (
    suppression_id uuid NOT NULL,
    channel character varying(20) NOT NULL,
    address_hash character varying(64) NOT NULL,
    address_hint character varying(120),
    party_id uuid,
    reason character varying(30) NOT NULL,
    source character varying(30) NOT NULL,
    created_by character varying(255),
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT suppression_entry_pkey PRIMARY KEY (suppression_id),
    CONSTRAINT uq_suppression_channel_address UNIQUE (channel, address_hash),
    CONSTRAINT suppression_entry_channel_chk CHECK (channel IN ('EMAIL', 'SMS')),
    CONSTRAINT suppression_entry_reason_chk
        CHECK (reason IN ('HARD_BOUNCE', 'SPAM_COMPLAINT', 'LEGAL_DNC', 'MANUAL', 'UNSUBSCRIBE_LINK')),
    CONSTRAINT suppression_entry_source_chk
        CHECK (source IN ('CSR', 'SELF_SERVICE', 'UNSUBSCRIBE_LINK', 'IMPORT', 'API', 'SYSTEM'))
);

CREATE INDEX idx_suppression_party ON suppression_entry (party_id);
CREATE INDEX idx_suppression_lookup ON suppression_entry (channel, address_hash);
