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
  `resourceType` (`BAY` | `MOBILE_UNIT` | `UNASSIGNED`, DECISION-SHOPMGMT-003) is persisted
  verbatim, resolved as follows — submit is authoritative (DECISION-SHOPMGMT-011), so omitting
  `resourceType` is never a way to skip DECISION-SHOPMGMT-021 eligibility on a real `resourceId`:
  no `resourceId` resolves to `UNASSIGNED` (a stated `BAY`/`MOBILE_UNIT` with no `resourceId` is
  `400 VALIDATION_ERROR`, field `resourceId`); a `resourceId` with `resourceType` explicitly
  `UNASSIGNED` is refused the same way (contradictory); a `resourceId` with `resourceType` omitted
  is **inferred** — an `ext_bay` row for that id makes it `BAY`, else an `ext_mobile_unit` row makes
  it `MOBILE_UNIT`, else `503 LOCATION_REPLICATION_PENDING` (`422 SERVICE_POSITION_INVALID` when the id is not a
  UUID) — and validated exactly as if the caller had
  stated it. A stated `BAY`/`MOBILE_UNIT` is validated against DECISION-SHOPMGMT-021 (below); a
  failure is `422` with one of the `SERVICE_POSITION_*` codes, never overridable.
- `POST /v1/appointments/{appointmentId}/conflict-override` — a manager accepts SOFT conflicts by id
  (`{conflictIds, overrideReason}`); requires `shop:conflict:override` and the appointment's location
  in scope. `400` for a conflict not recorded against the appointment, `409` for a HARD one (envelope,
  nothing written) or one already overridden (`CONFLICT_ALREADY_OVERRIDDEN`).
- `GET /v1/appointments/{appointmentId}` — retrieve an appointment. The response carries
  `affected` (DECISION-SHOPMGMT-022, below).
- `PUT /v1/appointments/{appointmentId}/reschedule` — reschedule an appointment; the same rules as
  creation apply, including DECISION-SHOPMGMT-021 bay/mobile-unit eligibility against the resource
  the appointment ends up on. By default that is the appointment's own (unchanged) resource, and
  the appointment's own slot does not count against it; optional `newResourceType`/`newResourceId`
  move it onto a different bay or mobile unit instead (DECISION-SHOPMGMT-022 rule 3) — only the new
  resource is validated, never the old one, so moving off a now-ineligible resource always
  succeeds. DECISION-SHOPMGMT-004's reschedule allowance (below) also applies.
- `DELETE /v1/appointments/{appointmentId}/cancel` — cancel an appointment
- `GET /v1/schedules/view` — shop schedule view. Each event carries `affected`, and the optional
  `affected` query parameter (`true`/`false`) filters the board to only affected or only
  unaffected appointments (DECISION-SHOPMGMT-022, below); omitted returns both.
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

Each roster row identifies the mechanic by `mechanicPersonId`, the People-domain
person id (ADR-0015 §7 I7) and the value `POST /v1/appointments/{id}/assignments`
takes as `mechanicPersonId`, so a row's id can be posted as it is. `mechanicRecordId`
is this module's own surrogate key for the mechanic row: not a person id, not a
cross-service identifier. Both are always present. Neither roster publishes
`mechanicId` or `personId` any more (#2363; the assignment response made the same
change in #2123); the mechanic roster's sort key for the person id is still
`personId`, the entity property. The internal `MechanicAvailabilityResult` uses
the same two names.

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

### What a refusal says (#2139, #2140)

A conflict's message is its `conflict_rule.message_template` rendered by the evaluator, and two
things about that text are contractual:

- **Every template that quotes a window names the zone the window is in** (`{zone}`, rendered as the
  IANA id). Times render in the facility's timezone (DECISION-SHOPMGMT-015), so a booking sent as
  `09:00Z` at an Eastern site is refused for `04:00–05:00 America/New_York`. Without the zone the
  converted time reads as a platform arithmetic error rather than as the facility-local conversion it
  is. When the location's zone is unknown the times render in UTC and `{zone}` says so.
- **`MECHANIC_UNAVAILABLE` names the staffing fact, not presence.** The rule asks whether an ACTIVE
  TECHNICIAN staffing assignment at the location covers the booking's facility-local date, which has
  two different noes; the message names which one fired — no assignment at the location at all, or
  assignments that exist with none effective on that date. A shop with seven technicians whose
  assignments begin after the date being asked about is staffed, and "no mechanic is present" was not
  something a caller could act on. Effective dates are read as written and never adjusted toward the
  question, so a date before an assignment begins genuinely has nobody assigned on it — as true of a
  historical query as of a booking. The `/{locationId}/technicians` roster applies the same
  effective-date coverage for its roster `date`, so the board and a booking on that date count the
  same technicians (#2140).

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
holiday closures are skipped, never reported as full. Ranking (search only; never changes
eligibility): earliest start, then `CERTIFIED` before `AWAITING`, then general bays before
specialty bays doing general work (D14 rule 6), then a *weak* best-fit tiebreak — the smallest
adequate `maxDutyClass`, a null ceiling read as class 8 — then `displayOrder` (not yet a replica
field on this branch) or name.

### Bay eligibility (CAP-325 D13/D14, DECISION-SHOPMGMT-021)

`BayEligibilityService` is the **one** eligibility function, shared by the opening search (which
filters against it) and appointment create/reschedule (which refuse against it): search, submit
and reschedule never disagree. **Specialty is defined by the tenant's bay-type specialty map**
(D14.1, replicated from `location.bay-specialty-map.updated` into `ext_bay_specialty_map` /
`ext_bay_type`), not by which bays happen to be active — an operation is specialty iff the map
names it for some `BayType`, and a bay must claim every specialty operation on the appointment (in
its own `serviceCapabilityCodes`) to take it; a specialty operation no active bay at the location
claims is unbookable there, never falling back to general work. An operation the map does not name
is general work, open to any bay whose `accepts_general_work` is true (`false` only for
`WASH_DETAIL`, DECISION-LOCATION-025). A bay whose `maxDutyClass` is below the vehicle's GVWR class
is out (skipped when either is null); `bayEligibility` counts the two misses
(`excludedByCapability`, `excludedByDutyClass`) separately. Empty list reasons are exactly two:
`NO_ELIGIBLE_BAY_AT_LOCATION` and `ALL_ELIGIBLE_BAYS_BOOKED`. While a tenant's specialty map has
not arrived yet (an empty replica), specialty is derived instead from whichever of the location's
bays claims the operation — today's pre-replica behaviour, logged once per tenant (WARN).

**`resourceType` resolution is not a way around eligibility** (DECISION-SHOPMGMT-011: submit is
authoritative). No `resourceId` resolves to `UNASSIGNED` (no resource checks); a `resourceId` with
`resourceType` explicitly `UNASSIGNED` is refused as contradictory (`400 VALIDATION_ERROR`, field
`resourceId`), as is a stated `BAY`/`MOBILE_UNIT` with no `resourceId`. A `resourceId` with
`resourceType` omitted is **inferred**: an `ext_bay` row for that id makes it `BAY`, else an
`ext_mobile_unit` row makes it `MOBILE_UNIT`; a well-formed id matching neither is
`503 LOCATION_REPLICATION_PENDING` (it may not have replicated yet), and a malformed one is
`422 SERVICE_POSITION_INVALID`.
Reschedule applies the same resolution to the appointment's own (stored) `resourceType`/`resourceId`
— a `NULL` or otherwise unrecognised stored `resourceType` beside a real `resourceId` is inferred
and validated exactly as a fresh submit would, not silently skipped; the one exception is a stored
value of `TECHNICIAN`, a distinct, already-existing reading used elsewhere in this module for
mechanic-busy tracking (never written by this service), which stays skipped.

**Submit and reschedule** (`resourceType` `BAY` or `MOBILE_UNIT`, stated or inferred) refuse with
**422** and no override, `fieldErrors` naming `resourceId`:

| Condition | Code |
| --- | --- |
| `resourceId` malformed, of the other resource kind, or at another location | `SERVICE_POSITION_INVALID` |
| Resource not `ACTIVE` (out of service or retired) | `SERVICE_POSITION_INACTIVE` |
| A `BAY` does not claim a specialty operation on the appointment, or takes no general work and the appointment has general operations | `SERVICE_POSITION_NOT_EQUIPPED` |
| Vehicle GVWR class above the bay's `maxDutyClass` | `SERVICE_POSITION_DUTY_CLASS_EXCEEDED` |

`MOBILE_UNIT` (stated or inferred) runs existence, location and active checks only, until mobile
scheduling lands (DECISION-SHOPMGMT-023) — no specialty or duty-class check. The near-capacity
divisor (`FACILITY_NEAR_CAPACITY`, above) counts only active bays with `accepts_general_work`.
Existing appointments' own resource is not re-validated except on reschedule (above);
DECISION-SHOPMGMT-022 (below) surfaces any that sit in a now-ineligible bay without forcing a
reschedule.

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

## Affected appointments and the reschedule allowance (#2270)

### Affected (DECISION-SHOPMGMT-022)

When a bay or mobile unit goes out of service, is retired, or loses the eligibility a booking
relied on, `pos-location` never blocks the change — it cannot see appointments (ADR-0044) and the
equipment is broken whatever the system says. Instead, `pos-shop-manager` derives **`affected`** at
read time, for every held, not-yet-started appointment on a `BAY`/`MOBILE_UNIT` resource:

- status is `SCHEDULED` — the only "held, pre-work" status in `AppointmentStatus`; every other
  pre-terminal status (`CHECKED_IN`, `WORK_IN_PROGRESS`, `WAITING_FOR_PARTS`, `QUALITY_CHECK`,
  `READY_FOR_PICKUP`, `REOPENED`) means the visit is already under way, so moving it is a
  shop-floor reassignment, not a reschedule-queue item;
- `startAt` is in the future;
- `resourceType` is `BAY` or `MOBILE_UNIT` — never `UNASSIGNED` or the legacy `TECHNICIAN` reading;
- and the named resource is missing from its replica, is not `ACTIVE` (`ext_bay`/`ext_mobile_unit`
  collapse both `OUT_OF_SERVICE` and `RETIRED` into one `active=false` row, so "not ACTIVE" already
  covers both), or — for a `BAY` only — no longer passes the DECISION-SHOPMGMT-021 eligibility rule
  (`BayEligibilityService.refusalFor`). A mobile unit runs existence and active checks only, same
  as `resolveAndValidateResourceType` (DECISION-SHOPMGMT-023: no per-unit eligibility check exists
  yet beyond that).

Nothing is stored: `AffectedAppointmentEvaluator` computes it fresh on every read, batching its
replica/service-request/vehicle reads once per location rather than once per appointment, so a
resource returning to service (or regaining eligibility) clears the flag on the very next read. It
is exposed as `affected` on `GET /v1/appointments/{id}` and on every event in
`GET /v1/schedules/view`, which also takes an optional `affected` (`true`/`false`) query parameter
— `true` is the reschedule queue. A retired or out-of-service bay/mobile unit keeps its replica row
(DECISION-LOCATION-026), including its `name`, so an affected appointment's resource is still
nameable on the board, not a bare id.

### The reschedule allowance and its shop-caused exemption (DECISION-SHOPMGMT-004)

Up to 2 reschedules of an appointment are free. The 3rd and later reschedule needs the caller to
hold `appointments:reschedule:approve` and to send a non-blank `approvalReason` (max 1000
characters) — permission-only gating, enforced server-side, no separate approval workflow. Missing
the permission is `403` (the module's ordinary `AccessDeniedException` path, `RescheduleApprovalGuard`
mirroring `ConflictOverrideService`'s always-`@PreAuthorize`-gated shape); holding it but sending no
`approvalReason` is `422 RESCHEDULE_APPROVAL_REASON_REQUIRED` (`fieldErrors` names
`approvalReason`). A refused reschedule records no history row, so it never counts.

A reschedule is **shop-caused**, and so exempt from the count, when either its `reason` is
`EQUIPMENT_ISSUE` or the appointment was DECISION-SHOPMGMT-022 affected **at the moment of the
reschedule, evaluated before any field of it changes** — moving an appointment off a bay that just
failed is never held against the customer. `reschedule_history.counts_against_allowance` records
which; the allowance is the count of an appointment's history rows with it `true`.
`RescheduleAppointmentRequest.newResourceType`/`newResourceId` (DECISION-SHOPMGMT-022 rule 3, above)
and `approvalReason` are independent of each other — a reschedule can move the resource, need
approval, both, or neither.

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
| `ext_people_staffing_assignment` | `people.events.v1` | `PeopleEventsListener` (role stored trimmed and upper-cased, #2173) |
| `ext_person_credential` | `people.events.v1` | `PeopleEventsListener` |
| `ext_catalog_service`, `ext_catalog_service_skill` | `catalog.events.v1` | `CatalogEventsListener` |
| `ext_people_contact_person` | `people-contact.events.v1` | `PeopleContactEventsListener` |
| `ext_workorder` | `workorder.events.v1` | `WorkorderEventsListener` |
| `ext_workorder_position` | `workorder.events.v1` | `WorkorderEventsListener` (the fact's `positions`, replace-set per fact, #2530) |
| `ext_bay`, `ext_mobile_unit` | `location.events.v1` | `LocationEventsListener` |
| `ext_location`, `ext_location_parent` | `location.events.v1` | `LocationEventsListener` |
| `ext_bay_type`, `ext_bay_specialty_map` | `location.events.v1` | `LocationEventsListener` (#2261) |

### What holds a bay on the capacity read (#2530)

`GET /v1/schedules/capacity` counts work that actually held a bay, appointment or not. Its unit is a
*bay hold*: one bay, one window, one job. Holds come from two sources and a job contributes through
exactly one of them for any instant, so nothing is counted twice:

- **Position holds** — every interval a workorder held a bay for, from `ext_workorder_position`. The
  bay is the one actually held, so a job worked on a different bay than booked is charged where it
  stood, and a job that moved is charged to each bay for its own interval. An interval still open ends
  at the instant the view is generated and no later. A walk-in has nothing but these.
- **Appointment holds** — an appointment whose linked workorder has taken a bay contributes only what
  is still booked: the part of its planned window after now, on the bay its workorder currently holds
  (else the booked one), and nothing once the workorder has completed. An appointment whose workorder
  has not taken a bay (not started, or history older than #2530) contributes its effective window as
  before (#2021).

On a date before today, in the location's zone, an appointment that never produced work (no workorder,
or one that never started) is not counted: a past date reports how busy the bay was, not what was
booked for it. Today and future dates still count it.

`carryOverIn` names a job by `appointmentId`, with `workorderId` when linked, or by `workorderId`
alone for a walk-in; `appointmentId` is no longer required on `CarryOverView`.

`ext_workorder` says only where a workorder is now, and pos-workorder clears that when the workorder
closes, which is why the history is replicated separately. Workorders that existed before #2530 get
their history on their next fact; the owner's `workorder.fact-backfill.requested` command re-emits
facts for started or completed workorders, so one run backfills the history the past needs.

### Workorder-to-appointment link (#2531)

`work_order_appointment_mapping` is not a replica but is written from the same feed.
`WorkorderEventsListener` is its only writer: when a `workorder.workorder.updated` fact names an
`appointmentId` (the appointment the workorder's estimate came from), the listener links the workorder
to that appointment in the transaction that writes the `ext_workorder` row, through
`WorkorderAppointmentLinkService`. The link is what the appointment status sync
(`WorkorderStatusEventService`), the appointment's actual start and finish, and the capacity read's
carry-over all look up.

- One row per workorder (`work_order_id` is the key). A workorder already linked keeps its link; every
  later fact repeats the same appointment.
- A fact with no `appointmentId` (a walk-in, or a producer older than the field) links nothing and
  removes nothing.
- An `appointmentId` this module does not hold is logged and skipped, and the replica row still lands.
- An appointment created with `sourceType: WORK_ORDER` does not get a row from that alone; only the
  workorder's own fact writes one.

### Not yet replicated is `503`, not `404` (#1994)

A replica row arrives by event, so an id with no row is either wrong or not here yet, and a `404`
(or a `400`/`422` worded as one) cannot tell the caller which. Where this module has nothing else
that proves the entity exists, a replica miss answers `503 Service Unavailable` with a
`Retry-After` header and a `<X>_REPLICATION_PENDING` code (`ApiError.referenceId` carries the
awaited id). Bulk ingest classifies the same exception as `REPLICATION_PENDING`. A row that is
present but in the wrong state keeps its own status.

| Code | Replica miss | Endpoint |
| --- | --- | --- |
| `CRM_REPLICATION_PENDING` | `ext_customer_party`, `ext_vehicle` | `POST /v1/appointments` |
| `LOCATION_REPLICATION_PENDING` | `ext_location` (opening search); `ext_bay` / `ext_mobile_unit` for a well-formed `resourceId` (submit, reschedule) | `GET /v1/schedules/openings`, `POST /v1/appointments`, `PUT /v1/appointments/{id}/reschedule` |
| `CATALOG_REPLICATION_PENDING` | `ext_catalog_service` | `GET /v1/schedules/openings` |
| `MECHANIC_REPLICATION_PENDING` | the mechanic projection built from staffing events | `POST /v1/appointments/{id}/assignments`; the internal mechanic availability query |

**Bay/mobile-unit topology is event-sourced, not read live.** A synchronous `RestClient` into
pos-location would work today but is a domain→domain call that ADR-0044 R1 forbids, and no standing
grant covers it — it would require a new recorded ADR-0044 exception on the pos-warranty precedent
(#786). pos-workorder made the same call the other way in #1656.

**Consequence, stated plainly:** pos-location publishes bay and mobile-unit facts as of #1668 —
`location.bay.updated` and `location.mobile-unit.updated` on `location.events.v1`, alongside the
`location.location.*` and `location.storage-location.updated` facts its `LocationFactPublisher`
already emitted. The fact contracts this module consumes are the canonical records in
`pos-domain-events` (`com.positivity.domainevents.location`); this module declares no mirror of
them.

**Retirement no longer deletes the replica row (DECISION-LOCATION-026, #2264).** `DELETE` on a bay
or mobile unit at pos-location now retires it (`status = RETIRED`) instead of hard-deleting, and the
fact it publishes is an ordinary `location.bay.updated` / `location.mobile-unit.updated` — never
`location.bay.deleted` / `location.mobile-unit.deleted`. `LocationEventsListener.applyBayUpdated` /
`applyMobileUnitUpdated` already derive `active` from the raw `status` (see below), so a retirement
is indistinguishable from any other non-`ACTIVE` status change: the row stays, `active` flips to
`false`, and an appointment or workorder that already names it keeps resolving to a name.
`applyBayDeleted` / `applyMobileUnitDeleted` still exist for a stray or replayed pre-#2264 delivery
of the retired `BayDeletedV1` / `MobileUnitDeletedV1` facts — pos-location no longer emits either —
and now handle one the same defensive way: if the replica row still exists, it is marked
`active = false` rather than removed. Neither method calls `deleteById` any more.

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

`ext_bay` also carries `display_order` (integer, nullable, **V13** — DECISION-LOCATION-026, #2264),
mirrored additively from `BayUpdatedV1.displayOrder`. `ExtBayReplicaRepository.findActiveByLocationOrdered`
sorts the dashboard's bay roster by `displayOrder` (nulls last), then name-then-id, matching the
order pos-location's own `GET .../bays` uses.

`ext_mobile_unit` gains `max_duty_class` (integer, nullable, `CHECK` 1–8, **V14** —
DECISION-LOCATION-029, #2267), the mobile-unit counterpart of `ext_bay.max_duty_class` (same GVWR
axis, CAP-325 D13). `applyMobileUnitUpdated` merges it with the same additive-field guard
`applyLocationUpdated` already uses (`mergeField`): absent from the raw fact means the publisher
predates the field, so the already-replicated value stands, never read as "unconstrained". #2269
checks it at placement. The optional identity fields DECISION-LOCATION-029 also adds to the owner's
`mobile_units` (`unitNumber`, `vin`, `licensePlate`, `plateRegion`) are display-only and are not
replicated here.

**Bay specialty map replica (#2261, DECISION-LOCATION-025).** `location.bay-specialty-map.updated`
carries a tenant's *whole* bay-type specialty map — one entry per `BayType`, never a delta — telling
"no bay claims this specialty" apart from "this is general work" (DECISION-SHOPMGMT-021 rule 4).
`LocationEventsListener` applies it as a full replace: every `ext_bay_type` / `ext_bay_specialty_map`
row for the tenant is deleted and one row per entry reinserted, in the same handler transaction as
the `processed_events` mark. Two tables, mirroring pos-location's own
`bay_specialty_operation` / `bay_specialty_map_version` split but folded into one master row per bay
type since the map is only ever replaced atomically:

- `ext_bay_type` — one row per `(tenant_id, bay_type)`: `accepts_general_work` and the
  `aggregate_version` the tenant's whole map was last applied at (every row from one emission
  carries the same version, which is what the `ReplicaVersionGuard` stale check reads back).
- `ext_bay_specialty_map` — one row per `(tenant_id, bay_type, operation_code)` a bay type is the
  only one able to perform (CAP-325 D14).

Both start empty and stay empty until the map arrives for a tenant; an absent row must never be read
as "not specialty" — it may just mean "not yet published" — so a consumer must keep behaving exactly
as today (no operation is treated as specialty) until the map fills. Wiring that read into
eligibility enforcement is a later story.

`ext_bay` separately gains `accepts_general_work boolean NOT NULL DEFAULT true`, mapped from
`BayUpdatedV1.acceptsGeneralWork` — additive within schema v1, so an absent or explicit-null field on
the fact means the publisher predates it and the already-replicated value (or the column default for
a brand-new row) is kept, the same additive-field guard style this listener already uses for
`gvwrClass` on the vehicle replica.

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
[`durion/docs/architecture/api/ERROR_ENVELOPE.md`](../../durion/docs/architecture/api/ERROR_ENVELOPE.md)); a malformed id is still `400` for every caller because Spring parses
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

## Reconciliation manifest replay requests (#2452)

A manifest listener that finds drift sends the owner's `outbox.replay-requested` command through
`OutboxReplayRequests`, which waits up to 30s for the broker's acknowledgement. A request that cannot
be handed to Kafka, that the broker rejects, or that is not acknowledged in time propagates to `KafkaErrorHandlingConfig`, which retries the manifest with backoff and then dead-letters it to `{topic}.dlq`.
Swallowing it would lose the repair for good, because each owner publishes a window's manifest once
and no later manifest covers that window again. Redelivery is safe: a manifest writes nothing, the
comparison only reads, and the replay command is keyed by window start. A manifest that does not parse
is still dropped.
