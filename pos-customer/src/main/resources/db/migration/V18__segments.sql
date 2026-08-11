-- Story #1137: saved audience segments.
-- audience_type is part of the definition rather than a filter applied later: commercial and
-- individual audiences differ in who consents and what a message may say, so a segment that
-- mixes them cannot be addressed correctly. It is immutable after creation.
CREATE TABLE segment (
    segment_id uuid NOT NULL,
    name character varying(150) NOT NULL,
    description character varying(1000),
    audience_type character varying(20) NOT NULL,
    type character varying(20) NOT NULL,
    -- Serialized SegmentPredicate tree, validated against the whitelisted attribute catalog
    -- before it is ever written. Never interpolated into SQL.
    predicate text,
    active boolean NOT NULL DEFAULT TRUE,
    created_by character varying(255),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT segment_pkey PRIMARY KEY (segment_id),
    CONSTRAINT segment_audience_type_chk CHECK (audience_type IN ('COMMERCIAL', 'INDIVIDUAL')),
    CONSTRAINT segment_type_chk CHECK (type IN ('STATIC', 'DYNAMIC')),
    -- A DYNAMIC segment without a predicate has no definition; a STATIC one with a predicate
    -- has a definition that does not describe its membership. Refuse both.
    CONSTRAINT segment_predicate_shape_chk CHECK (
        (type = 'DYNAMIC' AND predicate IS NOT NULL)
        OR (type = 'STATIC' AND predicate IS NULL))
);

CREATE UNIQUE INDEX uq_segment_name ON segment (lower(name));
CREATE INDEX idx_segment_audience_type ON segment (audience_type);

CREATE TABLE segment_member (
    segment_member_id uuid NOT NULL,
    segment_id uuid NOT NULL,
    party_id uuid NOT NULL,
    contact_id uuid,
    added_by character varying(255),
    added_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT segment_member_pkey PRIMARY KEY (segment_member_id),
    CONSTRAINT uq_segment_member UNIQUE (segment_id, party_id),
    CONSTRAINT segment_member_segment_fk FOREIGN KEY (segment_id) REFERENCES segment (segment_id)
);

CREATE INDEX idx_segment_member_segment ON segment_member (segment_id);
