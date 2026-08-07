-- #1175: read-only per-vehicle care-preference replica fed by vehicle.care-preference.updated
-- facts on vehicle.events.v1. pos-vehicle-inventory owns these facts; nothing in this module may
-- write the table except the event consumer.
--
-- service_interval_months is nullable: null means "no per-vehicle override — use the module-wide
-- pos.customer.crm.service-due-months default". A care-preference delete is a versioned tombstone
-- (interval null, aggregate_version advanced) rather than a hard delete, so the stale-version
-- guard keeps rejecting replayed older facts after a removal. aggregate_version is the owner's
-- last-writer-wins epoch-millis stamp (care preferences are hard-deleted and re-created upstream,
-- so it is not a JPA row version); preference_updated_at carries the owner's row timestamp,
-- updated_at is when this replica row was written.
CREATE TABLE ext_vehicle_care_preference (
    vehicle_id uuid NOT NULL,
    service_interval_months integer,
    preference_updated_at timestamp(6) with time zone,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_vehicle_care_preference_pkey PRIMARY KEY (vehicle_id)
);
