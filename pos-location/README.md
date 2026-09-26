# pos-location

Location hierarchy and physical space management service for the Durion Positivity ETSMS platform. Manages the tree of service locations, bays, storage locations, mobile units, service areas, rosters, and travel buffer policies.

## Responsibilities

- Manage the location hierarchy (parent/child relationships between service sites)
- Create and configure service bays and mobile unit bays
- Maintain storage locations within each site, including what each one is fit to hold
- Transfer inventory between storage locations atomically
- Manage location rosters (which staff are assigned to a location)
- Define service areas and their coverage rules
- Configure site defaults (tax jurisdiction, currency, operating hours)
- Enforce travel buffer policies for mobile unit scheduling

## Key Classes

- `LocationService` — location CRUD and hierarchy traversal
- `BayService` — bay lifecycle for fixed and mobile bays
- `StorageLocationService` — bin-level storage location management
- `StorageLocationInventoryTransferService` — atomic transfer of stock between bins
- `LocationRosterService` — staff roster management per location
- `ServiceAreaService` — service area and coverage rule management
- `TravelBufferPolicyService` — mobile unit travel buffer configuration

## API Endpoints

- `GET /v1/locations/{locationId}` — retrieve a location
- `GET /v1/locations/{locationId}/children` — child locations
- `GET /v1/locations/{locationId}/validation` — validate location configuration
- `DELETE /v1/locations/{locationId}` — deactivate a location
- `GET /v1/locations/roster` — current location roster
- `GET /v1/locations/{id}/coverage-rules` — service area coverage rules
- `GET /v1/bays/{bayId}` — retrieve a bay
- `POST /v1/locations/{locationId}/bays` — add a bay to a location
- `DELETE /v1/locations/{locationId}/bays/{bayId}` — hard-delete a bay (#1668)
- `DELETE /v1/mobile-units/{id}` — hard-delete a mobile unit and its coverage rules (#1668)
- `GET /v1/locations/{storageLocationId}` — retrieve a storage location
- `POST /v1/locations/{siteId}/storage-locations` — create a storage location
- `PATCH /v1/locations/{siteId}/storage-locations/{storageLocationId}` — patch a storage location
- `GET /v1/mobile-units:eligible?baseLocationId=&postalCode=&countryCode=&at=&operationCodes=` —
  eligible mobile units for scheduling, scoped to `baseLocationId` (required, #2265)
- `GET /v1/mobile-units?baseLocationId=&status=&include=coverageRules` — one shop's units (and, with
  `include=coverageRules`, each unit's rules in the same response); every filter is optional (#2253)
- `PUT /v1/mobile-units/{id}/coverage-rules` — replace a unit's coverage, validated as create is (#2248)

## Error codes

Every non-2xx answer is the platform `ApiError` envelope
([`ERROR_ENVELOPE.md`](../../durion/docs/architecture/api/ERROR_ENVELOPE.md)). A refusal caused by one
request field also carries `fieldErrors[0].field` naming it (`name`, `baseLocationId`,
`coverageRules[1].ruleType`, `rules[0].serviceAreaId`, ...). Codes specific to this module:

| Code | Status | When |
| --- | --- | --- |
| `VALIDATION_ERROR` | 400 | A malformed or out-of-range value; `fieldErrors` names it |
| `LOCATION_NOT_FOUND` | 422 | A mobile unit's `baseLocationId` names no location |
| `TRAVEL_BUFFER_POLICY_NOT_FOUND` | 422 | A mobile unit's `travelBufferPolicyId` names no policy |
| `SERVICE_AREA_NOT_FOUND` | 422 | A coverage rule's `serviceAreaId` names no service area |
| `SERVICE_AREA_INACTIVE` | 422 | A coverage rule's `serviceAreaId` names a service area whose `active` is `false` (#2265) |
| `UNPROCESSABLE_CONTENT` | 422 | An ACTIVE mobile unit without a travel buffer policy, capabilities and coverage rules, or an unknown capability code |
| `MOBILE_UNIT_NAME_TAKEN` | 409 | Another mobile unit at the same base location has the name (ignoring case) |
| `BAY_NAME_TAKEN` | 409 | Another bay at the same location has the name |
| `TRAVEL_BUFFER_POLICY_NAME_TAKEN` | 409 | Another travel buffer policy has the name |
| `OPTIMISTIC_LOCK_FAILED` | 409 | A concurrent update to the same mobile unit won the version race |
| `NOT_FOUND` | 404 | The resource addressed by the path does not exist |

A `DuplicateResourceException` answers its own code (`*_NAME_TAKEN`), not the generic `CONFLICT`
(#2252). A mobile unit's name is unique at its base location ignoring case, held in the database by
`uq_mobile_unit_base_location_lower_name` (V6) so concurrent writes cannot both commit. The
`baseLocationId` filter on `GET /v1/mobile-units` is location-scope gated like `listBays`
(`location-scope.yaml`). Mobile unit `status` is `ACTIVE` or `INACTIVE` only (V6 adds a `CHECK`), and travel
buffer policy `bufferType` is `FLAT_MINUTES`, `PERCENTAGE_OF_TRAVEL` or `DISTANCE_MULTIPLIER` (also a
`CHECK` since V6, #2249).

## Mobile unit coverage eligibility (DECISION-LOCATION-027, DECISION-SHOPMGMT-023, #2265)

`GET /v1/mobile-units:eligible` finds the ACTIVE mobile units eligible to cover a service address:

- `baseLocationId` is **required** (400 `VALIDATION_ERROR` with `fieldErrors` naming it when missing)
  and scopes the answer to units based there — a unit only takes work from its own base location
  (DECISION-SHOPMGMT-023 rule 1), matching `pos-workorder`'s same-site placement rule.
  `postalCode`, `countryCode` and `at` (an ISO-8601 instant) stay required as before.
- `operationCodes` is optional and repeatable/comma-separated, like `include` on `GET
  /v1/mobile-units`; when sent, a unit must claim **every** code listed against its
  `serviceCapabilityCodes` — unlike a `GENERAL_SERVICE` bay, a mobile unit has no general-work
  default.
- A coverage rule only matches when its service area's `active` is `true`. Retiring an area
  (`PATCH /v1/service-areas/{id}` with `active: false`) keeps every rule pointing at it — nothing is
  deleted — but none of them match again until the area is reactivated; deactivating an area never
  changes any unit's own `status`.
- Results are ordered by `priority` ascending, then mobile unit id, as **one** ranking across the
  named location's units — ties are broken deterministically rather than left to per-rule ordering.
- `mobile_unit_coverage_rules.validFrom`/`validTo` are UTC instants (DECISION-LOCATION-017), not
  calendar dates: `validFrom` is inclusive, `validTo` is exclusive, and both are compared against
  `at` directly. Migration V9 converted the previous `date` columns so an existing window reads
  unchanged: `validFrom` becomes that day's UTC midnight, and `validTo` becomes the **next** day's
  UTC midnight (the old inclusive end-of-day date now falls just inside the exclusive window).
  Creating or replacing a rule requires `validTo` to be strictly after `validFrom` when both are set.

## Location scope (ADR-0061, #1872)

A caller whose permission is location-scoped (gateway headers `X-Loc-Fin-Bits`, `X-Loc-Oth-Bits`,
`X-Loc-Scope`) is checked against the location a request names, on top of `@PreAuthorize`. Tokens
without the claims behave exactly as before. The per-operation decisions live in
[`location-scope.yaml`](location-scope.yaml), which CI reads.

- **Gated** (`403` with `ApiError.code = LOCATION_SCOPE_DENIED`, see [`durion/docs/architecture/api/ERROR_ENVELOPE.md`](../../durion/docs/architecture/api/ERROR_ENVELOPE.md)):
  `PUT|PATCH|DELETE /v1/locations/{locationId}` on `location:write` (a missing location still
  answers `404` first); every `/v1/locations/{locationId}/bays` operation on `location:bay:read` or
  `location:bay:manage`; `/v1/locations/{locationId}/defaults` on `location:write` / `location:read`.
  Bay routes now answer `400` for a `locationId` or `bayId` that is not a UUID instead of deriving an id.
- **Unscoped** reads of the tree itself: `getLocationById`, `validateLocation`, `listLocationChildren`,
  `listLocationDescendants`, `getLocationResponsiblePerson`. Hierarchy resolution and navigation need
  ancestors and siblings of nodes outside a caller's reach, and every module replicates this data anyway.

pos-location owns the tree, so it keeps no replica: `LocationHierarchyService` is the module's
`LocationAncestorResolver` and walks its own `location_parent` edges per dimension (`FINANCIAL`, and
`OTHER` = the seven remaining `ParentType`s) with the shared `LocationAncestry` closure, inclusive of
self and bounded by `MAX_DEPTH`. An unknown id answers empty sets, and the scope check then denies.

## Repair capability on locations (#1657)

`GET /v1/locations` returns `hasRepairCapability`, `activeBayCount` and `activeMobileUnitCount` on every
`LocationResponseDTO`, and `GET /v1/locations/roster` returns `hasRepairCapability` on every `LocationRef`
(the roster stays lightweight and carries no counts). Consumers must read these fields instead of fanning
out over `GET /v1/locations/{locationId}/bays` and `GET /v1/mobile-units` per location.

- `activeBayCount` counts bays owned by the location whose `status` is exactly `ACTIVE`; `OUT_OF_SERVICE`
  bays are excluded.
- `activeMobileUnitCount` counts mobile units whose `baseLocationId` is the location and whose `status` is
  exactly `ACTIVE`. The check is an allow-list, so any status value other than `ACTIVE` — including a value
  added later — is treated as non-operational.
- `hasRepairCapability` is `activeBayCount > 0 || activeMobileUnitCount > 0`.
- An inactive location (`active == false`) always reports `false` with both counts `0`, whatever bays or
  mobile units it has on record. Whether inactive locations appear in either list is unchanged.

Nothing is denormalized onto `location`: `LocationRepairCapabilityProjector` computes the projection per
request from two aggregate queries — one `GROUP BY` over `bays` and one over `mobile_units`, each scoped to
the whole batch of returned location ids — so a bay or mobile unit that was just created, restatused or
re-based shows up immediately, and list size never adds queries. There is deliberately no
`?capability=REPAIR` filter parameter; narrow on `hasRepairCapability` client-side.

## Storage-location putaway capability (#1514)

A storage location carries two orthogonal descriptions, and they are deliberately independent:

- `type` — where it sits in the site's physical topology (`FLOOR`, `SHELF`, `BIN`, `CAGE`, `TRUCK`).
  **Unchanged.**
- `storageCategoryCode` — what it is fit to *hold*: `TIRE_RACK`, `OIL_STORAGE`, `BATTERY_RACK`,
  `SMALL_PARTS_BIN`, `BULK_FLOOR`, `STAGING`, `QUARANTINE`, `GENERAL`. A tire rack and a bulk pallet
  area are both `FLOOR` topologically, but only one of them should receive tires, so putaway needed a
  capability rather than a parallel type hierarchy.

Alongside it, `hazardContainment` (boolean) and `allowNewProduct` (`MIXED`, `SAME_PRODUCT_ONLY`,
`EMPTY_ONLY`). All three are accepted on create and PATCH, returned on the read paths, and published
on the existing `StorageLocationUpdatedV1` fact (additive within schema v1, ADR-0044 — no new
synchronous call). pos-inventory replicates them and routes putaway on them.

`storage_category_code` (V8) stays **nullable** so a row that predates the capability needs no
backfill, and `StorageCategory.orDefault` resolves null to `GENERAL` on every read path *and before
publishing*. A consumer therefore never sees null for a location whose fact was published after V8,
and never has to reimplement the null-means-`GENERAL` rule. `GENERAL` is permissive: it accepts every
catalog category.

`STAGING` and `QUARANTINE` are putaway *sources*, not destinations — pos-inventory refuses putaway
into them outright.

`allowNewProduct` is currently **declarative only**: pos-location owns and publishes it and
pos-inventory replicates it, but no putaway check reads it yet. Set it truthfully anyway — it is the
model for Odoo's `allow_new_product` policy and the enforcement point is expected to consume it.

**Republishing an existing bin's capability**: the generic `location.outbox.replay-requested` command
re-queues already-serialized outbox rows, so it cannot carry a field those rows predate. Declaring
the capability with a PATCH is what publishes a fresh fact. See `docs/OPERATIONS_RUNBOOK.md` →
"Issue #1514: rehydrating the putaway replica columns".

## Published facts: bays and mobile units

pos-location is the system of record for bays and mobile units, and publishes their lifecycle on the
existing `location.events.v1` topic (issue #1668, ADR-0044 §6):

| Event type                     | Payload                                             | When |
| ------------------------------ | --------------------------------------------------- | ---- |
| `location.bay.updated`         | `bayId`, `locationId`, `name`, `bayType`, `status`  | bay created or changed, including a status change |
| `location.bay.deleted`         | `bayId`                                             | bay hard-deleted via `DELETE /v1/locations/{locationId}/bays/{bayId}` |
| `location.mobile-unit.updated` | `mobileUnitId`, `baseLocationId`, `name`, `status`  | unit created or changed, including a re-base |
| `location.mobile-unit.deleted` | `mobileUnitId`                                      | unit hard-deleted via `DELETE /v1/mobile-units/{id}` |
| `location.bay-specialty-map.updated` | `tenantId`, `entries[]` (`bayType`, `operationCodes[]`, `acceptsGeneralWork`), `aggregateVersion` | a tenant's bay specialty map changes, and once per active tenant at startup |

Records live in `pos-domain-events` (`com.positivity.domainevents.location`). Consumers —
pos-workorder's dispatch board and pos-shop-manager's unit roster — hold `ext_bay` /
`ext_mobile_unit` replicas fed only by these facts.

**`status` is the raw lifecycle string, never a derived `active` boolean.** `BayEntity.status` is
`ACTIVE` | `OUT_OF_SERVICE`; `MobileUnitEntity.status` is written only as `ACTIVE` | `INACTIVE`.
Consumers derive activeness themselves with an allow-list on `ACTIVE`, so an unrecognised status
reads as inactive rather than as an error. Taking a unit out of service is a status change on the
`updated` fact — the replica keeps the row and flips it inactive; only a `deleted` fact removes it.

A mobile unit with **no base location** publishes nothing. A base site is optional on that
aggregate, so a unit without one is a legitimate owner-side state rather than malformed data — but
pos-workorder rejects a site-less fact and counts it on `replica.payload.rejected`, the metric that
exists to detect producer contract drift, and pos-shop-manager would store a row its roster query
can never return. Such a unit cannot be dispatched from anywhere, so withholding the fact loses
nothing; assigning a base site publishes an ordinary update. A bay cannot hit this case —
`bays.location_id` is `NOT NULL`.

Deletion is a hard delete for a unit created in error, not the way to retire a real one: use PATCH
with `OUT_OF_SERVICE` / `INACTIVE` for that. Deleting a mobile unit also removes its coverage rules
(`mobile_unit_coverage_rules` holds a plain FK with no cascade, so they are cleared first) and its
capability assignments. Neither delete performs a usage check, so callers must confirm the resource
is not referenced by scheduled work first.

**The site scope rides every `updated` emission**, not only the mutation that changed it, because
consumers rebuild the whole replica row from the payload. Note the deliberate asymmetry: a bay names
`locationId`, a mobile unit names `baseLocationId` (mirroring the owner's own columns), and neither
is `siteId` — the name the sibling `StorageLocationUpdatedV1` fact uses. pos-workorder rejects a
payload with no site scope rather than writing a row its roster query could never return.

A **re-based** mobile unit travels on an ordinary `updated` fact naming the new site; because
consumers scope rosters by that column, the unit leaves the old site's roster and joins the new one.
A re-base is never expressed as `deleted` + `updated`: the tombstone path is an unguarded delete, so
an out-of-order pair could drop or resurrect the row.

`bays` and `mobile_units` each gained a `version` column in **V9**, seeded to 0. It backs the
envelope's `aggregateVersion`, which strictly advances per committed mutation so a consumer's stale
guard is sound (#1486). Tombstones publish at `version + 1` — one past every fact the aggregate has
published — because consumers delete without consulting a version.

### Bay specialty map, published per tenant (DECISION-LOCATION-025, CAP-325 D14/D14.1/D14.3)

`bay_specialty_operation` (V4, seeded by `R__seed_location_2_bay_specialty.sql` under both the
alpha default tenant `...0001` and the platform tenant `...0000` — the provisioning template
`BaySpecialtyMapProvisioningService` copies into every newly created tenant) is the only source for
whether a catalog `operationCode` is *specialty* — a bay's own
`serviceCapabilityCodes` (on `BayUpdatedV1`) say only what that one bay claims. Deriving "is this
specialty" from which bays happen to be active at a location silently turns missing equipment into
general work, so pos-location now publishes the map itself:

- **Tenant provisioning.** On `tenant.created` (consumed from `tenant.events.v1`, pattern:
  pos-security-service's `TenantEventsListener`), pos-location copies the platform tenant's map rows
  into the new tenant. **Idempotent**: a tenant that already has any map rows — a replayed
  `tenant.created`, or one provisioned before this listener existed — is left untouched; no copy, no
  fact, though the eventId is still recorded so a redelivery short-circuits immediately.
- **`location.bay-specialty-map.updated`** (`pos-domain-events`
  `com.positivity.domainevents.location.BaySpecialtyMapUpdatedV1`) publishes on the same
  `location.events.v1` topic through the existing outbox, keyed by `tenantId` (the envelope
  `aggregateId`). The payload carries the tenant's **full** map, one entry per `BayType` — including
  a type with no specialty rows, whose `operationCodes` is then empty — never a delta:
  `{tenantId, entries: [{bayType, operationCodes[], acceptsGeneralWork}], aggregateVersion}`.
  `aggregateVersion` is a dedicated per-tenant counter (`bay_specialty_map_version`, one row per
  tenant, since the map itself is several rows with no JPA `@Version` of its own): it strictly
  advances whenever the map actually changes for a tenant (today, only first-time provisioning), and
  a startup republish carries whatever version is already on record rather than bumping it — the
  same equal-version-applies rule as every other strictly-advancing fact
  (`ReplicaVersionGuard`), which is what lets the startup sweep repair a replica holding the right
  version but wrong rows.
- **Startup sweep.** A `BaySpecialtyMapStartupPublisher` `ApplicationRunner` republishes every
  active tenant's current map once at boot (`TenantIterator.forEachActiveTenant`), so a replica
  standing up for the first time, or one that missed live traffic, converges without a manual
  replay. A per-tenant failure is logged and never blocks startup.
- **`BayType.acceptsGeneralWork()`** (DECISION-LOCATION-025) is `false` only for `WASH_DETAIL`; every
  other type — specialty bays included — takes general work by default (ranked last, per D14). It
  rides both `location.bay-specialty-map.updated` (per type) and `BayUpdatedV1` (additive within
  schema version 1, per bay), replacing a `WASH_DETAIL`-by-name check in consumers.
- **Wash and detail services are never in the map** (DECISION-LOCATION-025 rule 5): they are
  ordinary catalog line items, so `WASH_DETAIL`'s `operationCodes` is always empty.

### Reading the scheduling fields back (#2139)

`LocationResponseDTO` carries `timezone`, `operatingHours` and `holidayClosures`, so every location
read (`GET /v1/locations`, `GET /v1/locations/{locationId}`, `GET /v1/locations:top-level`,
`GET /v1/locations/{locationId}/children`) and every write response (`POST`, `PUT`, `PATCH`) returns
the scheduling facts as stored. They were write-only until #2139: a caller could publish hours
through `patchLocation`, be refused a booking against them by pos-shop-manager, and have no way to
read back what the server kept or which zone it reads them in.

The zone is the load-bearing part. Hours are facility-local (DECISION-015), so hours published
without a `timezone` keep whichever zone the location already carries — `08:00` then means 08:00 to
the shop, not to the caller, and a booking sent as `09:00Z` is judged at that location's `04:00`.

`null` and `[]` stay different facts here exactly as they are on the event (see below): a `null`
`operatingHours` means hours were never published, which is also why the scheduling HOURS rules do
not fire on it, while `[]` means configured as closed every day. Stored JSON that cannot be read is
logged and answered as absent rather than failing the read.

### Operating hours and holiday closures on `location.location.updated` (#2023)

`LocationUpdatedV1` (also on `location.events.v1`) carries four additional fields so
pos-shop-manager can read a location's capacity window without a synchronous call to pos-location
(ADR-0044 §6): `operatingHours`, `holidayClosures`, `checkInBufferMinutes`, `cleanupBufferMinutes`.
`checkInBufferMinutes` / `cleanupBufferMinutes` are read straight off the entity; `operatingHours`
and `holidayClosures` are parsed from the canonical JSON `LocationServiceImpl` writes into the
`operating_hours` / `holiday_closures` columns.

`operatingHours.dayOfWeek` is canonicalized case-insensitively against `java.time.DayOfWeek` on the
way out, because the write path does not validate it today (#2020 F1) — `"MONDAY"`, `"Monday"` and
`"monday"` all persist as-is. **If any entry cannot be canonicalized** — malformed JSON, an
unparseable day name, or two entries that collide on the same day once canonicalized — the publisher
logs an ERROR naming the location and the offending value, and publishes `operatingHours: null` for
the whole list rather than a partial one: a partial list would let a consumer read a real, configured
day as `CLOSED`, which is worse than reporting the week as unknown. `holidayClosures` is parsed and
validated independently, so a bad entry in one column never nulls the other.

**`null` and `[]` are different facts, not interchangeable.** A `null` column means *not
configured*; an empty JSON array means *configured as closed every day* / *configured with no
closures*. The fact preserves that distinction rather than collapsing both to `null` or both to `[]`.

### Backfilling existing bays, mobile units and locations

`location.outbox.replay-requested` **cannot** seed these replicas: it re-queues rows already in
`event_outbox`, and every bay and mobile unit that existed before #1668 has no outbox history. A
forward-only stream would leave those units permanently invisible. A location has the opposite
problem for the same reason: `LocationUpdatedV1` gained `operatingHours`, `holidayClosures`,
`checkInBufferMinutes` and `cleanupBufferMinutes` (#2023), so a location that last published
*before* that change has only the old, frozen payload shape sitting in `event_outbox` — replaying
it would just re-send that stale shape, never the new fields.

Use the regenerate-from-state command on `location.commands.v1` instead:

```json
{"commandType": "location.fact-backfill.requested", "payload": {"aggregate": "all"}}
```

`payload.aggregate` accepts `bay`, `mobile-unit`, `location`, or `all` (the default when omitted);
an unrecognised value backfills nothing rather than everything. The run pages through the owner's
tables (`pos.location.fact-backfill.page-size`, default 500), one transaction per page, and is
idempotent — a replica applies an equal version and skips only a strictly-greater one, so re-running
repairs a stale replica without duplicating rows.

A run is **bounded** at `pos.location.fact-backfill.max-rows-per-run` (default 20000) and resumable.
It executes on the Kafka command-listener thread shared with `location.outbox.replay-requested`, so
an unbounded walk risks exceeding `max.poll.interval.ms`; an evicted consumer never commits its
offset, so the command would be redelivered and the whole backfill would restart in a loop. When a
run hits the bound it logs a WARN naming the cursor to resume from — re-send the command with
`payload.afterId` set to that value. Because a long backfill delays other modules' replay requests
on the same consumer group, prefer running it off-peak.

Paging is **keyset** (`id > afterId`), not offset: deleting any row below an offset shifts every
later row back one position, so an offset page would skip a surviving unit — precisely the
invisibility the backfill exists to repair. Each page takes a shared row lock, which is what keeps a
concurrent delete from resurrecting a replica row: outbox rows are drained in id order across
*committed* rows, so without the lock a backfill row inserted early but committed late could be
published after a tombstone that committed first, and the consumer — which deletes unconditionally
and so retains no version to guard with — would re-apply the older update and recreate the row
permanently.

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |
| `pos.location.fact-backfill.page-size` | `500` | Rows per transaction when backfilling bay/mobile-unit facts |
| `pos.location.fact-backfill.max-rows-per-run` | `20000` | Rows per backfill command before it stops and reports a resume cursor |

## Multitenancy (ADR-0062, WS1 pilot)

This module is the pilot for the ADR-0062 runtime: it depends on `pos-tenancy-common`, every
scoped entity extends `TenantScopedEntity`, and the two global tables (`event_outbox`,
`processed_events`, listed in `src/main/resources/db/tenancy-global-tables.txt`) carry
`@TenantGlobal`. The request tenant is bound by `TenantContextFilter` from `X-Tenant-Id`, the
Kafka tenant by `TenantRecordInterceptor` from the `tenantId` record header, and every connection
checkout binds `app.current_tenant` for row-level security. Until plan WS2b delivers the JWT `tid`
claim, `pos.tenancy.default-tenant-id` binds the alpha default tenant on every unbound path.

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`).

Platform-scoped schedulers (run with no tenant bound; touch only global tables):

| Job | Why |
| --- | --- |
| `OutboxPublisher.publishPending` | Drains `event_outbox`; each row's `tenant_id` becomes the record header |
| `ManifestPublisher.publishDueManifest` | Groups the window's `event_outbox` rows by `tenant_id` and publishes one manifest per tenant, stamped with that tenant; every active tenant of the registry gets one, zero-count when it published nothing |

Proof: `TenantIsolationIT` (tenant A's row is invisible to tenant B and to an unbound connection,
through the repository and through raw SQL) and `TenancySchemaConformanceIT` (every non-whitelisted
table has `tenant_id`, RLS enabled and forced, and the `tenant_isolation` policy; the pool is
`pos_app` with no bypass), both on Testcontainers Postgres (`./mvnw -pl pos-location verify`).

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-tenancy-common` — ADR-0062 tenant context, connection binding, Hibernate resolver, Kafka propagation
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-bulk-ingest-lib` — bulk-ingest base controller

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-location -am spring-boot:run
```
