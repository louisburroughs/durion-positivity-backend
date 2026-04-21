-- Partial unique indexes for hierarchy tables to prevent duplicate name entries
-- and make find-or-create safe under concurrency.

CREATE UNIQUE INDEX IF NOT EXISTS ux_manufacturer_name_lower
    ON manufacturer (lower(name));

CREATE UNIQUE INDEX IF NOT EXISTS ux_make_manufacturer_name_lower
    ON make (manufacturer_id, lower(name))
    WHERE manufacturer_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_make_name_lower_no_manufacturer
    ON make (lower(name))
    WHERE manufacturer_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_model_make_name_lower
    ON model (make_id, lower(name))
    WHERE make_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_model_name_lower_no_make
    ON model (lower(name))
    WHERE make_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_vehicle_type_make_name_lower
    ON vehicle_type (make_id, lower(vehicle_type_name))
    WHERE make_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_vehicle_type_name_lower_no_make
    ON vehicle_type (lower(vehicle_type_name))
    WHERE make_id IS NULL;
