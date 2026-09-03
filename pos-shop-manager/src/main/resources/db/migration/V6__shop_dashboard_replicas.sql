-- ADR-0044 §6 (#1658): the three read-only replicas behind GET /v1/shop-dashboard.
--
-- The dashboard has to answer, in one request, "what is on every bay and mobile unit at this
-- location, and what else is open here" — facts owned by pos-workorder and pos-location. ADR-0044
-- R1 forbids asking either of them synchronously, so the answers live here, fed by their events,
-- and the endpoint's "one batched query per source" requirement is satisfied against these tables
-- rather than against another module's API.
--
-- Topology sourcing decision (AC11), recorded here and in LocationEventsListener: bays and mobile
-- units are event-sourced, not read live over RestClient. A live read would work today and needs
-- no tables, but it is a domain-to-domain synchronous call and would require minting a NEW
-- recorded ADR-0044 exception on the pos-warranty precedent (#786) — there is no standing grant
-- that covers it. pos-workorder made the same call the other way one story earlier (#1656), and
-- this module already runs four consumers over this exact contract. The honest cost: pos-location
-- does not publish bay or mobile-unit facts yet (its LocationFactPublisher emits location.location.*
-- and location.storage-location.updated only), so ext_bay and ext_mobile_unit start empty and the
-- dashboard's units[] is empty until that publisher lands. openWorkorders[] is unaffected.
--
-- Only the event consumers write these tables (R3). aggregate_version carries the envelope's
-- monotonic version for the stale-fact guard, per the ext_* convention already used by
-- ext_vehicle (V3) and ext_people_contact_person (V4).

-- Workorder facts from workorder.events.v1 (workorder.workorder.updated).
--
-- Terminal rows are KEPT, not deleted. "A completed or cancelled workorder frees its unit" is then
-- a pure read-side consequence of filtering on status, with no write on either side of the domain
-- wall and no schema change in workexec.
CREATE TABLE ext_workorder (
    workorder_id      uuid PRIMARY KEY,
    workorder_number  varchar(255),
    status            varchar(32),
    location_id       uuid,
    vehicle_id        uuid,
    customer_id       uuid,
    resource_id       uuid,
    resource_type     varchar(32),
    mechanic_ids      text,
    promised_at       timestamp(6) with time zone,
    scheduled_date    date,
    aggregate_version bigint      NOT NULL,
    updated_at        timestamp(6) with time zone NOT NULL
);

-- The dashboard's only workorder access path: scope by site, then filter by status. Both columns
-- are in the index so the open-work query for one location never scans the table.
CREATE INDEX ix_ext_workorder_location_status ON ext_workorder (location_id, status)
    WHERE location_id IS NOT NULL;

-- Resolves "which workorder is on this unit" without a second pass over the location's work.
CREATE INDEX ix_ext_workorder_resource ON ext_workorder (resource_id)
    WHERE resource_id IS NOT NULL;

-- Bay and mobile-unit topology from location.events.v1. Both carry the site scope (bays:
-- location_id; mobile units: base_location_id, the site a unit is dispatched from) so the roster
-- can list every unit at a location including ones holding no work — a set that cannot be derived
-- from the day's workorders, which is exactly why the topology needs its own source.
--
-- active=false rows are kept, not deleted: a decommissioned unit may still hold open work, and
-- dropping the row would make that work's unit unnameable.
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
