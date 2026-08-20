-- ADR-0055 stage 4 (#1416): tolerance-based cycle-count reconciliation, and the decimal,
-- UOM-aware count capture it depends on.
--
-- V39 widened cycle_count_adjustment (the settled variance) but deliberately left the
-- count-capture tables integral, noting the tolerance policy was stage 4's problem. This is
-- that migration.
--
-- 1. Widen the count-capture tables to numeric(19,4), matching every other quantity column
--    the ledger touches. A bulk product counted at 42.75 gal could not be recorded before this;
--    it silently truncated to 42 or 43 the moment it hit cycle_count_task/count_entry.
ALTER TABLE cycle_count_task
    ALTER COLUMN expected_quantity TYPE numeric(19,4);

ALTER TABLE count_entry
    ALTER COLUMN actual_quantity TYPE numeric(19,4),
    ALTER COLUMN expected_quantity TYPE numeric(19,4),
    ALTER COLUMN variance TYPE numeric(19,4);

-- 2. Required data per the owner's spec (issue #1414 comment): measured quantity + the unit it
--    was measured in, measurement method, variance percentage, the resolved tolerance snapshot,
--    whether the count landed inside it, and a variance reason for the cases that did not.
--    measured_quantity is the value as keyed (in uom_code, or base when uom_code is null);
--    actual_quantity (above) stays the base-UoM value the variance is computed against — the
--    two are equal whenever uom_code is null, which is every count until a bulk product is
--    seeded. measurement_method defaults MANUAL_COUNT so every pre-existing and future
--    non-bulk count needs no behavior change to keep meaning "someone counted it by hand".
ALTER TABLE count_entry
    ADD COLUMN measured_quantity numeric(19,4),
    ADD COLUMN uom_code varchar(32),
    ADD COLUMN measurement_method varchar(32) NOT NULL DEFAULT 'MANUAL_COUNT',
    ADD COLUMN variance_percentage numeric(9,4),
    ADD COLUMN allowed_tolerance_absolute numeric(19,4),
    ADD COLUMN allowed_tolerance_percentage numeric(7,4),
    ADD COLUMN within_tolerance boolean NOT NULL DEFAULT true,
    ADD COLUMN variance_reason varchar(500);

-- Backfill measured_quantity for any pre-existing rows: with no uom_code, measured and actual
-- coincide by definition (see the comment above).
UPDATE count_entry SET measured_quantity = actual_quantity WHERE measured_quantity IS NULL;
ALTER TABLE count_entry ALTER COLUMN measured_quantity SET NOT NULL;

-- 3. cycle_count_task gains ACCEPTED_WITHIN_TOLERANCE: a count whose variance sits inside its
--    resolved tolerance is reconciled with no adjustment and no manager review — a state the
--    existing five-value lifecycle (plus V10's CONFLICT) had no room for. Every other outcome
--    is unchanged: COUNTED_PENDING_REVIEW still means "review it", REQUIRES_INVESTIGATION still
--    means "recount limit or threshold breach", and both remain reachable for an
--    exceeding-tolerance count exactly as they were before tolerance existed.
ALTER TABLE cycle_count_task DROP CONSTRAINT cycle_count_task_status_check;
ALTER TABLE cycle_count_task ADD CONSTRAINT cycle_count_task_status_check
    CHECK (((status)::text = ANY ((ARRAY[
        'ASSIGNED'::character varying,
        'COUNTED_PENDING_REVIEW'::character varying,
        'CONFLICT'::character varying,
        'REQUIRES_INVESTIGATION'::character varying,
        'APPROVED'::character varying,
        'REJECTED'::character varying,
        'ACCEPTED_WITHIN_TOLERANCE'::character varying])::text[])));

-- 4. Tolerance configuration: scoped by product and/or storage location, most-specific wins
--    (product+location > product > location > none). Either bound may be null; both null means
--    zero tolerance — today's behavior, preserved as the default until a tolerance is seeded.
--    storage_location matches cycle_count_task.bin_location's own convention (free text that
--    may hold a location UUID's string form; see CycleCountConflictDetector) rather than a
--    foreign key, since the task table it scopes has no location UUID column to key against.
CREATE TABLE cycle_count_tolerance (
    tolerance_id uuid NOT NULL,
    product_id uuid,
    storage_location varchar(100),
    absolute_tolerance numeric(19,4),
    percentage_tolerance numeric(7,4),
    active boolean NOT NULL DEFAULT true,
    created_by varchar(100) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT cycle_count_tolerance_pkey PRIMARY KEY (tolerance_id),
    CONSTRAINT cycle_count_tolerance_bounds_check CHECK (
        (absolute_tolerance IS NULL OR absolute_tolerance >= 0)
        AND (percentage_tolerance IS NULL OR percentage_tolerance >= 0))
);

-- At most one ACTIVE row per (product, location) scope, including the global default
-- (product_id and storage_location both null) — sentinels stand in for NULL because Postgres
-- treats each NULL as distinct in a plain unique index.
CREATE UNIQUE INDEX ux_cycle_count_tolerance_scope ON cycle_count_tolerance (
    COALESCE(product_id::text, '00000000-0000-0000-0000-000000000000'),
    COALESCE(storage_location, '')
) WHERE active;

CREATE INDEX idx_cycle_count_tolerance_product ON cycle_count_tolerance (product_id) WHERE active;
CREATE INDEX idx_cycle_count_tolerance_location ON cycle_count_tolerance (storage_location) WHERE active;
