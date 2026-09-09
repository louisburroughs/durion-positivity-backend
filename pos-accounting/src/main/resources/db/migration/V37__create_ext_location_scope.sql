-- ADR-0061 §1–§2 (#1885): the location replica this module needs to answer a location-scope
-- check in-process. Location scope is assigned to a node and covers that node and every
-- descendant; the check intersects the caller's assigned nodes with a materialised ancestor set
-- held here, never with a per-request call to pos-location.
--
-- Fed by location.location.updated / .deleted on location.events.v1 (ADR-0044 §6).
-- H2(MODE=PostgreSQL)-compatible, like this module's other replica migrations.

-- 1. The location itself. Only the fields the scope check and its logs need are replicated.
CREATE TABLE ext_location (
    location_id uuid NOT NULL,
    name character varying(255),
    -- The owner's unique short code. This module's GL locationId dimension names a location by
    -- code (e.g. LOC-107), so the scope check resolves the code to the id the sets are keyed on.
    code character varying(100),
    active boolean NOT NULL DEFAULT true,
    aggregate_version bigint NOT NULL,
    -- Materialised ancestor sets, inclusive of the location itself, one per hierarchy dimension:
    -- FINANCIAL walks the FINANCIAL parent chain, OTHER walks the union of
    -- HOME_OFFICE/HEADQUARTERS/REGION/DISTRICT/PHYSICAL/ORGANIZATIONAL/SHIPPING. Stored as a
    -- comma-separated, sorted list of canonical UUID strings (see UuidSetConverter); '' means
    -- "no ancestors known" and reads as deny.
    financial_ancestor_ids text NOT NULL DEFAULT '',
    other_ancestor_ids text NOT NULL DEFAULT '',
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_location_pkey PRIMARY KEY (location_id)
);
CREATE UNIQUE INDEX idx_ext_location_code ON ext_location (code);

-- 2. Typed location-parent edges carried on every location fact, mirroring pos-people's,
--    pos-inventory's and pos-workorder's ext_location_parent. One parent per (child, parentType)
--    upstream; every location fact replaces the child's full edge set.
CREATE TABLE ext_location_parent (
    child_id uuid NOT NULL,
    parent_type character varying(64) NOT NULL,
    parent_id uuid NOT NULL,
    CONSTRAINT ext_location_parent_pkey PRIMARY KEY (child_id, parent_type)
);
CREATE INDEX idx_ext_location_parent_parent ON ext_location_parent (parent_id, parent_type);
