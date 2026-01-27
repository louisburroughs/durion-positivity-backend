## OpenAPI Spec Generation Status - pos-order

## Completed Work

### Controller Annotations
- PriceOverrideController annotated with comprehensive OpenAPI tags/operations

### Application Class
- Updated `PosOrderApplication.java` with `@OpenAPIDefinition` annotation
- Configured with:
  - Title: "Order API"
  - Version: "1.0"
  - Description: "API for managing orders and price overrides in the POS system"

### Security Configuration
- Created `SecurityConfig.java` to permit access to OpenAPI endpoints
- Configured to allow `/v3/api-docs/**`, `/swagger-ui/**`, and `/swagger-ui.html` without authentication
- Maintains method-level security via `@PreAuthorize` annotations on controller endpoints

### Build and Runtime Setup
- Added `springdoc-openapi-starter-webmvc-ui:2.7.0` dependency
- Configured `springdoc-openapi-maven-plugin:1.5` within the `openapi` profile
- Updated dependencies to include:
  - PostgreSQL driver (runtime scope)
  - Jackson JSON processing library
- `openapi` Maven profile configured with:
  - Spring Boot start/stop goals for test environment
  - H2 in-memory database (PostgreSQL compatibility mode)
  - Disabled Flyway, Eureka, and Spring Boot Admin for profile runs

### Build Configuration
- Spring Boot Maven plugin already configured to exclude Lombok from repackaged JAR
- Added pluginRepositories section for Maven Central and Springdoc repo
- Properties section added with springdoc versions

### Generation Result
- Command: `./mvnw -Popenapi verify -pl pos-order -am -DskipTests`
- Output: `pos-order/target/openapi.json` (≈8.1 KB, includes all annotated endpoints)

## Current Status
- ✅ Automated OpenAPI generation succeeds and produces `target/openapi.json`
- ✅ Application starts under the `openapi` profile with H2 in PostgreSQL mode
- ✅ Security properly configured to allow OpenAPI endpoint access
- ✅ Controller endpoints properly documented with Swagger/OpenAPI annotations

## How to Run
- Generate spec: `./mvnw -Popenapi verify -pl pos-order -am -DskipTests`
- View spec: `cat pos-order/target/openapi.json`
- Optional UI: start app (`./mvnw spring-boot:run -pl pos-order`) then open `http://localhost:8080/swagger-ui.html`

## API Endpoints Documented
- **Price Override Management**: Complete approval workflow for price overrides
  - POST /api/v1/orders/price-overrides - Apply price override
  - POST /api/v1/orders/price-overrides/{overrideId}/approve - Approve override
  - POST /api/v1/orders/price-overrides/{overrideId}/reject - Reject override
  - GET /api/v1/orders/price-overrides/{overrideId} - Get override details
  - GET /api/v1/orders/price-overrides - Query overrides (by order, status, or date range)
  - GET /api/v1/orders/price-overrides/pending - Get pending approvals

## Next Steps
- Consider wiring the `openapi` profile into CI to publish `openapi.json` as an artifact
- Add validation (lint/contract checks) on the generated spec as part of the pipeline
- Document behavioral contract tests that align with the OpenAPI spec
