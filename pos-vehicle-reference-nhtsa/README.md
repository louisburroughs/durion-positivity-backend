# pos-vehicle-reference-nhtsa

Vehicle reference data service backed by the NHTSA (National Highway Traffic Safety Administration) vehicle database. Stores and exposes vehicle taxonomy data — manufacturers, makes, models, vehicle types, and vehicle variables — used for fitment validation and vehicle lookups.

## Responsibilities

- Store NHTSA vehicle reference data (manufacturers, makes, models, vehicle types, vehicle variables) in PostgreSQL
- Expose read endpoints for the full NHTSA vehicle taxonomy
- Provide vehicle variable and variable value lookups for detailed vehicle specification matching

## Key Classes

- `VehicleReferenceController` — REST controller at `/v1/vehicle-fitment`
- `Make`, `Model`, `Manufacturer`, `VehicleType`, `VehicleVariable`, `VehicleVariableValue` — JPA entities for NHTSA taxonomy
- `VehicleReferenceMapper` — maps entities to response DTOs
- `RestClientConfig` — configures `RestClient` for fetching data from the NHTSA API

## API Endpoints

- `GET /v1/vehicle-fitment/manufacturers` — list all NHTSA manufacturers
- `GET /v1/vehicle-fitment/makes/{manufacturerId}` — makes for a manufacturer
- `GET /v1/vehicle-fitment/models/{makeId}` — models for a make
- `GET /v1/vehicle-fitment/vehicle-types/{makeId}` — vehicle types for a make

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
(`./mvnw -pl pos-vehicle-reference-nhtsa -am verify`). With no scoped table there is no isolation to prove.

## Dependencies

- `pos-shared-dtos` — shared DTOs

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-vehicle-reference-nhtsa -am spring-boot:run
```
