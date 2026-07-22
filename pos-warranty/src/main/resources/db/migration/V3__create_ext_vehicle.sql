-- ADR-0044 §6 (#924): read-only vehicle-registry replica fed by vehicle.vehicle.updated facts on
-- vehicle.events.v1. Replaces the synchronous VehicleInventoryClient read path that froze the
-- VIN + odometer onto a warranty claim at intake.
-- H2(MODE=PostgreSQL)-compatible like V1 (no Postgres-only syntax).
CREATE TABLE ext_vehicle (
    vehicle_id uuid NOT NULL,
    vin character varying(64),
    odometer_value integer,
    odometer_unit character varying(16),
    active boolean NOT NULL DEFAULT true,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_vehicle_pkey PRIMARY KEY (vehicle_id)
);
