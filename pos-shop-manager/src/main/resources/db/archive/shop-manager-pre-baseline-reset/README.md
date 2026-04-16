This directory preserves the pre-reset shop manager migration chain.

The active Flyway path is `src/main/resources/db/migration/` and now contains only:

- `V1__baseline_shop_manager_schema.sql`

Files in this archive are retained for history and reference only. They sit
outside Flyway's active scan path so fresh databases bootstrap from the
collapsed baseline instead of replaying the old migration chain.
