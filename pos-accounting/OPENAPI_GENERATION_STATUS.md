## OpenAPI Spec Generation Status - pos-accounting

## Completed Work

### Controller Annotations
- All six controllers annotated with OpenAPI tags/operations (GL Accounts, Journal Entries, Posting Rules, Accounting Events, Accounting, Invoice Payments).

### Build and Runtime Setup
- Added `springdoc-openapi-starter-webmvc-ui` and configured `springdoc-openapi-maven-plugin:1.5` within the `openapi` profile.
- Updated springdoc starter to `2.7.0` for Spring Boot 3.4.2 compatibility.
- `openapi` Maven profile now starts/stops the app with H2 (PostgreSQL compatibility mode), generates the spec, and shuts down cleanly.
- Security updated to permit `/v3/api-docs/**` and Swagger UI endpoints.
- JSON/JSONB columns adjusted to rely on `@JdbcTypeCode(SqlTypes.JSON)` without PostgreSQL-only `columnDefinition` for H2 compatibility.

### Repository and Service Fixes
- GLMapping, PostingRuleSet, MappingKey, PostingCategory, GLAccount, and VendorBill repositories aligned to actual entity fields.
- PostingRuleServiceImpl updated to use supported repository methods.

### Generation Result
- Command: `./mvnw -Popenapi verify -pl pos-accounting -am -DskipTests`
- Output: `pos-accounting/target/openapi.json` (≈26 KB, includes all annotated endpoints).

## Current Status
- ✅ Automated OpenAPI generation succeeds and produces `target/openapi.json`.
- ✅ Application starts under the `openapi` profile with H2 in PostgreSQL mode.
- ⚠️ Non-blocking warnings: OTLP metrics exporter connection refused (expected locally without collector).

## How to Run
- Generate spec: `./mvnw -Popenapi verify -pl pos-accounting -am -DskipTests`
- View spec: `cat pos-accounting/target/openapi.json`
- Optional UI: start app (`./mvnw spring-boot:run -pl pos-accounting`) then open `http://localhost:8080/swagger-ui.html`.

## Next Steps
- Consider wiring the `openapi` profile into CI to publish `openapi.json` as an artifact.
- Add validation (lint/contract checks) on the generated spec as part of the pipeline.
