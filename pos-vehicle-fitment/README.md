# Vehicle Fitment Module

This module handles vehicle fitment data using Spring Data JPA and H2 database.

## Schema management

Flyway manages runtime schema for this module.

- Baseline migration: `src/main/resources/db/migration/V1__baseline_vehicle_fitment_schema.sql`
- Runtime JPA mode: `spring.jpa.hibernate.ddl-auto=validate`

## Purpose

Manages part fitments, hierarchical vehicle taxonomy (Manufacturer → Make → Model → VehicleType), and part-to-vehicle associations.

## Key Endpoints (Wave 3 additions)

- `POST /v1/fitments/bulk-ingest` — bulk ingest fitments (CSV → `FitmentBulkIngestRecord`)
- Permission: `vehicle-fitment:hint:create`
- Event emitted: `VEHICLE_FITMENT_BULK_INGEST` (apiVersion `1`)

## Service behavior

- `VehicleFitmentService.createFitment(...)` implements a find-or-create hierarchy pattern: it will locate or create Manufacturer, Make, Model, then `VehicleType`, and finally persist the `PartFitment` association.
