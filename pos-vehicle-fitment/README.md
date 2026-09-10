# pos-vehicle-fitment

Vehicle fitment and part applicability service for the Durion Positivity ETSMS platform. Manages the hierarchical vehicle taxonomy (Manufacturer, Make, Model, VehicleType), part-to-vehicle fitment associations, vehicle variables, and applicability hints for product-to-vehicle matching.

## Responsibilities

- Maintain vehicle taxonomy: Manufacturer, Make, Model, VehicleType
- Create and query part fitment associations (which parts fit which vehicles)
- Manage vehicle applicability hints for catalog product matching
- Support bulk fitment import via `POST /v1/fitments/bulk-ingest`
- Expose NHTSA vehicle reference data: vehicle types, makes, models, manufacturers

## Key Classes

- `VehicleFitmentService` — fitment CRUD using find-or-create taxonomy hierarchy
- `VehicleApplicabilityHintService` — manages product-to-vehicle applicability hints
- `VehicleFitmentController` — REST controller at `/v1/fitments`
- `VehicleFitmentBulkIngestController` — bulk ingest endpoint at `/v1/fitments/bulk-ingest`
- `VehicleApplicabilityHintController` — hint CRUD at `/v1/vehicle-fitment/hints`

## API Endpoints

- `POST /v1/fitments` — create a part fitment
- `GET /v1/fitments/product/{productId}` — fitments for a product
- `GET /v1/fitments/makes/{manufacturerId}` — makes for a manufacturer
- `GET /v1/fitments/manufacturers` — list all manufacturers
- `GET /v1/fitments/models/{makeId}` — models for a make
- `GET /v1/fitments/vehicle-types/{makeId}` — vehicle types for a make
- `POST /v1/fitments/bulk-ingest` — bulk import fitments (auth: `vehicle-fitment:hint:create`)
- `POST /v1/fitments/filter-products` — find products applicable to a vehicle
- `GET /v1/vehicle-fitment/hints/{hintId}` — retrieve an applicability hint
- `POST /v1/vehicle-fitment/hints` — create an applicability hint
- `PUT /v1/vehicle-fitment/hints/{hintId}` — update a hint
- `DELETE /v1/vehicle-fitment/hints/{hintId}` — delete a hint

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |

## Multitenancy (ADR-0062, WS3 wave 10)

This module runs on the ADR-0062 runtime: it depends on `pos-tenancy-common`, and every table is fitment reference data shared by
all tenants: each entity carries `@TenantGlobal` and every table is listed in
`src/main/resources/db/tenancy-global-tables.txt`; nothing here is tenant-scoped. The request tenant is
bound by `TenantContextFilter` from `X-Tenant-Id` (the gateway injects it from the token's `tid`), and every
connection checkout binds `app.current_tenant` for row-level security. `pos.tenancy.default-tenant-id` still binds
the alpha default tenant on every unbound path.

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`, `FlywayConfig`).

Proof: `TenancySchemaConformanceIT` (every whitelisted table has no policy and no row-level security, and the
whitelist names only tables that exist; the pool is `pos_app` with no bypass) on Testcontainers Postgres
(`./mvnw -pl pos-vehicle-fitment -am verify`). With no scoped table there is no isolation to prove.

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-bulk-ingest-lib` — bulk-ingest base controller

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-vehicle-fitment -am spring-boot:run
```
