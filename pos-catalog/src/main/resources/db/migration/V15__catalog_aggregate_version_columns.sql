-- #1486: pos-catalog's fact envelope aggregateVersion was the row's updated_at as epoch millis,
-- which can tie when two mutations land in the same millisecond. Replaced with a JPA optimistic-
-- lock @Version counter on the three fact-carrying entities (product, service,
-- supplier_article_code), so the published sequence strictly advances per committed mutation.
--
-- Seeded from the legacy epoch-millis values rather than starting at 0: consumers of
-- catalog.events.v1 already hold replicas keyed on that convention, and a fresh 0 would look like
-- a regression to their stale-event guard. Postgres-only syntax is fine here — tests run with
-- Flyway disabled (ddl-auto: create-drop against H2), so this migration never runs against H2.

ALTER TABLE product ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
UPDATE product
SET version = CAST(EXTRACT(EPOCH FROM updated_at) * 1000 AS BIGINT)
WHERE updated_at IS NOT NULL;

ALTER TABLE service ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
UPDATE service
SET version = CAST(EXTRACT(EPOCH FROM updated_at) * 1000 AS BIGINT)
WHERE updated_at IS NOT NULL;

ALTER TABLE supplier_article_code ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
UPDATE supplier_article_code
SET version = CAST(EXTRACT(EPOCH FROM updated_at) * 1000 AS BIGINT)
WHERE updated_at IS NOT NULL;
