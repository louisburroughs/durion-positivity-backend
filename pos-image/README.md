# pos-image

Image storage and retrieval microservice for the Durion Positivity ETSMS platform. Persists image binary data with classification metadata and provides fetch-by-filename or fetch-by-ID endpoints.

## Responsibilities

- Store uploaded images with a classification tag (product, vehicle, document, etc.)
- Retrieve images by internal UUID or by original filename
- Track image metadata (filename, content type, size) in PostgreSQL via JPA
- Support audit timestamps via JPA auditing

## Key Classes

- `ImageService` — public service interface; upload and retrieval operations
- `ImageServiceImpl` — stores image bytes in `image_entity` via `ImageRepository`
- `ImageController` — REST controller at `/v1/images`
- `ImageEntity` — JPA entity for image record (id, filename, contentType, data, classification)
- `Classification` — enum of image classification categories

## API Endpoints

- `GET /v1/images/id/{id}` — retrieve image by UUID
- `GET /v1/images/filename/{filename}` — retrieve image by original filename

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |

## Multitenancy (ADR-0062, WS3 wave 11)

This module runs on the ADR-0062 runtime: it depends on `pos-tenancy-common`, and both entities extend `TenantScopedEntity` (there are no global tables;
the `image_context` collection table takes its `tenant_id` from the Postgres default). The request tenant is
bound by `TenantContextFilter` from `X-Tenant-Id` (the gateway injects it from the token's `tid`), and every
connection checkout binds `app.current_tenant` for row-level security. `pos.tenancy.default-tenant-id` still binds
the alpha default tenant on every unbound path.

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`, `FlywayConfig`).

There is no outbox, no consumer, no scheduled job and no native query.

Proof: `TenantIsolationIT` (tenant A's `image` row is invisible to tenant B and to an unbound
connection, through the repository and through raw SQL) and `TenancySchemaConformanceIT` (every
non-whitelisted table has `tenant_id`, RLS enabled and forced, and the `tenant_isolation` policy; the pool is
`pos_app` with no bypass), both on Testcontainers Postgres (`./mvnw -pl pos-image -am verify`).

## Dependencies

No internal `pos-*` module dependencies at runtime.

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-image -am spring-boot:run
```
