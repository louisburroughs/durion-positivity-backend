## OpenAPI Spec Generation Status - pos-people

## Completed Work

### Application Class
- Existing `PosPeopleApplication.java` already has `@SpringBootApplication` and `@OpenAPIDefinition` annotations
- Configured with:
  - Title: "People API"
  - Version: "1.0"
  - Description: "API for managing people in the POS system"

### Controller Annotations
- All seven controllers already annotated with comprehensive OpenAPI tags/operations:
  - PersonController - People API (CRUD operations for person records)
  - WorkSessionController - Work Sessions API (work session and break management)
  - PeopleAvailabilityController - People Availability API (people availability queries)
  - PeopleReportsController - People Reports API (attendance and reporting)
  - TimeEntryAdjustmentController - People - TimeEntries (time entry adjustment APIs)
  - TimeEntryApprovalController - Time Entry Approval API (batch approval workflow)
  - TimeEntryExceptionController - People - Exceptions (exception handling)

### Security Configuration
- Created `SecurityConfig.java` to permit access to OpenAPI endpoints
- Configured to allow `/v3/api-docs/**`, `/swagger-ui/**`, and `/swagger-ui.html` without authentication
- Maintains method-level security via `@PreAuthorize` annotations on controller endpoints

### Build and Runtime Setup
- Updated `springdoc-openapi-starter-webmvc-ui:2.6.0` to `2.7.0`
- Added `spring-boot-starter-web` dependency (required for servlet API)
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
- Command: `./mvnw -Popenapi verify -pl pos-people -am -DskipTests`
- Output: `pos-people/target/openapi.json` (≈15 KB, includes all annotated endpoints)
- Build time: 16.314 seconds
- Status: ✅ **SUCCESS**

## Current Status
- ✅ Automated OpenAPI generation succeeds and produces `target/openapi.json`
- ✅ Application starts under the `openapi` profile with H2 in PostgreSQL mode
- ✅ Security properly configured to allow OpenAPI endpoint access
- ✅ All controller endpoints properly documented with Swagger/OpenAPI annotations

## How to Run
- Generate spec: `./mvnw -Popenapi verify -pl pos-people -am -DskipTests`
- View spec: `cat pos-people/target/openapi.json`
- Optional UI: start app (`./mvnw spring-boot:run -pl pos-people`) then open `http://localhost:8080/swagger-ui.html`

## API Endpoints Documented

### People Management
- GET /v1/people - Get all people
- POST /v1/people - Create a new person
- GET /v1/people/{personId} - Get person by ID
- PUT /v1/people/{personId} - Update an existing person
- DELETE /v1/people/{personId} - Delete a person

### Work Sessions
- POST /v1/people/workSessions/start - Start work session
- POST /v1/people/workSessions/stop - Stop work session
- POST /v1/people/workSessions/{id}/breaks/start - Start work session break
- POST /v1/people/workSessions/{id}/breaks/stop - Stop work session break

### Time Entry Adjustments
- POST /v1/people/timeEntries/adjustments - Create a time entry adjustment
- GET /v1/people/timeEntries/{timeEntryId}/adjustments - List adjustments for a time entry
- POST /v1/people/timeEntries/adjustments/{adjustmentId}/approve - Approve adjustment

### Time Entry Approvals
- POST /v1/people/timeEntries/approve - Batch approve time entries
- POST /v1/people/timeEntries/reject - Batch reject time entries

### Time Entry Exceptions
- GET /v1/people/exceptions - List exceptions (optional filter by employeeId)
- POST /v1/people/exceptions - Create a time entry exception
- POST /v1/people/exceptions/{exceptionId}/acknowledge - Acknowledge an exception
- POST /v1/people/exceptions/{exceptionId}/resolve - Resolve an exception
- POST /v1/people/exceptions/{exceptionId}/waive - Waive an exception

### People Availability
- GET /v1/people/availability - Get people availability (optional locationId and date filters)

### Reporting
- GET /v1/people/reports/attendanceJobtimeDiscrepancy - Get attendance and job time discrepancy report

## OpenAPI Endpoint Access
When the application is running, OpenAPI documentation is available at:
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

## Next Steps
- Consider wiring the `openapi` profile into CI to publish `openapi.json` as an artifact
- Add validation (lint/contract checks) on the generated spec as part of the pipeline
- Document behavioral contract tests that align with the OpenAPI spec
