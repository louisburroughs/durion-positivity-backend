-- ADR-0061 §1–§2 (#1872): the location replica behind this module's in-process location-scope
-- check, fed by location.location.updated / location.location.deleted on location.events.v1.
--
-- Every location-parameterised endpoint here (createAppointment, viewSchedule, getShopDashboard,
-- listLocationTechnicians, getTechnicianPerson and the by-id appointment read) gates the caller's
-- scope against the requested location. The decision intersects the caller's assigned nodes with
-- the location's ancestor set on the dimension the role is scoped on, and ADR-0061 forbids asking
-- pos-location per request — so the ancestor sets are materialised on this replica, exactly as
-- pos-people (V12/V13), pos-inventory and pos-workorder do.
--
-- Only LocationEventsListener writes these tables (ADR-0044 R3). Existing `shop` rows are not
-- migrated: `shop.id` IS the pos-location location id by convention (every service resolves the
-- request's locationId with shopRepository.findById), but the scope check reads this replica, not
-- `shop` — a scoped caller is denied at a location the replica has not seen, which is the
-- fail-closed behaviour ADR-0061 specifies. The owner's POST .../facts/replay fills the table.

CREATE TABLE ext_location (
    location_id            uuid PRIMARY KEY,
    code                   varchar(64),
    name                   varchar(255),
    active                 boolean     NOT NULL DEFAULT false,
    aggregate_version      bigint      NOT NULL,
    synced_at              timestamp(6) with time zone NOT NULL,
    -- Comma-separated, sorted canonical UUID strings (see UuidSetConverter); inclusive of the
    -- location itself. '' means "no ancestors known" and reads as deny.
    financial_ancestor_ids text        NOT NULL DEFAULT '',
    other_ancestor_ids     text        NOT NULL DEFAULT ''
);

-- Typed parent edges carried on each location fact; the owner allows one parent per
-- (child, parentType), so that pair is the key, and every fact replaces the child's full set.
CREATE TABLE ext_location_parent (
    child_id    uuid        NOT NULL,
    parent_id   uuid        NOT NULL,
    parent_type varchar(64) NOT NULL,
    CONSTRAINT ext_location_parent_pkey PRIMARY KEY (child_id, parent_type)
);

-- Downward step for the descendant walk (recompute after a re-parent; reach expansion).
CREATE INDEX ix_ext_location_parent_parent ON ext_location_parent (parent_id, parent_type);
