-- odoo-parity I1 (#1031, spec §9): recurring cycle-count schedules
-- (Odoo cyclic_inventory_frequency / next_inventory_date analog).
--
-- 1. Schedule table: per-location count cadence with optional single-zone and
--    SKU-category filters (sku_category is metadata-only in v1 — no catalog
--    category replica exists yet). Schedules are deactivated, never deleted.
CREATE TABLE cycle_count_schedule (
    schedule_id uuid NOT NULL,
    location_id uuid NOT NULL,
    zone_id uuid,
    sku_category character varying(100),
    frequency_days integer NOT NULL,
    next_due_date date NOT NULL,
    auto_create_plan boolean NOT NULL,
    active boolean NOT NULL,
    created_by character varying(255) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT cycle_count_schedule_pkey PRIMARY KEY (schedule_id),
    CONSTRAINT cycle_count_schedule_frequency_days_check CHECK ((frequency_days > 0))
);

CREATE INDEX idx_cycle_count_schedule_due ON cycle_count_schedule (active, next_due_date);
CREATE INDEX idx_cycle_count_schedule_location ON cycle_count_schedule (location_id);

-- 2. Plan provenance: which schedule/due date auto-created the plan. Both
--    columns stay NULL for manual plans. The unique constraint is the hard
--    exactly-once guard for the scheduled runner (NULLs are distinct, so
--    manual plans are unaffected).
ALTER TABLE cycle_count_plan ADD COLUMN schedule_id uuid;

ALTER TABLE cycle_count_plan ADD COLUMN due_date date;

ALTER TABLE cycle_count_plan ADD CONSTRAINT cycle_count_plan_schedule_due_key UNIQUE (schedule_id, due_date);
