# pos-shop-manager

Shop operations service for the Durion Positivity ETSMS platform. Manages shop appointments, bay and mobile unit scheduling, mechanic availability and assignments, workorder operational context, and conflict detection.

## Responsibilities

- Create and manage service appointments with scheduling conflict detection
- Schedule bays and mobile units for appointments
- Track mechanic availability and assign technicians to appointments
- Resolve scheduling conflicts with override support
- Provide workorder operational context (bay, mechanic, vehicle, customer details)
- Process workorder status events to update scheduling state
- Audit shop configuration changes
- Sync mechanic records from the people service

## Key Classes

- `AppointmentsService` — appointment lifecycle (create, reschedule, cancel)
- `ConflictDetectionService` — checks for overlapping bay/mechanic/mobile unit bookings
- `ConflictOverrideService` — records operator overrides for detected conflicts
- `MechanicAvailabilityService` — evaluates technician availability windows
- `WorkorderOperationalContextService` — assembles the full operational context for a workorder
- `ShopService` — shop-level configuration management

## API Endpoints

- `POST /v1/appointments` — create an appointment
- `GET /v1/appointments/{appointmentId}` — retrieve an appointment
- `PUT /v1/appointments/{appointmentId}/reschedule` — reschedule an appointment
- `DELETE /v1/appointments/{appointmentId}/cancel` — cancel an appointment
- `GET /v1/schedules/view` — shop schedule view
- `GET /v1/bays` / `GET /v1/{locationId}/bays/{bayId}` — retrieve bays
- `POST /v1/{locationId}/bays` — add a bay
- `DELETE /v1/{locationId}/bays/{bayId}` — remove a bay
- `POST /v1/{locationId}/mobileUnit` — register a mobile unit
- `GET /v1/{locationId}/workorders/{workorderId}/operationalContext` — workorder operational context
- `GET /v1/{locationId}/technicians/{personId}/person` — technician person detail

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-events` — `@EmitEvent` annotation and event registration

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-shop-manager -am spring-boot:run
```
