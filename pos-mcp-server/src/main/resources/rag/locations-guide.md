# Locations Guide — Stores, Hours, Bays, Mobile Units and Location Reach

## Purpose

RAG id: `shop.locations`  
RAG scope: `shopmanager`  
Required permissions: `location:bay:read`, `shop:schedule:view`, `shop:technician:view`  
Audience: store managers, service advisors, dispatchers and administrators.  
This document is reference context only and grants no access; access is enforced by permission codes at request time.

This guide explains what a location is on the Durion Positivity platform: the store or site record, its opening hours
and holiday closures, the service bays and mobile units based there, which technicians work there, which location a
signed-in user acts in, and how a permission can be limited to some locations. It answers questions such as "What are
the hours at the Elm Street store?", "Which technicians work at this location?" or "Why can I not see the bays at the
north branch?".

Two services share the subject. `pos-location` is the system of record for the location itself (name, code, address,
timezone, hours, closures, bays, mobile units, service areas, travel buffers). `pos-shop-manager` turns a location into
a bookable shop (appointments, schedule, technician roster, dashboard). Staffing (who is assigned where) belongs to
`pos-people`, and the reach of a permission is decided by `pos-security-service` when a user signs in.

---

## What a location is

A business with several sites usually runs them as one operation: the outlets share a brand, central management and
standard practices (see Sources [1]). The platform models each site as a **location** record, and lets locations be
arranged in hierarchies so that reporting, accounting and permissions can roll up.

| Field | What it means |
| --- | --- |
| `name` | Display name. Unique, compared case-insensitively. |
| `code` | Unique, immutable business code chosen by the business (for example `MAIN-WS-001`). There is no generated format: ask for the exact code. |
| `status` / `active` | A location is retired by setting it `INACTIVE`. Reactivation is not offered by the partial-update operation. |
| `type` | A classification (a location type, by id or name). |
| Address fields | Street lines, city, state, postal code, country, mailing address, phone number. |
| `timezone` | IANA zone id (for example `America/Toronto`). Hours and closures are read in this zone. |
| `operatingHours` | One entry per day of the week, each with a local opening and closing time. |
| `holidayClosures` | Dated days on which the location is closed (for example `2026-12-25`). |
| `checkInBufferMinutes`, `cleanupBufferMinutes` | Scheduling buffers applied around bookings. |
| `distanceUnit` | `KM` (default) or `MI`: the unit this location's forms show and accept. Distances are stored in kilometres. |
| `responsiblePersonId` | The person responsible for the location. |

**Hierarchy.** A location can have parents of different types: `HOME_OFFICE`, `HEADQUARTERS`, `REGION`, `DISTRICT`,
`PHYSICAL`, `ORGANIZATIONAL`, `FINANCIAL` and `SHIPPING`. Each location has at most one parent of each type, and cycles
are refused per type (ADR-0016). The **top-level location** is the active root of the hierarchy (a parent of others and
a child of none); when no edges exist it is the oldest active location.

**Deleting versus retiring.** Deleting a location removes the row permanently and is meant only for a record created by
mistake; nothing checks whether other records still point at it. Retire a real site by setting it `INACTIVE`, which
keeps its history.

---

## Opening hours and holiday closures

The hours on a location are the facility's own hours, written in local time and interpreted in the location's
`timezone`. Scheduling converts a booking into that zone before checking it against the hours, so 08:00 means 08:00 at
the store, wherever the person asking is.

- To answer "What are the hours at the Elm Street store?", look the location up (by name, code or id) and read its
  `operatingHours` and `holidayClosures` together with its `timezone`.
- A day with no entry has no published hours.
- Hours are changed with a partial update of the location (`location:write`). An update that supplies hours without a
  timezone keeps the timezone the location already has. Entries must name each day of the week once, with the opening
  time before the closing time; an invalid timezone or hours entry is refused (422).

The technician roster in `pos-shop-manager` uses these hours as a **placeholder shift window**: every technician at the
location gets the same window for the day, marked `shiftSource: LOCATION_HOURS`. It is not a personal roster, and it
does not know about staggered shifts, part-time hours, overtime or time off. A dated holiday closure shows the day as
`CLOSED`; an unknown timezone or unreadable hours show `UNKNOWN`.

---

## Bays

A **bay** is a fixed service stall at a location. Each bay has:

- a `bayType`: `GENERAL_SERVICE`, `ALIGNMENT`, `TIRE_SERVICE`, `HEAVY_DUTY`, `INSPECTION` or `WASH_DETAIL` (every type
  except `WASH_DETAIL` also accepts general work);
- a capacity (`maxConcurrentVehicles`, at least 1), optional service capability codes (active catalog operation codes)
  and skill requirements, an optional duty ceiling (`maxDutyClass`, a GVWR class from 1 to 8) and a display order;
- a `status`: `ACTIVE`, `OUT_OF_SERVICE` or `RETIRED`.

An `OUT_OF_SERVICE` bay must carry a reason (`EQUIPMENT_FAILURE`, `SCHEDULED_MAINTENANCE`, `INSPECTION`,
`SAFETY_HOLD`, `FACILITY_ISSUE` or `OTHER`, with a note required for `OTHER`) and may carry an expected return time.
Returning a bay to `ACTIVE` clears the reason. Retiring a bay sets `RETIRED`; nothing is hard-deleted and the bay name
stays reserved at that location. A list of bays hides retired ones unless `RETIRED` is asked for.

| Action | Permission |
| --- | --- |
| List or read bays | `location:bay:read` |
| Create, change, take out of service or retire a bay | `location:bay:manage` |

---

## Mobile units, service areas and travel buffers

A **mobile unit** is a van or truck that serves customers away from the shop. It has a **base location**, a status
(`ACTIVE`, `OUT_OF_SERVICE` or `RETIRED`, with the same out-of-service reasons as bays), service capability codes, an
optional duty ceiling, and optional display-only identity fields (unit number, VIN, licence plate and plate region).

An `ACTIVE` mobile unit must have a travel buffer policy, at least one capability and at least one coverage rule.
Coverage rules say where the unit operates: a `SERVICE_AREA` rule points at a postal-code service area, and
`DISTANCE_TIER` rules step outward by maximum distance and end with one catch-all tier. Coverage is always replaced as a
whole set. Retiring a unit keeps its coverage rules, which stop matching because the unit is no longer active.

"Which mobile units can reach this address?" is answered by the eligibility search, which matches active units to a
service address through their coverage rules.

| Action | Permission |
| --- | --- |
| Read mobile units, coverage and eligibility | `location:mobile-unit:read` |
| Create, change, re-cover or retire a mobile unit | `location:mobile-unit:manage` |
| Read or manage postal-code service areas | `location:service-area:read` / `location:service-area:manage` |
| Read or manage travel buffer policies | `location:travel-buffer-policy:read` / `location:travel-buffer-policy:manage` |

**Repair capability.** The location list reports, for each location, how many `ACTIVE` bays and `ACTIVE` mobile units
it has and whether it can perform repairs at all (`hasRepairCapability`: at least one of either). An inactive location
always reports no capability.

---

## A location as a shop

A location becomes bookable once `pos-shop-manager` holds a **shop** record for it, keyed by the same location id and
carrying a name, an optional address and a scheduling timezone (written with `shop:schedule:edit`). The schedule view,
capacity and openings searches (`shop:schedule:view`) answer 404 for a location that has no shop record. Appointments,
bay and mechanic assignment and conflict overrides are covered by the shop management guide (`shop.management`).

---

## Which technicians work at a location

A technician works at a location when `pos-people` holds an **ACTIVE staffing assignment** for that person at that
location, with the role `TECHNICIAN` and effective dates that cover the day in question. `pos-shop-manager` keeps an
eventually consistent copy of those assignments together with the mechanic's identity and skills.

- **One location, one day:** the location technician roster (`shop:technician:view`) lists the technicians whose
  technician assignment at that location is effective on a date (today in the location's timezone by default), with
  their skills and the placeholder shift window taken from the location's opening hours
  (`shiftSource: LOCATION_HOURS`). It can be narrowed by status and skill code.
- **The whole business:** the mechanic roster (`shop:technician:view`) lists every mechanic projected from active
  technician assignments, across locations.
- **One person's assignments:** `pos-people` lists a person's assignments and their primary location
  (`people:employee:view`); see the employee guide (`people.employees`).

Both rosters trail `pos-people` by the event-propagation delay, so a technician assigned a moment ago may not appear yet.
The roster has no personal shift schedule and no time-off data: the platform does not model paid time off today.

---

## Which location a user acts in

A signed-in user's locations come from their staffing assignments, through the link between their user account and
their person record:

- **Primary location:** `GET /v1/people/me/primary-location` (`people:self:view`) returns the user's active primary
  assignment as of today. A user with no link or no primary assignment gets the top-level location, flagged
  `defaulted: true`.
- **All current locations:** `GET /v1/people/me/locations` (`people:self:view`) lists every assignment active today,
  primary first. It is what a location switcher shows. When the user's person link has not replicated yet the answer is
  503 `USER_LINK_REPLICATION_PENDING` with a retry delay.
- **Availability:** the availability list (`people:availability:view`) shows who is assigned to a location on a date;
  when no location is named it uses the caller's own location.

A person has exactly one primary assignment at a time. Making a new assignment primary ends the overlapping old primary,
and a person's first active assignment is always primary.

---

## Location-scoped permissions

Whether a permission reaches every location or only some is a property of the **role** that grants it, not of the
assignment and not of the employee (ADR-0061).

- A role whose `location_scope` is `ALL` grants its permissions everywhere. Roles created through the role API start as
  `ALL`.
- A role whose `location_scope` is `LOCATION` grants its permissions only at the locations the holder is assigned to in
  `pos-people`, and at every location below those along the role's hierarchy (`FINANCIAL`, or `OTHER` for the
  non-financial parent types).
- When a user holds the same permission through an `ALL` role and a `LOCATION` role, the broader grant wins.
- The reach is computed when the access token is issued. If a location-scoped permission has no assigned location to
  resolve to, it reaches nowhere: absence denies, it never widens to every location.

An endpoint that checks reach refuses a location outside it with **403 `LOCATION_SCOPE_DENIED`**. Examples: listing the
bays of a location with a location-scoped `location:bay:read`, updating a location with a location-scoped
`location:write`, reading a location's technician roster with a location-scoped `shop:technician:view`, or naming a
location in the availability list. Some lists return an empty result rather than a refusal; the availability list does
so when the caller's own location is outside their reach.

When a user cannot see a location's data, check three things in order: that the user holds the permission at all, that
the role granting it is `LOCATION`-scoped, and that the user has an active staffing assignment at that location (or
above it in the relevant hierarchy) today. A change that widens reach (a new assignment, a later end date) is picked up
at the next sign-in or token refresh; a change that narrows it (an assignment ended, moved to another location or cut
short) revokes the person's live tokens straight away, so they sign in again with the narrower reach.

---

## Permissions at a glance

| Area | Read | Change |
| --- | --- | --- |
| Location record, hours, closures, hierarchy | `location:read` | `location:write` |
| Bays | `location:bay:read` | `location:bay:manage` |
| Mobile units and coverage | `location:mobile-unit:read` | `location:mobile-unit:manage` |
| Service areas | `location:service-area:read` | `location:service-area:manage` |
| Travel buffer policies | `location:travel-buffer-policy:read` | `location:travel-buffer-policy:manage` |
| Shop record, schedule, openings | `shop:schedule:view` | `shop:schedule:edit` |
| Technician rosters | `shop:technician:view` | (from staffing assignments in `pos-people`) |
| Own primary location and locations | `people:self:view` | (from staffing assignments) |

Which roles hold these codes is tenant configuration: ask a security administrator, or see the role-permission matrix
(`security.role-permission-matrix`).

---

## Sources

Platform sources:

- `pos-location/src/main/java/com/positivity/location/internal/controller/LocationController.java`, `BayController.java`,
  `MobileUnitController.java`, `MobileUnitEligibilityController.java`, `ServiceAreaController.java`,
  `TravelBufferPolicyController.java`
- `pos-location/src/main/java/com/positivity/location/internal/entity/Location.java`, `ParentType.java`;
  `internal/enums/BayType.java`, `OutOfServiceReason.java`; `internal/dto/OperatingHoursRequest.java`,
  `HolidayClosureRequest.java`
- `pos-location/src/main/resources/permissions.yaml`
- `pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/controller/ShopController.java`,
  `TechnicianController.java`, `MechanicRosterController.java`, `ScheduleController.java`;
  `internal/enums/ShiftSource.java`; `internal/service/StaffingScheduleService.java`
- `pos-shop-manager/src/main/resources/permissions.yaml`
- `pos-people/src/main/java/com/positivity/people/internal/controller/PeopleAvailabilityController.java`,
  `StaffingAssignmentController.java`
- `pos-security-service/README.md` (Role location scope; Scope claims in the access token);
  `pos-security-service/src/main/java/com/positivity/securityservice/internal/enums/LocationScope.java`
- `durion/docs/adr/0016-location-entity-semantics.adr.md` (location hierarchy),
  `durion/docs/adr/0061-location-scope-authorization-ownership.adr.md` (location-scoped authorization)
- `durion/domains/location/.business-rules/AGENT_GUIDE.md` (domain boundaries)

External sources:

1. "Chain store", Wikipedia, Wikimedia Foundation. <https://en.wikipedia.org/wiki/Chain_store> (accessed 2026-10-02).
