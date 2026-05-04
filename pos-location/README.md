# pos-location

Location hierarchy and physical space management service for the Durion Positivity ETSMS platform. Manages the tree of service locations, bays, storage locations, mobile units, service areas, rosters, and travel buffer policies.

## Responsibilities

- Manage the location hierarchy (parent/child relationships between service sites)
- Create and configure service bays and mobile unit bays
- Maintain storage locations within each site
- Transfer inventory between storage locations atomically
- Manage location rosters (which staff are assigned to a location)
- Define service areas and their coverage rules
- Configure site defaults (tax jurisdiction, currency, operating hours)
- Enforce travel buffer policies for mobile unit scheduling

## Key Classes

- `LocationService` — location CRUD and hierarchy traversal
- `BayService` — bay lifecycle for fixed and mobile bays
- `StorageLocationService` — bin-level storage location management
- `StorageLocationInventoryTransferService` — atomic transfer of stock between bins
- `LocationRosterService` — staff roster management per location
- `ServiceAreaService` — service area and coverage rule management
- `TravelBufferPolicyService` — mobile unit travel buffer configuration

## API Endpoints

- `GET /v1/locations/{locationId}` — retrieve a location
- `GET /v1/locations/{locationId}/children` — child locations
- `GET /v1/locations/{locationId}/validation` — validate location configuration
- `DELETE /v1/locations/{locationId}` — deactivate a location
- `GET /v1/locations/roster` — current location roster
- `GET /v1/locations/{id}/coverage-rules` — service area coverage rules
- `GET /v1/bays/{bayId}` — retrieve a bay
- `POST /v1/locations/{locationId}/bays` — add a bay to a location
- `GET /v1/locations/{storageLocationId}` — retrieve a storage location
- `GET /v1/mobile-units:eligible` — eligible mobile units for scheduling

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-bulk-ingest-lib` — bulk-ingest base controller

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-location -am spring-boot:run
```
