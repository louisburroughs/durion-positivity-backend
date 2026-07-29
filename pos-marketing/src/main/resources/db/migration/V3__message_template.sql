-- Story #1147: channel-specific message bodies with substitution tokens.
-- audience_type lives on the template, not just the campaign: the two audiences have different
-- token vocabularies, and binding them here lets an unknown token be rejected at save time
-- rather than rendering as a blank in front of a customer.
CREATE TABLE message_template (
    template_id uuid NOT NULL,
    name character varying(150) NOT NULL,
    channel character varying(20) NOT NULL,
    audience_type character varying(20) NOT NULL,
    subject character varying(255),
    body text NOT NULL,
    created_by character varying(255),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT message_template_pkey PRIMARY KEY (template_id),
    CONSTRAINT message_template_channel_chk CHECK (channel IN ('EMAIL', 'SMS')),
    CONSTRAINT message_template_audience_type_chk CHECK (audience_type IN ('COMMERCIAL', 'INDIVIDUAL')),
    -- SMS has no subject line; an email without one is a deliverability problem.
    CONSTRAINT message_template_subject_chk CHECK (channel <> 'EMAIL' OR subject IS NOT NULL)
);

CREATE UNIQUE INDEX uq_message_template_name ON message_template (lower(name));
CREATE INDEX idx_message_template_channel ON message_template (channel, audience_type);
