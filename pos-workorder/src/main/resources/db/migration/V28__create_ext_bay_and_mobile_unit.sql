-- ADR-0044 §6 (#1656): read-only bay and mobile-unit replicas fed by location.bay.* and
-- location.mobile-unit.* facts on location.events.v1, following the ext_location template (V8).
--
-- These are the reason the dispatch board can name a resource at all. BayStatus.bayName has been
-- declared-but-always-null since the panel was written, because bay identity is owned by
-- pos-location and ADR-0044 forbids reading it synchronously or across the schema wall; without a
-- replica there was nowhere lawful to get the name from.
--
-- Both tables carry the site scope (bays: location_id; mobile units: base_location_id, the site a
-- unit is dispatched from) so the dashboard can list every active unit at the requested location,
-- including ones holding no work today — a set that cannot be derived from the day's workorders.
--
-- aggregate_version carries the fact envelope's monotonic version for the stale-event guard, per
-- the ext_* replica convention.
--
-- Row lifecycle, stated as implemented: a *deactivation* keeps the row. An OUT_OF_SERVICE bay or an
-- INACTIVE unit arrives as location.<entity>.updated, so the consumer rewrites the row with
-- active=false and open work assigned to it can still be named on the board. A *deletion* removes
-- the row: location.<entity>.deleted says the owner's aggregate is gone, and this replica mirrors
-- the owner rather than outliving it — the same rule ext_location follows for location.deleted (V8).
-- Losing the row does not hide the work: the dispatch board renders a panel row for any resource
-- open work still points at, name null, whether or not a replica row exists for it.
--
-- Note: pos-location does not publish these two fact families yet. The tables are created empty and
-- the consumer tolerates that; the upstream publisher is tracked as a cross-repo follow-up.
CREATE TABLE ext_bay (
    bay_id            uuid PRIMARY KEY,
    location_id       uuid,
    name              varchar(255),
    active            boolean     NOT NULL DEFAULT false,
    aggregate_version bigint      NOT NULL,
    updated_at        timestamp(6) with time zone NOT NULL
);

CREATE INDEX ix_ext_bay_location ON ext_bay (location_id) WHERE location_id IS NOT NULL;

CREATE TABLE ext_mobile_unit (
    mobile_unit_id    uuid PRIMARY KEY,
    base_location_id  uuid,
    name              varchar(255),
    active            boolean     NOT NULL DEFAULT false,
    aggregate_version bigint      NOT NULL,
    updated_at        timestamp(6) with time zone NOT NULL
);

CREATE INDEX ix_ext_mobile_unit_base_location ON ext_mobile_unit (base_location_id)
    WHERE base_location_id IS NOT NULL;
