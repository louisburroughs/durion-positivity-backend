# pos-shop-manager

Shop operations service for the Durion Positivity ETSMS platform. Manages shop appointments, bay and mobile unit scheduling, mechanic availability and assignments, workorder operational context, and conflict detection.

## Responsibilities

- Create and manage service appointments, refusing HARD scheduling conflicts and warning on SOFT ones at submit time (DECISION-SHOPMGMT-002, CAP-326)
- Schedule bays and mobile units for appointments
- Track mechanic availability and assign technicians to appointments
- Record manager overrides of SOFT scheduling conflicts (`shop:conflict:override`); a HARD conflict is never overridable
- Provide workorder operational context (bay, mechanic, vehicle, customer details)
- Serve the aggregate shop manager dashboard for a location in a single read
- Process workorder status events to update scheduling state
- Audit shop configuration changes
- Sync mechanic records from the people service

## Key Classes

- `AppointmentsService` — appointment lifecycle (create, reschedule, cancel)
- `SchedulingConflictEvaluator` — evaluates DECISION-SHOPMGMT-002's rules at create and reschedule (HOURS, BAY, MECHANIC, CAPACITY); `BAY_DOUBLE_BOOKED` is enforced by an exclusion constraint
- `ConflictOverrideService` — records a manager's override of SOFT scheduling conflicts (`shop:conflict:override`; HARD is never overridable)
- `MechanicAvailabilityService` — evaluates technician availability windows
- `WorkorderOperationalContextService` — assembles the full operational context for a workorder
- `ShopDashboardService` — the single-call dashboard read model over this module's local replicas
- `ShopService` — shop-level configuration management

## API Endpoints

- `POST /v1/appointments` — create an appointment. `201` with the new appointment; `200` when the
  request replays one that already exists (a repeated `Idempotency-Key`, or an exact keyless
  resubmission of the same booking); `409` with the DECISION-SHOPMGMT-002 envelope when a HARD rule
  fires (`FACILITY_CLOSED`, `OUTSIDE_OPERATING_HOURS`, `BAY_DOUBLE_BOOKED`, `MECHANIC_UNAVAILABLE`),
  listing every rule that fired with its code verbatim. SOFT rules (`FACILITY_NEAR_CAPACITY`) book
  and appear on the response as `conflicts[]`, each overridable until a manager overrides it.
- `POST /v1/appointments/{appointmentId}/conflict-override` — a manager accepts SOFT conflicts by id
  (`{conflictIds, overrideReason}`); requires `shop:conflict:override` and the appointment's location
  in scope. `400` for a conflict not recorded against the appointment, `409` for a HARD one (envelope,
  nothing written) or one already overridden (`CONFLICT_ALREADY_OVERRIDDEN`).
- `GET /v1/appointments/{appointmentId}` — retrieve an appointment
- `PUT /v1/appointments/{appointmentId}/reschedule` — reschedule an appointment; the same rules as creation apply, and the appointment's own slot does not count against it
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

Both roster endpoints require `shop:technician:view` and support optional `status`
and `skillCode` filters. Status defaults to `ACTIVE`. The location roster lists
mechanics whose person holds an ACTIVE `TECHNICIAN` staffing assignment at the
location (the `ext_people_staffing_assignment` replica); it is ordered by mechanic
last name, first name, and person ID before pagination, so it accepts `page` and
`size` but ignores `sort`. The mechanic roster honours `sort` and defaults to that
same ordering.

Competence is read from the `ext_person_credential` replica of the People domain's
credential aggregate (CAP-328) and never stored here. Each entry carries
`credentials`: every credential the person holds, with `skillCode`, the issuer's own
`sourceCredentialCode`, `issuedOn`, `expiresOn`, display-only `proficiency`, and a
`status` judged on the roster's reference date — the facility's local date for the
location roster (DECISION-SHOPMGMT-015), the clock's date for the mechanic roster.
An expired, revoked or superseded credential is listed with that status, not dropped
and not read as held. `skillCode` matches either the Durion skill code or the
issuer's code (`T4-BRAKES`), uppercase-and-trimmed on both sides, and counts only
credentials held on that date. The former `mechanic_skill` and `certification` tables,
the `PUT .../skills` and `POST /mechanics/bulk-ingest` endpoints and the `technician`
table are gone; credentials are ingested in pos-people
(`POST /v1/people/credentials/bulk-ingest`).

Both emit audit events registered in `internal/config/EventTypes` —
`SHOPMGR_MECHANIC_ROSTER_LIST` and `SHOPMGR_LOCATION_TECHNICIAN_LIST`, each with
the `search` latency preset.

## Scheduling conflicts (CAP-326)

The conflict model is DECISION-SHOPMGMT-002's, persisted: `conflict_rule` is a platform-global
catalog of eight rules whose `code` is the API reason code verbatim (one namespace, no mapping
table); `scheduling_conflict` records every rule that fired against a booking attempt, with
`appointment_id` null for a refused attempt; `conflict_override` is a manager's immutable
acceptance of one SOFT conflict, single-actor approved. The record's audit — HARD conflicts with
overrides, which should be zero — is a join and runs.

`SchedulingConflictEvaluator` runs at create and reschedule in the order HOURS, BAY, MECHANIC,
CAPACITY, and stops at the first HOURS refusal so a closed day is answered as closed, never as
full. Hours never published, an unknown timezone or no location replica row mean the HOURS rules
do not fire and a WARN is logged — an unknown fact is not a confirmed closure. The two SKILL rules
are evaluated from the `ext_person_credential` and `ext_catalog_service_skill` replicas (CAP-328,
CAP-329). `MECHANIC_OVERTIME` is seeded `is_active = false`: nothing supplies weekly hours yet, and
an active rule the evaluator never evaluates would advertise enforcement that does not exist.

`BAY_DOUBLE_BOOKED` is enforced by the database: `appointment_resource_no_overlap` (V8) is an
exclusion constraint on `(tenant_id, resource_id, tstzrange(start_at, end_at, '[)'))` over the
statuses in `AppointmentStatus.holdingAResource()`. The evaluator's pre-check is reporting; the
constraint is what makes two concurrent bookings of one bay yield exactly one appointment. A
refusal (`23P01`) is recorded as a `BAY_DOUBLE_BOOKED` conflict on a fresh connection by
`SchedulingConflictRecorder` — unless the refused insert was an exact keyless double-submit of the
appointment that won, in which case the winner is replayed with `200`.

### Skill rules (CAP-329, spec D10.1)

The two `SKILL` rules are evaluated at create and reschedule from replicas alone. The
booking's services are resolved on `ext_catalog_service` / `ext_catalog_service_skill`
(the `catalog.service.updated` v3 fact): a service whose requirements were never
configured contributes nothing — "not configured" is not "requires nothing" — and a
requirement applies when its GVWR class range covers the vehicle's `gvwr_class`
(`ext_vehicle`, CAP-327) or is unranged (ANY). Without a vehicle class only ANY
requirements apply and the detail says so. The technicians rostered at the location that
local day are checked on `ext_person_credential`, a credential counting only while held on
the facility-local date (DECISION-SHOPMGMT-015; expiry inclusive, REVOKED/SUPERSEDED never).
Nobody holding a required skill → `NO_COMPETENT_MECHANIC_ROSTERED` (SOFT, names the codes);
holders present but every one already the technician on an overlapping held appointment →
`COMPETENT_MECHANIC_UNAVAILABLE` (SOFT). Zero technicians at the location is
`MECHANIC_UNAVAILABLE` alone, never a competence failure (#2035 answer 5). Neither rule
withholds a booking; both are manager-overridable (`shop:conflict:override`).

### Assignment and competence (spec D10 (c), CAP-329)

Booking only warns; assignment is where competence has a consequence. `POST /v1/assignments`
resolves the appointment's service requests and vehicle class through `SkillRequirementResolver`
and, when a required skill is held by none of the assigned mechanics on the appointment's
facility-local date, creates the assignment in `AWAITING_SKILL_FULFILLMENT`
(DECISION-SHOPMGMT-010) rather than `ASSIGNED`; a request carrying an authorised `override`
(`shop:schedule:edit`, with a reason) assigns anyway. No requirement — none configured, or none
for this vehicle class — parks nothing. The status machine already allows
`AWAITING_SKILL_FULFILLMENT → ASSIGNED` once a holder is assigned.

## Opening search (`GET /v1/schedules/openings`, #2022)

"When is the next slot that fits a 90-minute alignment?" answered in one call, from replicas
alone (ADR-0044 §6) and in a fixed number of queries whatever the horizon: the location, its
bays, the services, the vehicle, the technician roster, everyone's credentials, and one
appointment read spanning the whole horizon. Advisory by the domain's own contract
(DECISION-SHOPMGMT-011): `POST /v1/appointments` decides, and every opening carries
`constraintsEvaluated` so nothing reads as enforcement (spec D11).

Parameters: `locationId`, `serviceIds` (1–10 catalog service ids), `durationMinutes` (1–1440),
`earliestStart`; optional `vehicleId` (resolves the GVWR class), `technicianId` (restricts to
openings that person can take), `horizonDays` (default and maximum 30, facility-local days from
`earliestStart`'s date), `limit` (default 10, maximum 50). Malformed values are 400; exceeding a
bound is 422 `OPENING_HORIZON_EXCEEDED` / `OPENING_LIMIT_EXCEEDED` / `OPENING_TOO_MANY_SERVICES`;
a location without a recognised timezone and published hours is 422 `LOCATION_HOURS_UNKNOWN`
(hours are HARD and Location is authoritative — without them every instant would read as open).

An opening is the earliest start in a free gap of one eligible bay at which the job, with the
location's `checkInBufferMinutes` before and `cleanupBufferMinutes` after, fits inside the gap and
inside the day's operating window, and at which a technician rostered that day (day grain, from
`ext_staffing_assignment`; PTO is not modelled) is not on an overlapping held appointment
(minute grain). One opening per gap per bay, at real minute resolution; closed days and
holiday closures are skipped, never reported as full. Ranking: earliest start, then `CERTIFIED`
before `AWAITING`, then bay.

Bay eligibility (CAP-325 D13/D14): a bay is eligible for an operation when it claims the
operation code in `serviceCapabilityCodes`; an operation no bay at the location claims is
general work, which every bay but a `WASH_DETAIL` one may do — general bays ranked before
specialty bays at the same start, so the rack stays free for alignment work without the shop
ever reading as full. A bay whose `maxDutyClass` is below the vehicle's class is out;
`bayEligibility` counts the two misses separately. Empty list reasons are exactly two:
`NO_ELIGIBLE_BAY_AT_LOCATION` and `ALL_ELIGIBLE_BAYS_BOOKED`.

Skill (CAP-329 D10, read through `SkillRequirementResolver`, the same reading the submit-time
evaluator uses): competence never withholds an opening. A technician holding every required
skill on the opening's facility-local date is preferred (`skillFulfillment: CERTIFIED`);
otherwise the opening names a free technician with `AWAITING` and `unmetSkillCodes`. The
window-invariant fact is reported once as `staffingAdvisory` alongside the (non-empty) list,
never as a `noOpeningReason`: `NO_COMPETENT_MECHANIC_ROSTERED` with `missingSkillCodes` and
`absenceScope` `NOT_AT_THIS_LOCATION` (nobody staffed here holds it) or `NOT_ROSTERED_THIS_DAY`
(a holder works here, not in the searched days); and `MECHANIC_UNAVAILABLE` when no technician
is rostered on any open day in the horizon (#2035 answer 5 — never a competence rule), in which
case the list is empty and `noOpeningReason` stays null. `NOT_IN_TENANT` and
`alternateLocations[]` are deliberately absent (DECISION-SHOPMGMT-012).

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

## Placeholder: mechanic shift window (#2060)

`GET /v1/shop-manager/{locationId}/technicians` carries five **PLACEHOLDER** fields per roster
entry — `shiftStart`, `shiftEnd`, `shiftMinutes`, `shiftSource`, `shiftStatus` — so the dispatch
board can render free hours today. They are derived by `LocationHoursShiftWindowService` from the
**location's operating hours** in the `ext_location` replica (`timezone`, `operating_hours`,
`holiday_closures`, read through the one existing `LocationHoursParser`), for the optional `date`
query parameter (default: today in the location's own timezone). They are **not** a person's
schedule: the platform has no per-person shift entity, so every mechanic at a location receives
the same window, and staggered shifts, part-timers, split shifts, overtime and PTO are invisible.

| `shiftStatus` | Meaning | Window fields |
| --- | --- | --- |
| `DERIVED` | the weekday's open/close in the location zone, emitted as UTC instants | set |
| `CLOSED` | a `holiday_closures` entry covers the date — a known fact | null |
| `UNKNOWN` | no replica, no usable timezone, unparsable hours, no entry for the weekday, or `openTime` not before `closeTime` (logged) — never a default window | null |

`shiftSource` is `LOCATION_HOURS` on every entry; `PERSON_SCHEDULE` is reserved for the real
implementation. The check-in and cleanup buffers are appointment concerns and are not folded in.
No new permission, table, event or replica: the read inherits `shop:technician:view` and the
endpoint's location-scope gate.

The real per-person window is [#71](https://github.com/louisburroughs/durion-positivity-backend/issues/71),
blocked on the HR availability contract question in
[#271](https://github.com/louisburroughs/durion-positivity-backend/issues/271). When it lands,
delete `LocationHoursShiftWindowService`, this section, and the placeholder wording on the
controller's `@Operation` and the DTO's `@Schema` descriptions; the fields stay and are then filled
from the person's schedule with `shiftSource = PERSON_SCHEDULE`.

## Local replicas (ADR-0044)

This module reads other domains only through read-only `ext_*` tables fed by their events; nothing
but the event consumer writes them, and no synchronous call crosses a domain wall.

| Table | Owner topic | Consumer |
| --- | --- | --- |
| `ext_customer_party` | `customer.events.v1` | `CustomerEventsListener` |
| `ext_vehicle` | `vehicle.events.v1` | `VehicleEventsListener` |
| `ext_people_staffing_assignment` | `people.events.v1` | `PeopleEventsListener` |
| `ext_person_credential` | `people.events.v1` | `PeopleEventsListener` |
| `ext_catalog_service`, `ext_catalog_service_skill` | `catalog.events.v1` | `CatalogEventsListener` |
| `ext_people_contact_person` | `people-contact.events.v1` | `PeopleContactEventsListener` |
| `ext_workorder` | `workorder.events.v1` | `WorkorderEventsListener` |
| `ext_bay`, `ext_mobile_unit` | `location.events.v1` | `LocationEventsListener` |
| `ext_location`, `ext_location_parent` | `location.events.v1` | `LocationEventsListener` |

**Bay/mobile-unit topology is event-sourced, not read live.** A synchronous `RestClient` into
pos-location would work today but is a domain→domain call that ADR-0044 R1 forbids, and no standing
grant covers it — it would require a new recorded ADR-0044 exception on the pos-warranty precedent
(#786). pos-workorder made the same call the other way in #1656.

**Consequence, stated plainly:** pos-location publishes bay and mobile-unit facts as of #1668 —
`location.bay.updated` / `location.bay.deleted` and `location.mobile-unit.updated` /
`location.mobile-unit.deleted` on `location.events.v1`, alongside the `location.location.*` and
`location.storage-location.updated` facts its `LocationFactPublisher` already emitted. The fact
contracts this module consumes are the canonical records in `pos-domain-events`
(`com.positivity.domainevents.location`); this module declares no mirror of them.

`ext_bay` and `ext_mobile_unit` nonetheless **start empty**, and the dashboard's `units[]` with
them, until the owner backfills: the facts are forward-only, so a bay or mobile unit that existed
before #1668 and has not been touched since emits nothing. Outbox replay cannot reach those rows
either — they have no outbox history. pos-location repairs this with the
`location.fact-backfill.requested` command (see `pos-location/README.md`, "Backfilling existing bays
and mobile units", and `docs/OPERATIONS_RUNBOOK.md`). `openWorkorders[]` is unaffected either way.

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

## Location scope (ADR-0061, #1872)

Every location-parameterised endpoint gates the caller's **location scope** on top of its
`@PreAuthorize` permission: the permission answers "may this caller do X", and
`SecurityContextHelper.locationScope().require(permission, locationId)` answers "…at this
location". A caller whose grant is location-scoped can no longer read or book at another shop by
changing the `locationId`. Tokens without the `loc_*` claims (pre-rollout) are unaffected. The
decisions are recorded in `location-scope.yaml` beside `openapi.yaml`, which CI checks:

| Operation | Shape | Permission | Notes |
| --- | --- | --- | --- |
| `AppointmentsController.createAppointment` | gate | `appointments:create` / `shop:schedule:edit` | body `locationId`; `hasAnyAuthority`, so denied only when **no held** alternate covers |
| `AppointmentsController.getAppointment` | gate | `appointments:view` / `shop:schedule:view` | sibling: gated on the stored appointment's location **after** the 404, so ids cannot be probed and the create gate cannot be bypassed |
| `AppointmentsController.rescheduleAppointment` | gate | `appointments:reschedule` | sibling: gated on the stored appointment's location after the 404 |
| `AppointmentsController.cancelAppointment` | gate | `appointments:cancel` | sibling: gated on the stored appointment's location after the 404 |
| `ScheduleController.viewSchedule` | gate | `shop:schedule:view` | query `locationId` |
| `ShopDashboardController.getShopDashboard` | gate | `shop:dashboard:view` | query `locationId` |
| `TechnicianController.listLocationTechnicians` | gate | `shop:technician:view` | path `locationId` |
| `TechnicianController.getTechnicianPerson` | gate | `shop:technician:view` | path `locationId` |

No endpoint here *narrows*: every `locationId` names the resource being acted on, none is an
optional list filter. A denial renders `403` with `ApiError.code = LOCATION_SCOPE_DENIED` (see
`docs/ERROR_ENVELOPE.md`); a malformed id is still `400` for every caller because Spring parses
the UUID before the gate runs. The `hasAnyAuthority` endpoints go through
`LocationScopeGuard.requireAny`, which consults only the alternates the caller actually holds.

The check runs in-process against the `ext_location` replica — never a per-request call to
pos-location. `ext_location` carries the two materialised, inclusive-of-self ancestor sets
(`financial_ancestor_ids` along the `FINANCIAL` parent chain; `other_ancestor_ids` along the union
of the seven non-financial parent types), and `ext_location_parent` holds the typed edges each
`location.location.updated` fact carries. `LocationEventsListener` replaces the child's edges from
the fact and `LocationHierarchyService.recomputeAncestors` rebuilds the sets for the location and
every replicated descendant, so a re-parent propagates and a parent arriving after its children
pushes its ancestry down. `LocationHierarchyService` is also the module's
`LocationAncestorResolver` bean; a location the replica does not hold answers empty sets, which a
scoped caller cannot cover (fail closed) — ingestion never fails on an unknown parent, only the
check does. The table starts empty until the owner replays (`POST .../facts/replay` on
pos-location); until then scoped callers are denied everywhere while unscoped tokens behave as
before.

`shop.id` **is** the pos-location location id by convention — every service resolves a request's
`locationId` through `ShopRepository` — but `shop` is this module's own scheduling configuration,
not a replica, carries no hierarchy, and is not consulted by the scope check.

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |

## Multitenancy (ADR-0062, WS3 wave 5)

This module runs on the ADR-0062 runtime: it depends on `pos-tenancy-common`, every scoped entity
extends `TenantScopedEntity`, and the global tables listed in `src/main/resources/db/tenancy-global-tables.txt`
carry `@TenantGlobal`. The request tenant is bound by `TenantContextFilter` from `X-Tenant-Id` (the gateway
injects it from the token's `tid`), the Kafka tenant by `TenantRecordInterceptor` from the `tenantId` record
header on every one of the module's consumers, and every connection checkout binds `app.current_tenant` for
row-level security. `pos.tenancy.default-tenant-id` still binds the alpha default tenant on every unbound path
(tokens issued before `tid`, records without the header).

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`, `FlywayConfig`).

The only global table is `processed_events`. The module has no outbox, no scheduled job and no native query.

Proof: `TenantIsolationIT` (tenant A's `certification` row is invisible to tenant B and to an unbound
connection, through the repository and through raw SQL) and `TenancySchemaConformanceIT` (every
non-whitelisted table has `tenant_id`, RLS enabled and forced, and the `tenant_isolation` policy; the pool is
`pos_app` with no bypass), both on Testcontainers Postgres (`./mvnw -pl pos-shop-manager -am verify`).

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-tenancy-common` — ADR-0062 tenant context, connection binding, Hibernate resolver, Kafka propagation
- `pos-events` — `@EmitEvent` annotation and event registration

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`: `V1__baseline_shop_manager.sql` (the
2026-09-09 flattened baseline with the tenancy schema on every scoped table) and the repeatable mechanics seed,
which binds the alpha default tenant for its own transaction.

## Development

```bash
./mvnw -pl pos-shop-manager -am spring-boot:run
```
