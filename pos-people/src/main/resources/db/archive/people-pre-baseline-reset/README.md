This directory preserves the pre-reset people migration chain.

The active Flyway path is `src/main/resources/db/migration/` and now contains only:

- `V1__baseline_people_schema.sql`
- `R__seed_reference_people.sql`

Files in this archive are retained for history and reference only. They sit
outside Flyway's active scan path so fresh databases bootstrap from the
collapsed baseline instead of replaying the old migration chain.
