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

## Dependencies

- `pos-shared-dtos` — shared DTOs

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-vehicle-reference-nhtsa -am spring-boot:run
```
