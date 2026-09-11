# pos-people

HR and workforce management service for the Durion Positivity ETSMS platform. Manages employees,
time entries, work sessions, staffing assignments, availability, and timekeeping ingestion.
Person identity, contact points, and user-person links are owned by pos-people-contact since the
ADR-0044 Phase 3 split (#874/#875); this module reads them from event-fed
`ext_people_contact_*` replicas and sends identity writes as
`people-contact.person.upsert-requested` commands.

## Responsibilities

- Manage employee employment records (identity attributes live in pos-people-contact)
- Track time entries — attendance only: clock-in, clock-out and breaks — plus their adjustments
  and exceptions per employee. Time a technician spends on a workorder task is a separate record
  owned by pos-workorder; a time entry carries no workorder reference (#1573).
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
- `GET /v1/people/employees/{employeeId}` — retrieve an employee profile, including the `contactInfo`
  block (address, personal phone numbers and emergency contact)
  (auth: `people:employee_pii:view`, not the `people:employee:view` the structural reads use — #1898)
- `DELETE /v1/people/{personId}` — deactivate a person
- `GET /v1/people/availability` — employee availability query (auth: `people:availability:view`)
- `GET /v1/people/me/primary-location` — authenticated user's primary location
  (auth: `people:self:view`)
- `GET /v1/people/me/locations` — authenticated user's active location assignments
  (auth: `people:self:view`)
- `GET /v1/people/{personId}/users` — linked user accounts for a person
- `GET /v1/people/users/{userId}/person` — person record for a user ID
- `GET /v1/people/{assignmentId}` — retrieve a staffing assignment
- `DELETE /v1/people/{assignmentId}` — remove a staffing assignment
- `GET /v1/people/timeEntries` — attendance time entries (clock-in, clock-out, breaks) for the
  approvals queue, filterable by `status`, `workDate` (+ `timeZone`), `employeeId` and
  `locationId`, oldest submission first (auth: `people:timeEntry:view`)
- `GET /v1/people/timeEntries/{timeEntryId}` — one attendance time entry
  (auth: `people:timeEntry:view`)
- `GET /v1/people/{timeEntryId}/adjustments` — adjustments for a time entry
- `GET /v1/people/approvedTime` — approved time summary
- `POST /v1/people/bulk-ingest` — bulk import employees (auth: `people:employee:create`)

## Location scope

Location-scope enforcement (ADR-0061, #1871/#1872) is decided per operation in
`location-scope.yaml` beside `openapi.yaml`; `scripts/audit-rbac.py --check` fails on a missing or
stale entry. `LocationHierarchyService` is the module's `LocationAncestorResolver` bean (ancestor
sets on the `ext_location` replica) and also serves `descendantsOf` for narrowing. Callers whose
token predates the scope claims, or whose permission is not location-scoped, are unaffected. A
refused location is a 403 with `ApiError.code` `LOCATION_SCOPE_DENIED` and never echoes the id.

| Operation | Shape | Permission | Where |
| --- | --- | --- | --- |
| `GET /v1/people/timeEntries` | narrow | `people:timeEntry:view` | `TimeEntryServiceImpl.listTimeEntries` |
| `GET /v1/people/availability` | narrow | `people:availability:view` | `PeopleAvailabilityServiceImpl.getPeopleAvailability` |
| `GET /v1/people/reports/attendanceJobtimeDiscrepancy` | narrow | `accounting:time:export` | `PeopleReportsServiceImpl.getAttendanceDiscrepancyReport` |
| `GET /v1/people/reports/approvedTime` | gate (every `locationId`) | `accounting:time:export` | `PeopleReportsServiceImpl.getApprovedTimeForExport` |
| `POST /v1/people/staffing/assignments` | gate (request `locationId`) | `people:employee:edit` | `StaffingAssignmentServiceImpl.create` |
| `PUT /v1/people/staffing/assignments/{id}` | gate (existing and requested location) | `people:employee:edit` | `StaffingAssignmentServiceImpl.update` |
| `DELETE /v1/people/staffing/assignments/{id}` | gate (existing location) | `people:employee:edit` | `StaffingAssignmentServiceImpl.end` |
| `POST /v1/people/bulk-ingest` | unscoped | — | ADMIN-only load; the location is a payload default |

- **gate** — the named location must be within the caller's reach or the request is refused.
  Gates run after the existence checks so a 404 precedes a 403 and ids cannot be probed.
- **narrow** — a named location is gated; with no location a scoped caller sees only their reach
  (assigned nodes plus replicated descendants on the scoped dimension), and an empty reach is an
  empty result rather than a refusal. For availability the defaulted location is the requester's
  own, so the narrowed result is that location's rows when covered and an empty list otherwise.
- Staffing assignments feed a person's location-scope claims, so every mutation is gated: a
  LOCATION-scoped HR user can only assign, move or end assignments within their own reach.

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |

## Multitenancy (ADR-0062, WS3 wave 9)

This module runs on the ADR-0062 runtime: it depends on `pos-tenancy-common`, every scoped entity
extends `TenantScopedEntity`, and the two global tables (`event_outbox`, `processed_events`, listed in
`src/main/resources/db/tenancy-global-tables.txt`) carry `@TenantGlobal`. The request tenant is bound by
`TenantContextFilter` from `X-Tenant-Id` (the gateway injects it from the token's `tid`), the Kafka tenant by
`TenantRecordInterceptor` from the `tenantId` record header on every one of the module's consumers, and every connection checkout binds
`app.current_tenant` for row-level security. `pos.tenancy.default-tenant-id` still binds the alpha default
tenant on every unbound path (tokens issued before `tid`, records without the header).

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`, `FlywayConfig`).

The outbox row carries the producing tenant as data (`tenant_id`, stamped from the bound tenant by
`OutboxEventWriter`, added by `V2__event_outbox_tenant_id.sql`):

| Job | Classification | Why |
| --- | --- | --- |
| `OutboxPublisher.publishPending` | platform-scoped | Drains `event_outbox`; each row's `tenant_id` becomes the record header |
| `ManifestPublisher.publishDueManifest` | platform-scoped | Summarises `event_outbox` per window across tenants; each manifest is a platform-tenant record until per-tenant manifests land in plan WS4-3 |
| `TimePeriodRolloverScheduler.runScheduledRollover` | per-tenant | `time_period` and the entries it closes are scoped; one rollover per tenant of the registry |
There are no native queries. The `pg` test profile is strict (the transitional `connection-init-sql` binding
is gone); `FlywayMigrationIT` keeps its own `@ServiceConnection` container as the owner.

Proof: `TenantIsolationIT` (tenant A's `ext_location` row is invisible to tenant B and to an unbound
connection, through the repository and through raw SQL) and `TenancySchemaConformanceIT` (every
non-whitelisted table has `tenant_id`, RLS enabled and forced, and the `tenant_isolation` policy; the pool is
`pos_app` with no bypass), both on Testcontainers Postgres (`./mvnw -pl pos-people -am verify`).

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
