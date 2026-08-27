-- #1527 review: the service-level overlap check is advisory only — two concurrent
-- creates with different start dates can both pass it and insert overlapping periods,
-- and uq_time_period_tenant_start (V8) only catches identical starts. Enforce the
-- no-overlap invariant in the database: per tenant, inclusive [start_date, end_date]
-- ranges must not intersect. btree_gist supplies the gist equality operator for the
-- uuid tenant column (same CREATE EXTENSION IF NOT EXISTS pattern as timescaledb /
-- pgvector elsewhere in this repo). A violation surfaces as a
-- DataIntegrityViolationException, which createTimePeriod maps to 409 and the
-- rollover's create loop treats as "period already exists — skip".
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE time_period
    ADD CONSTRAINT ex_time_period_tenant_no_overlap
    EXCLUDE USING gist (tenant_id WITH =, daterange(start_date, end_date, '[]') WITH &&);
