# Vehicle Fitment Module

This module handles vehicle fitment data using Spring Data JPA and H2 database.

## Schema management

Flyway manages runtime schema for this module.

- Baseline migration: `src/main/resources/db/migration/V1__baseline_vehicle_fitment_schema.sql`
- Runtime JPA mode: `spring.jpa.hibernate.ddl-auto=validate`
