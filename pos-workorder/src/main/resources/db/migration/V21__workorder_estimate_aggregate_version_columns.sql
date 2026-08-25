-- #1486 follow-up: pos-workorder's workorder.events.v1 envelope stamped
-- Instant.now(clock).toEpochMilli() as aggregateVersion for every WorkorderUpdatedV1 and
-- EstimateUpdatedV1 fact -- an emission-time value, not the aggregate's own state version, so two
-- mutations landing in the same millisecond tied. Replaced with JPA optimistic-lock @Version
-- counters on Workorder and Estimate, so the published sequence strictly advances per committed
-- mutation and the platform's equal-applies consumer rule (ReplicaVersionGuard) is safe to adopt
-- for these two facts.
--
-- workorder has no existing version column, so it gets a plain `version` BIGINT like the
-- catalog/location precedent. estimate already has a `version` column, but it is an ordinary
-- (non-optimistic-lock) integer that tracks estimate revisions as a business concept -- unrelated
-- to this fact's concurrency counter -- so the new column is named `aggregate_version` instead of
-- colliding with it (see Estimate.aggregateVersion javadoc).
--
-- Both are seeded here, unconditionally, from wall-clock millis at MIGRATION time -- deliberately
-- NOT from updated_at, mirroring pos-location's V6. The versions consumers currently hold are
-- EMISSION-time clock millis, which can exceed a row's updated_at by a few milliseconds (the gap
-- between the mutation commit and the publish call reading the clock); only migration-time now()
-- upper-bounds every version ever emitted for these facts, so the first post-migration fact
-- (seed+1 or greater, once flushed) can never regress a replica.
--
-- Postgres-only syntax is fine here -- the default test suite runs with Flyway disabled
-- (ddl-auto: create-drop against H2); only FlywayMigrationIT (Testcontainers Postgres, `verify`
-- phase, profile `pg`) exercises this migration for real, with Hibernate then validating the
-- resulting schema against these same entities.

ALTER TABLE workorder ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
UPDATE workorder
SET version = CAST(EXTRACT(EPOCH FROM now()) * 1000 AS BIGINT);

ALTER TABLE estimate ADD COLUMN aggregate_version BIGINT NOT NULL DEFAULT 0;
UPDATE estimate
SET aggregate_version = CAST(EXTRACT(EPOCH FROM now()) * 1000 AS BIGINT);
