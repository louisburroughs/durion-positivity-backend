-- ADR-0044 §6 (#1636): typed location-parent edges carried on location.location.updated
-- facts, mirroring pos-inventory's ext_location_parent (V5). One parent per
-- (child, parentType) upstream; every location fact replaces the child's full edge set.
-- Serves the top-level default-location resolution for GET /people/me/primary-location.
CREATE TABLE ext_location_parent (
    child_id uuid NOT NULL,
    parent_type varchar(64) NOT NULL,
    parent_id uuid NOT NULL,
    CONSTRAINT ext_location_parent_pkey PRIMARY KEY (child_id, parent_type)
);
CREATE INDEX idx_ext_location_parent_parent ON ext_location_parent (parent_id, parent_type);
