This directory preserves the pre-reset invoice migration chain.

The active Flyway path is `src/main/resources/db/migration/` and now contains only:

- `V1__baseline_invoice_schema.sql`
- `R__seed_reference_invoice.sql`

Files in this archive are retained for history and reference only. They sit
outside Flyway's active scan path so fresh databases bootstrap from the
collapsed baseline instead of replaying the old migration chain.
