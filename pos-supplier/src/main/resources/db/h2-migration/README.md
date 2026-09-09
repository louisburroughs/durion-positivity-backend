# H2 test chain (pos-supplier)

The retired versioned migrations, verbatim, for the H2 `@DataJpaTest` / `@SpringBootTest`
slices that boot the real DDL with `ddl-auto=validate` (`spring.flyway.locations=classpath:db/h2-migration`).
They were written H2-compatible; the Postgres baseline in `../migration/` is not, since the
2026-09-09 tenancy flatten (row-level security, `app_current_tenant()`), and Hibernate-generated
DDL would drop the unique, check and foreign-key constraints those tests prove.

Not used by any runtime profile. A schema change goes into `../migration/V1__baseline_supplier.sql`
first and then here as a new `V21+` script, until plan WS5 moves these tests to Testcontainers and
this directory goes away (the same arrangement `pos-mcp-server/src/main/resources/db/h2-migration`
has for its `dev`/`test` profiles).
