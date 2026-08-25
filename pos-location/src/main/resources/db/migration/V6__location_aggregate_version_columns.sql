-- #1486 follow-up: pos-location's location.events.v1 envelope stamped
-- Instant.now(clock).toEpochMilli() as aggregateVersion on every emit -- an emission-time
-- value, not the aggregate's own state version, so two mutations landing in the same
-- millisecond tied. Replaced with JPA optimistic-lock @Version counters on Location and
-- StorageLocationEntity, so the published sequence strictly advances per committed mutation
-- and the platform's equal-applies consumer rule (ReplicaVersionGuard) is safe to adopt here.
--
-- location.version already existed as a column (an @Version the publisher never read), but its
-- values are ordinary Hibernate optimistic-lock counters -- small integers counting each row's
-- updates, nothing like the epoch-millis magnitudes consumers already hold. Left alone, the
-- first fact emitted post-migration would report a version far BELOW what every replica
-- already holds, and every consumer's stale guard would then discard it -- and everything
-- after it -- as an out-of-order regression, forever. storage_location has no version column
-- at all yet.
--
-- Both are therefore (re)seeded here, unconditionally, from wall-clock millis at MIGRATION
-- time -- deliberately NOT from updated_at, unlike catalog's V15. The versions consumers
-- currently hold are EMISSION-time clock millis, which can exceed a row's updated_at by a few
-- milliseconds (the gap between the mutation commit and the publish call reading the clock);
-- only migration-time now() upper-bounds every version ever emitted for these facts, so the
-- first post-migration fact (seed+1 or greater, once flushed) can never regress a replica.
-- Postgres-only syntax is fine here -- tests run with Flyway disabled (ddl-auto: create-drop
-- against H2), so this migration never runs against H2.

UPDATE location
SET version = CAST(EXTRACT(EPOCH FROM now()) * 1000 AS BIGINT);

ALTER TABLE storage_location ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
UPDATE storage_location
SET version = CAST(EXTRACT(EPOCH FROM now()) * 1000 AS BIGINT);
