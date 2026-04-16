Archived `pos-catalog` Flyway chain preserved during the collapsed baseline reset.

The legacy `V1..V7` sequence is kept outside `db/migration` so Flyway only sees
`V1__baseline_catalog_schema.sql` plus `R__seed_reference_catalog.sql` on fresh
bootstrap.
