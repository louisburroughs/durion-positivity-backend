# pos-vehicle-inventory

Vehicle registry and inventory service for the Durion Positivity ETSMS platform. Stores vehicle records with VIN, year/make/model/trim, license plate, and customer account associations. Provides search and bulk ingest, and publishes every registry mutation as a domain event on `vehicle.events.v1` (ADR-0044).

## Responsibilities

- Maintain the vehicle registry (create, update, deactivate vehicles)
- Search vehicles by VIN, account, or full-text criteria
- Manage customer care preferences per vehicle
- Publish `vehicle.vehicle.updated` events on `vehicle.events.v1` via a transactional outbox (ADR-0044 §6, #843); frontends write vehicles here directly through the gateway, and pos-customer keeps a read-only `ext_vehicle` replica fed by these events
- Publish `vehicle.care-preference.updated` events on the same topic after care-preference mutations (#1175); pos-customer projects the structured `serviceIntervalMonths` into an `ext_vehicle_care_preference` replica that drives per-vehicle CRM service-due resolution
- Serve replay/bootstrap commands on `vehicle.commands.v1` and publish reconciliation manifests on `vehicle.manifest.v1`
- Support legacy vehicle data migration (`VehicleLegacyService`)
- Expose bulk vehicle import via `POST /v1/vehicles/bulk-ingest`

## Key Classes

- `VehicleService` — vehicle CRUD; primary write path
- `VehicleSearchService` — search vehicles by VIN, account, or keyword
- `VehicleEventPublisher` / `OutboxEventWriter` / `OutboxPublisher` — transactional-outbox event pipeline (ADR-0044 §4)
- `ManifestPublisher` / `VehicleCommandListener` / `OutboxReplayService` — reconciliation-manifest publication and consumer-requested replay
- `VehiclePreferencesService` — manages per-vehicle care preferences
- `VehicleController` — REST controller at `/v1/vehicles`
- `VehicleBulkIngestController` — bulk ingest endpoint at `/v1/vehicles/bulk-ingest`

## API Endpoints

- `POST /v1/vehicles` — register a vehicle
- `GET /v1/vehicles/{vehicleId}` — retrieve a vehicle by ID
- `GET /v1/vehicles/vin/{vin}` — retrieve a vehicle by VIN
- `PUT /v1/vehicles/{id}` — update a vehicle
- `DELETE /v1/vehicles/{vehicleId}` — deactivate a vehicle
- `GET /v1/vehicles` — list/search vehicles
- `POST /v1/vehicles/bulk-ingest` — bulk import vehicles (auth: `vehicle-inventory:registry:create`)
- `GET /v1/vehicles/{id}` — get vehicle preferences (via preferences controller)
- `PUT /v1/vehicles` — update vehicle preferences

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |
| `POS_VEHICLE_INVENTORY_KAFKA_ENABLED` | `false` | Enables the ADR-0044 event pipeline (outbox drain, commands listener, manifests) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers for the domain-event channel |

## Multitenancy (ADR-0062, WS3 wave 10)

This module runs on the ADR-0062 runtime: it depends on `pos-tenancy-common`, every scoped entity extends `TenantScopedEntity` (`VehicleEntity` carries it for the
single-table vehicle hierarchy), and the global `event_outbox` (listed in
`src/main/resources/db/tenancy-global-tables.txt`) carries `@TenantGlobal`. The request tenant is
bound by `TenantContextFilter` from `X-Tenant-Id` (the gateway injects it from the token's `tid`), the Kafka tenant by `TenantRecordInterceptor` from the `tenantId` record header on the
command consumer, and every
connection checkout binds `app.current_tenant` for row-level security. `pos.tenancy.default-tenant-id` still binds
the alpha default tenant on every unbound path.

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`, `FlywayConfig`).

The outbox row carries the producing tenant as data (`tenant_id`, stamped from the bound tenant by
`OutboxEventWriter`, added by `V2__event_outbox_tenant_id.sql`); `OutboxPublisher.publishPending` and
`ManifestPublisher.publishDueManifest` are platform-scoped (they drain and summarise the global `event_outbox`;
the manifest publisher groups the window's rows by `tenant_id` and publishes one manifest per tenant, stamped
with that tenant, plus a zero-count one for every active registry tenant that published nothing). There are no native queries.

Proof: `TenantIsolationIT` (tenant A's `vehicle` row is invisible to tenant B and to an unbound
connection, through the repository and through raw SQL) and `TenancySchemaConformanceIT` (every
non-whitelisted table has `tenant_id`, RLS enabled and forced, and the `tenant_isolation` policy; the pool is
`pos_app` with no bypass), both on Testcontainers Postgres (`./mvnw -pl pos-vehicle-inventory -am verify`).

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-shared-dtos` — shared vehicle request/response DTOs
- `pos-domain-events` — ADR-0044 envelope, topics, and `VehicleUpdatedV1` payload contract
- `pos-bulk-ingest-lib` — bulk-ingest base controller

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-vehicle-inventory -am spring-boot:run
```
