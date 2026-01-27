## OpenAPI Spec Generation Status - pos-location

## Completed Work

### Controller Annotations
- All three controllers already annotated with OpenAPI tags/operations:
  - LocationController
  - MobileUnitController
  - BayController

### Application Class
- Existing `PosLocationApplication.java` already has `@SpringBootApplication` and `@OpenAPIDefinition` annotations
- Configured with:
  - Title: "Location API"
  - Version: "1.0"
  - Description: "API for managing locations in the POS system"

### Build and Runtime Setup
- Added `springdoc-openapi-starter-webmvc-ui:2.7.0` dependency (updated from 2.6.0)
- Configured `springdoc-openapi-maven-plugin:1.5` within the `openapi` profile
- Updated dependencies to include:
  - `spring-boot-starter-web` (was missing, required for servlet API)
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
- Command: `./mvnw -Popenapi -pl pos-location -am clean package integration-test -DskipTests`
- Output: `pos-location/target/openapi.json` (≈11 KB, includes all annotated endpoints)

## Current Status
- ✅ Automated OpenAPI generation succeeds and produces `target/openapi.json`
- ✅ Application starts under the `openapi` profile with H2 in PostgreSQL mode
- ✅ All three controllers properly documented with Swagger/OpenAPI annotations

## How to Run
- Generate spec: `./mvnw -Popenapi verify -pl pos-location -am -DskipTests`
- View spec: `cat pos-location/target/openapi.json`
- Optional UI: start app (`./mvnw spring-boot:run -pl pos-location`) then open `http://localhost:8080/swagger-ui.html`

## API Endpoints Documented
- **Location API**: CRUD operations for locations and parent relationships
  - GET /v1/locations - Get all locations
  - GET /v1/locations/{locationId} - Get location by ID
  - POST /v1/locations - Create new location
  - PUT /v1/locations/{locationId} - Update location
  - DELETE /v1/locations/{locationId} - Delete location
  - POST /v1/locations/{locationId}/parents - Add parent to location
  - GET /v1/locations/parents - Get all location parents
- **Mobile Units**: Manage mobile unit locations
- **Bays**: Manage bay locations and hierarchy

## Next Steps
- Consider wiring the `openapi` profile into CI to publish `openapi.json` as an artifact
- Add validation (lint/contract checks) on the generated spec as part of the pipeline
- Document any behavioral contract tests that align with the OpenAPI spec
