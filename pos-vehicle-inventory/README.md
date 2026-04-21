# pos-vehicle-inventory

Vehicle inventory service for Durion POS.

## Purpose

Handles vehicle registry, search, and inventory-level metadata for vehicles.

## Key Endpoints (Wave 3 additions)

- `POST /v1/vehicles/bulk-ingest` — bulk ingest vehicles (CSV → `VehicleBulkIngestRecord`)
  - Permission: `vehicle-inventory:registry:create`
  - Event emitted: `VEHICLE_BULK_INGEST` (apiVersion `1`)

## Ingest DTO (summary)

- `VehicleBulkIngestRecord` fields (important ones): `accountId` (UUID), `vin` (17 chars), `unitNumber`, `description`, `licensePlate`, `licensePlateJurisdiction`, `year`, `make`, `model`, `trim`.

## Setup notes

- Module follows standard backend conventions (Spring Boot, Flyway migrations).
- See module tests and `VehicleBulkIngestController` for request contract and response codes.
