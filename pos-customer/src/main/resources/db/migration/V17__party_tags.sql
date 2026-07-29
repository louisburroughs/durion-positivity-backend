-- Story #1136: flat CRM tag taxonomy plus per-party assignments.
-- Tags are the lightest segmentation primitive; unlike account_tier they carry no
-- derived semantics, so a marketer can coin one without a schema change.
CREATE TABLE party_tag (
    tag_id uuid NOT NULL,
    name character varying(100) NOT NULL,
    category character varying(100),
    color character varying(20),
    active boolean NOT NULL DEFAULT TRUE,
    created_by character varying(255),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT party_tag_pkey PRIMARY KEY (tag_id)
);

-- Case-insensitive uniqueness: "Fleet Prospect" and "fleet prospect" are the same tag.
CREATE UNIQUE INDEX uq_party_tag_name ON party_tag (lower(name));
CREATE INDEX idx_party_tag_category ON party_tag (category);

CREATE TABLE party_tag_assignment (
    assignment_id uuid NOT NULL,
    party_id uuid NOT NULL,
    tag_id uuid NOT NULL,
    assigned_by character varying(255),
    assigned_at timestamp(6) with time zone NOT NULL,
    source character varying(20) NOT NULL,
    CONSTRAINT party_tag_assignment_pkey PRIMARY KEY (assignment_id),
    -- Makes assignment idempotent, which the campaign and import paths rely on when they replay.
    CONSTRAINT uq_party_tag_assignment UNIQUE (party_id, tag_id),
    CONSTRAINT party_tag_assignment_tag_fk FOREIGN KEY (tag_id) REFERENCES party_tag (tag_id),
    CONSTRAINT party_tag_assignment_source_chk
        CHECK (source IN ('MANUAL', 'CAMPAIGN', 'IMPORT', 'RULE'))
);

CREATE INDEX idx_party_tag_assignment_party ON party_tag_assignment (party_id);
CREATE INDEX idx_party_tag_assignment_tag ON party_tag_assignment (tag_id);
