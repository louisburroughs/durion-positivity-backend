# pos-people

HR and workforce management service for the Durion Positivity ETSMS platform. Manages employees,
time entries, work sessions, staffing assignments, availability, and timekeeping ingestion.
Person identity, contact points, and user-person links are owned by pos-people-contact since the
ADR-0044 Phase 3 split (#874/#875); this module reads them from event-fed
`ext_people_contact_*` replicas and sends identity writes as
`people-contact.person.upsert-requested` commands.

## Responsibilities

- Manage employee employment records (identity attributes live in pos-people-contact)
- Track time entries, adjustments, and exceptions per employee
- Record work sessions (clock-in/clock-out) and compute job time totals
- Manage staffing assignments across locations
- Evaluate employee availability for scheduling
- Publish `people.employee.updated` / `people.staffing-assignment.updated` facts on
  `people.events.v1` via a transactional outbox (ADR-0044)
- Translate between user identity and person records (`UserPersonTranslationService`)
- Ingest timekeeping data from external sources (`TimekeepingIngestionService`)
- Support bulk employee import via `POST /v1/people/bulk-ingest`

## Key Classes

- `EmployeeService` — employee CRUD and lifecycle (hire, terminate)
- `PersonService` — person record management linked to party
- `TimeEntryService` — time entry lifecycle (submit, approve, adjust)
- `WorkSessionService` — work session tracking with clock-in/out timestamps
- `StaffingAssignmentService` — location-based staffing assignments
- `UserPersonLinkService` — links a user account UUID to a person record

## API Endpoints

- `GET /v1/people/{personId}` — retrieve a person
- `GET /v1/people/{employeeId}` — retrieve an employee
- `DELETE /v1/people/{personId}` — deactivate a person
- `GET /v1/people/availability` — employee availability query
- `GET /v1/people/me/primary-location` — authenticated user's primary location
- `GET /v1/people/{personId}/users` — linked user accounts for a person
- `GET /v1/people/users/{userId}/person` — person record for a user ID
- `GET /v1/people/{assignmentId}` — retrieve a staffing assignment
- `DELETE /v1/people/{assignmentId}` — remove a staffing assignment
- `GET /v1/people/{timeEntryId}/adjustments` — adjustments for a time entry
- `GET /v1/people/approvedTime` — approved time summary
- `POST /v1/people/bulk-ingest` — bulk import employees (auth: `people:employee:create`)

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
./mvnw -pl pos-people -am spring-boot:run
```
