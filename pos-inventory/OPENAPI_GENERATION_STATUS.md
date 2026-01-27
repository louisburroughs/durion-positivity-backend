## OpenAPI Spec Generation Status - pos-inventory

## Completed Work

### Controller Annotations
- All six controllers already annotated with OpenAPI tags/operations:
  - InventoryAvailabilityController
  - CycleCountAdjustmentController
  - PickingListController
  - InventorySiteDefaultLocationsController
  - CycleCountController
  - InventoryLocationDeactivationController

### Application Class
- Created `PosInventoryApplication.java` with `@SpringBootApplication` and `@OpenAPIDefinition` annotations
- Configured with:
  - Title: "Inventory API"
  - Version: "1.0"
  - Description: "API for managing inventory in the POS system"

### Build and Runtime Setup
- Added `springdoc-openapi-starter-webmvc-ui:2.7.0` dependency
- Configured `springdoc-openapi-maven-plugin:1.5` within the `openapi` profile
- Updated dependencies to include:
  - PostgreSQL driver (runtime scope)
  - Jackson JSON processing library
  - `spring-boot-starter-test` for testing support
- `openapi` Maven profile configured with:
  - Spring Boot start/stop goals for test environment
  - H2 in-memory database (PostgreSQL compatibility mode)
  - Disabled Flyway, Eureka, and Spring Boot Admin for profile runs

### Build Configuration
- Spring Boot Maven plugin updated to exclude Lombok from repackaged JAR
- Added pluginRepositories section for Maven Central and Springdoc repo
- Properties section added with springdoc versions

### Generation Result
- Command: `./mvnw -Popenapi -pl pos-inventory -am clean package integration-test -DskipTests`
- Output: `pos-inventory/target/openapi.json` (≈19 KB, includes all annotated endpoints)

## Current Status
- ✅ Automated OpenAPI generation succeeds and produces `target/openapi.json`
- ✅ Application starts under the `openapi` profile with H2 in PostgreSQL mode
- ✅ All six controllers properly documented with Swagger/OpenAPI annotations

## How to Run
- Generate spec: `./mvnw -Popenapi verify -pl pos-inventory -am -DskipTests`
- View spec: `cat pos-inventory/target/openapi.json`
- Optional UI: start app (`./mvnw spring-boot:run -pl pos-inventory`) then open `http://localhost:8080/swagger-ui.html`

## API Endpoints Documented
- **Inventory Availability**: Query and update inventory availability
- **Cycle Count Adjustments**: Create, approve, reject adjustments with approval workflow
- **Picking Lists**: Confirm picking lists and commit consumption
- **Inventory Sites**: Configure default locations per site
- **Cycle Count API**: Submit counts, recounts, track variance, manage tasks
- **Location Deactivation**: Deactivate locations with inventory transfer

## Next Steps
- Consider wiring the `openapi` profile into CI to publish `openapi.json` as an artifact
- Add validation (lint/contract checks) on the generated spec as part of the pipeline
- Document any behavioral contract tests that align with the OpenAPI spec
