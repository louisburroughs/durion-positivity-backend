-- Aggregate version counters for bays and mobile units (issue #1668).
--
-- pos-location is the system of record for both aggregates but has never published a lifecycle
-- fact for either. #1668 adds location.bay.* and location.mobile-unit.* on location.events.v1,
-- and every fact on that topic carries an aggregateVersion that must strictly advance per
-- committed mutation -- the contract ReplicaVersionGuard's stale guard depends on (#1486).
-- Neither table has a version column today, so both get one here.
--
-- Seeded to 0, NOT to wall-clock millis. V6 had to reseed location/storage_location from
-- migration-time now() because those aggregates had already emitted facts stamped with
-- emission-time epoch millis, and consumers were holding those magnitudes: a fresh small counter
-- would have read as an out-of-order regression and been discarded forever. Bays and mobile units
-- have published nothing, so no consumer holds any version for them and there is no floor to
-- clear. Starting at 0 keeps the counters ordinary Hibernate optimistic-lock values, which is
-- what every version-carrying aggregate written since #1486 uses.
--
-- The consumers' replicas start empty and their guard skips only a strictly-greater held version,
-- so the first fact each aggregate emits (version 0 on create, or the current value once an
-- existing row is next touched) applies cleanly. Existing rows reach consumers through the
-- backfill path this story adds rather than through this column.
--
-- NOT NULL with a default so existing rows need no separate UPDATE and concurrent writers during
-- deploy cannot insert a null. Postgres-only syntax is fine here -- tests run with Flyway
-- disabled (ddl-auto: create-drop against H2), so this migration never runs against H2.

ALTER TABLE bays ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE mobile_units ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
