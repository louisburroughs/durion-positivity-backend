# pos-event-receiver

Central event aggregation and storage service for the Durion Positivity ETSMS platform. Receives `@EmitEvent` emissions from all microservices, persists them, computes hourly roll-ups, and exposes summary and query endpoints for observability dashboards and audit tooling.

## Responsibilities

- Receive and persist emitted events from all `pos-*` services
- Register and manage event type definitions with performance thresholds
- Compute hourly event count roll-ups for reporting
- Expose event summary endpoints (last hour, last day, last week)
- Authenticate inbound event writes using a shared API secret (avoids circular JWT dependency)
- Pre-register own event types at startup via `EventTypeInitializer`

## Key Classes

- `EmitEventService` — processes inbound event emission requests and persists to `emitted_event`
- `EventTypeService` — CRUD for event type registrations (type code, thresholds, description)
- `EventSummaryService` — aggregates and returns event count summaries by time window
- `EmitEventController` — handles `POST /v1/events` and `GET /v1/events/{id}`
- `EventTypeController` — manages event type catalog via `PUT /v1/eventTypes/code/{typeCode}`
- `EventsApiSecurityFilter` — validates the `X-Events-Api-Secret` header on write endpoints

## API Endpoints

- `POST /v1/events` — emit an event
- `GET /v1/events/{id}` — retrieve an event by ID
- `GET /v1/events/active` — list active events
- `DELETE /v1/events/{id}` — delete an event record
- `GET /v1/eventTypes` — list all event type registrations
- `GET /v1/eventTypes/code/{typeCode}` — get event type by code
- `PUT /v1/eventTypes/code/{typeCode}` — register or update an event type
- `GET /v1/events/summary/lastHour` — event counts for last hour
- `GET /v1/events/summary/lastDay` — event counts for last day
- `GET /v1/events/summary/lastWeek` — event counts for last week

## Configuration

| Property                | Default  | Description                                  |
| ----------------------- | -------- | -------------------------------------------- |
| `pos.events.api-secret` | (empty)  | Shared secret for event write authentication |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL                    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL                 |

## Dependencies

No internal `pos-*` module dependencies (avoids circular dependency with `pos-security-service`).

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-event-receiver -am spring-boot:run
```
