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
