-- #1486 follow-up: pos-customer's customer.events.v1 party facts (customer.party.updated,
-- customer.party.deleted) and the billing-rules fact (customer.billing-rules.updated) stamped
-- Instant.now(clock).toEpochMilli() as aggregateVersion on every emit -- an emission-time value,
-- not the aggregate's own state version, so two mutations landing in the same millisecond tied.
-- Replaced with a JPA optimistic-lock @Version counter on the party hierarchy root
-- (AbstractParty), so the published sequence strictly advances per committed mutation and the
-- platform's equal-applies consumer rule (ReplicaVersionGuard) is safe to adopt on the two facts
-- pos-order and pos-accounting version-guard (party-updated, billing-rules).
--
-- Party uses InheritanceType.TABLE_PER_CLASS: there is no shared "party" table, so each concrete
-- table gets its own version column and its own independent counter.
--
-- Seeded from wall-clock millis at MIGRATION time -- deliberately NOT from updated_at, the same
-- reasoning as pos-location's V6. The versions consumers currently hold are EMISSION-time clock
-- millis, which can exceed a row's updated_at by a few milliseconds (the gap between the mutation
-- commit and the publish call reading the clock); only migration-time now() upper-bounds every
-- version ever emitted for these facts, so the first post-migration fact (seed+1 or greater, once
-- flushed) can never regress a replica.
-- Postgres-only syntax is fine here -- tests run with Flyway disabled (ddl-auto: create-drop
-- against H2), so this migration never runs against H2.

ALTER TABLE person_party ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
UPDATE person_party
SET version = CAST(EXTRACT(EPOCH FROM now()) * 1000 AS BIGINT);

ALTER TABLE commercial_party ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
UPDATE commercial_party
SET version = CAST(EXTRACT(EPOCH FROM now()) * 1000 AS BIGINT);
