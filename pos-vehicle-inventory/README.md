# pos-vehicle-inventory

Vehicle registry and inventory service for the Durion POS platform. Stores vehicle records with VIN, year/make/model/trim, license plate, and customer account associations. Provides search, bulk ingest, and event-driven ingestion from external sources.

## Responsibilities

- Maintain the vehicle registry (create, update, deactivate vehicles)
- Search vehicles by VIN, account, or full-text criteria
- Manage customer care preferences per vehicle
- Handle event-driven vehicle record ingestion (`VehicleEventIngestionService`)
- Support legacy vehicle data migration (`VehicleLegacyService`)
- Expose bulk vehicle import via `POST /v1/vehicles/bulk-ingest`

## Key Classes

- `VehicleService` — vehicle CRUD; primary write path
- `VehicleSearchService` — search vehicles by VIN, account, or keyword
- `VehicleEventIngestionService` — processes inbound vehicle events (idempotent via `EventProcessingLog`)
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

| Property | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL |
| `EUREKA_SERVER_URL` | required | Eureka service discovery URL |

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-shared-dtos` — shared vehicle request/response DTOs
- `pos-bulk-ingest-lib` — bulk-ingest base controller

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-vehicle-inventory -am spring-boot:run
```
