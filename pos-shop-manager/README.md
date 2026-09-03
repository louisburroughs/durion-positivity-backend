# pos-shop-manager

Shop operations service for the Durion Positivity ETSMS platform. Manages shop appointments, bay and mobile unit scheduling, mechanic availability and assignments, workorder operational context, and conflict detection.

## Responsibilities

- Create and manage service appointments with scheduling conflict detection
- Schedule bays and mobile units for appointments
- Track mechanic availability and assign technicians to appointments
- Resolve scheduling conflicts with override support
- Provide workorder operational context (bay, mechanic, vehicle, customer details)
- Serve the aggregate shop manager dashboard for a location in a single read
- Process workorder status events to update scheduling state
- Audit shop configuration changes
- Sync mechanic records from the people service

## Key Classes

- `AppointmentsService` — appointment lifecycle (create, reschedule, cancel)
- `ConflictDetectionService` — checks for overlapping bay/mechanic/mobile unit bookings
- `ConflictOverrideService` — records operator overrides for detected conflicts
- `MechanicAvailabilityService` — evaluates technician availability windows
- `WorkorderOperationalContextService` — assembles the full operational context for a workorder
- `ShopDashboardService` — the single-call dashboard read model over this module's local replicas
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
- `GET /v1/shop-manager/mechanics` — paged HR-synchronized mechanic roster
- `GET /v1/shop-manager/{locationId}/technicians` — paged location technician roster
- `GET /v1/shop-manager/{locationId}/technicians/{personId}/person` — technician person detail
- `GET /v1/shop-dashboard?locationId={uuid}&date={yyyy-MM-dd}` — aggregate shop dashboard

Both roster endpoints require `shop:technician:view` and support optional exact
`status` and `skillCode` filters. Status defaults to `ACTIVE`. The location roster
is ordered by mechanic last name, first name, and person ID before pagination, so
it accepts `page` and `size` but ignores `sort`; the mechanic roster honours `sort`
and defaults to that same ordering.

Both emit audit events registered in `internal/config/EventTypes` —
`SHOPMGR_MECHANIC_ROSTER_LIST` and `SHOPMGR_LOCATION_TECHNICIAN_LIST`, each with
the `search` latency preset.

## Shop dashboard (`GET /v1/shop-dashboard`)

One call returns everything a shop manager board shows for a location, requiring
`shop:dashboard:view`:

- `units[]` — every bay and mobile unit at the location as a discriminated union tagged
  `unitType: BAY | MOBILE_UNIT`, each carrying the workorder on it or an explicit `null`. A unit
  holding no work is present with a null assignment, never omitted. This is **not** a persisted
  entity: the union is synthesized per request, because bays and mobile units belong to
  pos-location.
- `openWorkorders[]` — every open workorder at the location, with `unitId`/`unitName`/`unitType`
  populated when assigned and `null` when not. It is a **superset** of the assignments in `units[]`,
  not a filtered view of them, and it is capped at 200 rows with `openWorkordersTruncated: true`
  when the cap is hit.

`date` is optional, defaults to the location's local today (ADR-0038 date-only string, resolved
through the shop's timezone and falling back to UTC), and scopes **only** the unit roster — never
`openWorkorders`. The scope is an **upper** bound, not an equality: an open workorder scheduled on
or before `date` (or with no scheduled date at all) still occupies its unit, because multi-day jobs
are ordinary and a car on the lift since yesterday has not freed the bay. Work scheduled for a
later day is booked, not occupying — bounding it this way avoids trading a false-free unit for a
false-occupied one.

`mechanicName` names the **first assigned** technician specifically. If that person has not
replicated into this module yet it is `null` — never the next technician who happens to resolve,
because naming the wrong technician is worse than naming none. `mechanicNames` lists the
technicians that did resolve, so it can be shorter than the workorder's assignment.

`openWorkorders` is server-sorted: unassigned first, then by status band (blocked → queued →
active → ready), then `promisedAt` ascending with nulls last, then `workorderNumber`. The sort runs
in SQL and the cap is applied after it, so the returned rows are the first page of that order.

Occupancy is a read-side consequence of the open-status filter, not stored state: a COMPLETED or
CANCELLED workorder frees its unit with no write and no workexec schema change, while a
READY_FOR_PICKUP one still occupies it because the vehicle has not left. "Open" is derived as the
complement of the terminal statuses, mirroring how pos-workorder derives `getOpenStatuses()`, so a
status added upstream shows as open rather than silently disappearing.

Errors follow ADR-0017 in the standard `ApiError` envelope: `400` for a malformed `locationId` or
`date`, `403` without the permission, `404` for an unknown location. The read emits the
`SHOPMGR_SHOP_DASHBOARD_VIEW` audit event and changes no state.

## Local replicas (ADR-0044)

This module reads other domains only through read-only `ext_*` tables fed by their events; nothing
but the event consumer writes them, and no synchronous call crosses a domain wall.

| Table | Owner topic | Consumer |
| --- | --- | --- |
| `ext_customer_party` | `customer.events.v1` | `CustomerEventsListener` |
| `ext_vehicle` | `vehicle.events.v1` | `VehicleEventsListener` |
| `ext_people_staffing_assignment` | `people.events.v1` | `PeopleEventsListener` |
| `ext_people_contact_person` | `people-contact.events.v1` | `PeopleContactEventsListener` |
| `ext_workorder` | `workorder.events.v1` | `WorkorderEventsListener` |
| `ext_bay`, `ext_mobile_unit` | `location.events.v1` | `LocationEventsListener` |

**Bay/mobile-unit topology is event-sourced, not read live.** A synchronous `RestClient` into
pos-location would work today but is a domain→domain call that ADR-0044 R1 forbids, and no standing
grant covers it — it would require a new recorded ADR-0044 exception on the pos-warranty precedent
(#786). pos-workorder made the same call the other way in #1656.

**Consequence, stated plainly:** pos-location does not publish bay or mobile-unit facts yet (its
`LocationFactPublisher` emits `location.location.*` and `location.storage-location.updated` only),
so `ext_bay` and `ext_mobile_unit` start empty and the dashboard's `units[]` is empty in production
until that publisher lands. `openWorkorders[]` is unaffected. The fact contracts this module needs
are declared in `internal/dto/location` in the shape they should take in `pos-domain-events`.

A unit's `active` flag is **derived** from the owner's `status`, allow-listing `ACTIVE` in any
casing: pos-location's `BayEntity` and `MobileUnitEntity` carry no boolean active field, and a
mobile unit's status is a free-text column, so an absent, blank or unrecognised status means not
active. pos-workorder derives the same fact the same way (#1656); the two consumers mirror one
upstream aggregate and must not disagree about which units are in service.

`WorkorderEventsListener` raises an in-process `WorkorderStatusChangedEvent` that keeps the linked
appointment's status in step. It is consumed `AFTER_COMMIT`, in its own transaction, and a failure
in it is logged and swallowed: the appointment timeline is a downstream projection, so losing one
entry is recoverable, whereas letting it roll back the replica write and its `processed_events` row
would lose the update *and* redeliver the record forever. Kafka's bounded retry and dead-lettering
still cover everything that fails before commit.

The dashboard is a read model over an at-least-once feed with retry and backoff: it is not expected
to reflect an assignment change with zero latency, and its OpenAPI description says so.

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
