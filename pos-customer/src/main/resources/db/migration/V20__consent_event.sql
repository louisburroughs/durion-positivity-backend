-- Story #1139: append-only marketing-consent audit trail.
-- No updated_at and no update or delete path anywhere in the module: a consent trail that
-- can be edited is worthless as compliance evidence.
CREATE TABLE consent_event (
    consent_event_id uuid NOT NULL,
    party_id uuid NOT NULL,
    channel character varying(20) NOT NULL,
    old_value character varying(20) NOT NULL,
    new_value character varying(20) NOT NULL,
    reason character varying(30),
    source character varying(30) NOT NULL,
    actor character varying(255),
    occurred_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT consent_event_pkey PRIMARY KEY (consent_event_id),
    CONSTRAINT consent_event_channel_chk CHECK (channel IN ('EMAIL', 'SMS')),
    CONSTRAINT consent_event_old_value_chk CHECK (old_value IN ('OPT_IN', 'OPT_OUT', 'UNSET')),
    CONSTRAINT consent_event_new_value_chk CHECK (new_value IN ('OPT_IN', 'OPT_OUT', 'UNSET')),
    CONSTRAINT consent_event_source_chk
        CHECK (source IN ('CSR', 'SELF_SERVICE', 'UNSUBSCRIBE_LINK', 'IMPORT', 'API', 'SYSTEM'))
);

CREATE INDEX idx_consent_event_party ON consent_event (party_id, occurred_at DESC);
CREATE INDEX idx_consent_event_occurred ON consent_event (occurred_at);
