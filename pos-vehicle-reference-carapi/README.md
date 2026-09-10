# pos-vehicle-reference-carapi

Vehicle reference data service backed by the CarAPI data source. Stores and exposes vehicle make and model reference data used by the fitment and inventory modules for vehicle taxonomy lookups.

## Responsibilities

- Store CarAPI vehicle makes and models in a local PostgreSQL database via Flyway
- Expose read endpoints for makes and models by manufacturer
- Serve as an internal reference data source for vehicle taxonomy within the platform

## Key Classes

- `VehicleReferenceService` — queries `CarApiMake` and `CarApiModel` entities for reference lookups
- `VehicleReferenceController` — REST controller at `/v1/vehicle-reference`
- `CarApiMake` / `CarApiModel` — JPA entities for make and model data from CarAPI
- `VehicleReferenceMapper` — maps entities to response DTOs

## API Endpoints

- `GET /v1/vehicle-reference/makes` — list all CarAPI vehicle makes
- `GET /v1/vehicle-reference/models/{makeId}` — models for a given make ID

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |

## Multitenancy (ADR-0062, WS3 wave 11)

This module runs on the ADR-0062 runtime: it depends on `pos-tenancy-common`, and every table is vehicle reference data shared by
all tenants: each entity carries `@TenantGlobal` and every table is listed in
`src/main/resources/db/tenancy-global-tables.txt`; nothing here is tenant-scoped. The request tenant is
bound by `TenantContextFilter` from `X-Tenant-Id` (the gateway injects it from the token's `tid`), and every
connection checkout binds `app.current_tenant` for row-level security. `pos.tenancy.default-tenant-id` still binds
the alpha default tenant on every unbound path.

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`, read by Boot's Flyway auto-configuration).

Proof: `TenancySchemaConformanceIT` (every whitelisted table has no policy and no row-level security, and the
whitelist names only tables that exist; the pool is `pos_app` with no bypass) on Testcontainers Postgres
(`./mvnw -pl pos-vehicle-reference-carapi -am verify`). With no scoped table there is no isolation to prove.

## Dependencies

No internal `pos-*` module dependencies at runtime.

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-vehicle-reference-carapi -am spring-boot:run
```
