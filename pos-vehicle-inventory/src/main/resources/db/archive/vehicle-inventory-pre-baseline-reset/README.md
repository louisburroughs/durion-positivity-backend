This directory preserves the pre-reset vehicle inventory baseline.

The active Flyway path is `src/main/resources/db/migration/` and now contains only:

- `V1__baseline_vehicle_inventory_schema.sql`

Files in this archive are retained for history and reference only. They sit
outside Flyway's active scan path so fresh databases bootstrap from the
collapsed baseline instead of relying on the prior baseline file.
