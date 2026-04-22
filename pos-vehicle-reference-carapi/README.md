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

## Dependencies

No internal `pos-*` module dependencies at runtime.

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-vehicle-reference-carapi -am spring-boot:run
```
