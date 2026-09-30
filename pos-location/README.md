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
- `PATCH /v1/locations/{locationId}/bays/{bayId}` — update a bay, including status transitions among
  `ACTIVE` / `OUT_OF_SERVICE` / `RETIRED` (DECISION-LOCATION-026, #2264)
- `DELETE /v1/locations/{locationId}/bays/{bayId}` — retire a bay (sets `status = RETIRED`; no hard
  delete, DECISION-LOCATION-026, #2264)
- `PATCH /v1/mobile-units/{id}` — update a mobile unit, including status transitions among `ACTIVE` /
  `OUT_OF_SERVICE` / `RETIRED` (#2264)
- `DELETE /v1/mobile-units/{id}` — retire a mobile unit (sets `status = RETIRED`; coverage rules are
  kept, no hard delete, #2264)
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
| `OUT_OF_SERVICE_REASON_REQUIRED` | 422 | A bay or mobile unit's resulting status is `OUT_OF_SERVICE` without `outOfServiceReason`, or `outOfServiceReason` is `OTHER` without `outOfServiceNote` (DECISION-LOCATION-026, #2264) |
| `MOBILE_UNIT_NAME_TAKEN` | 409 | Another mobile unit at the same base location has the name (ignoring case), **including a retired unit's name** (#2264) |
| `MOBILE_UNIT_IDENTITY_TAKEN` | 409 | Another mobile unit in the tenant already has this `unitNumber`, `vin`, or `licensePlate`+`plateRegion` pair (DECISION-LOCATION-029, #2267); `fieldErrors` names the field |
| `BAY_NAME_TAKEN` | 409 | Another bay at the same location has the name, **including a retired bay's name** (#2264) |
| `SERVICE_AREA_NAME_TAKEN` | 409 | Another service area in the tenant has the name, on create or when `PATCH` renames an area (#2256) |
| `SERVICE_AREA_CONFLICT` | 409 | A write to a service area (or its postal codes) hit a stored-row conflict other than the name (fallback, #2256) |
| `TRAVEL_BUFFER_POLICY_NAME_TAKEN` | 409 | Another travel buffer policy has the name |
| `TRAVEL_BUFFER_POLICY_CONFLICT` | 409 | A write to a travel buffer policy hit a stored-row conflict other than the name (fallback, #2256) |
| `OPTIMISTIC_LOCK_FAILED` | 409 | A concurrent update to the same bay or mobile unit won the version race |
| `NOT_FOUND` | 404 | The resource addressed by the path does not exist |

A `DuplicateResourceException` answers its own code (`*_NAME_TAKEN`, or the `*_CONFLICT` fallback for
any other constraint), not the generic `CONFLICT` (#2252). Service area and travel buffer policy writes
flush inside the service (`saveAndFlush`), so a duplicate name is mapped to that code rather than
surfacing at commit as a 500; the name code is chosen only for the `(tenant_id, name)` unique
constraint (`service_areas_name_key`, `travel_buffer_policies_name_key`). A travel buffer policy's
name is fixed at create by design. `PATCH /v1/service-areas/{id}` reads `name` (non-blank text),
`description` (text, or `null` to clear) and `active` (a JSON boolean only); any other type is a
`VALIDATION_ERROR` naming the field. A bay's `maxDutyClass` on `PATCH` follows the same absent-versus-null
rule: omit it to keep the limit, send `null` to clear it, or send 1–8 to set it (#2251). A mobile unit's name is unique at its base location ignoring case, held in the database by
`uq_mobile_unit_base_location_lower_name` (V6) so concurrent writes cannot both commit; a bay's name
is unique per location by `uq_bays_location_normalized_name`. Neither uniqueness check excludes
`RETIRED` rows, so a retired resource's name stays reserved and reactivation can never collide
(DECISION-LOCATION-026 rule 3). The `baseLocationId` filter on `GET /v1/mobile-units` is
location-scope gated like `listBays` (`location-scope.yaml`). Bay and mobile unit `status` is
`ACTIVE`, `OUT_OF_SERVICE` or `RETIRED` (a `CHECK` on both tables since **V8**, DECISION-LOCATION-026,
#2264 — mobile units previously also accepted `INACTIVE`, retired with no compatibility shim), and
travel buffer policy `bufferType` is `FIXED_MINUTES` or `DISTANCE_TIER` (also a `CHECK`, V11, #2266 —
supersedes the V6 `CHECK`; see "Distance units and travel buffer policy types" below).

## Mobile unit duty ceiling and identity (DECISION-LOCATION-029, #2267)

A mobile unit carries an optional `maxDutyClass` (1–8), the same GVWR-class-ceiling axis a bay's
`maxDutyClass` uses (CAP-325 D13, V8) — null means unconstrained. It also carries four optional,
display-only identity fields: `unitNumber`, `vin`, `licensePlate` and `plateRegion`. None of the
five are read by scheduling or eligibility; there is no equipment list, usual crew or hours on a
mobile unit (spec D14.2 — crew is People's, hours follow the unit's base location per
DECISION-SHOPMGMT-023).

- `maxDutyClass` outside 1–8 is refused 400 `VALIDATION_ERROR`.
- `vin`, when given, is normalized to upper case and must be exactly 17 characters, never
  containing `I`, `O` or `Q` (ISO 3779); otherwise 400 `VALIDATION_ERROR`.
- `plateRegion`, when given, is normalized to upper case and must be an ISO 3166-2 code (for
  example `US-NC`); otherwise 400 `VALIDATION_ERROR`.
- `unitNumber`, `vin`, and the `licensePlate`+`plateRegion` pair are each unique per tenant while
  set, held in the database by partial unique indexes (`uq_mobile_units_tenant_unit_number`,
  `uq_mobile_units_tenant_vin`, `uq_mobile_units_tenant_license_plate`, migration **V10**) so
  concurrent writes cannot both commit; a duplicate is refused 409 `MOBILE_UNIT_IDENTITY_TAKEN`
  with `fieldErrors` naming the field. `licensePlate` and `plateRegion` are checked together only
  once both are set — a plate recorded without its region is not a duplicate of anything.
- A `PATCH` clears any of the five by sending `null`; an absent key leaves it unchanged.
- `MobileUnitUpdatedV1` carries all five, additively (schema version 4).

## Distance units and travel buffer policy types (DECISION-LOCATION-028, DECISION-LOCATION-015, #2266)

Every distance in a request or response is an explicit `{ value, unit }` object, `unit` one of `KM`
or `MI`; a bare number is refused (400 `VALIDATION_ERROR`, `fieldErrors` naming the field, and naming
`<field>.unit` specifically for a missing or unknown unit). Storage is always canonical kilometres,
rounded half-up to 2 decimals; conversion is exact (1 mi = 1.609344 km) and happens at the API edge
(`DistanceUnits`), never in the database.

- **Locations** (`Location.distanceUnit`, `location.distance_unit varchar(2) NOT NULL DEFAULT 'KM'
  CHECK (... IN ('KM','MI'))`, migration V11): the unit a location's own forms show and accept.
  `createLocation`/`updateLocation` default an omitted `distanceUnit` to `KM`; `patchLocation` leaves
  it unchanged when omitted. Alpha's US locations are seeded `MI`
  (`scripts/fixtures/seed/alpha/location/locations.csv`, carried through
  `pos-bulk-loader`'s `LocationRecord`/`LocationLoaderStrategy` into the `LOCATION` bulk-ingest
  payload) — a bulk-loader `distanceUnit` column left blank still defaults to `KM` at the ingest
  endpoint.
- **Mobile unit coverage rules** (`CoverageRuleRequest`/`CoverageRuleResponse.maxDistance`,
  `mobile_unit_coverage_rules.max_distance_km numeric(10,2)`, renamed from `max_distance` by V11): a
  rule's `maxDistance` is stored in kilometres and shown back in the unit's base location's
  `distanceUnit` — the location, not the caller, decides the display unit. `DISTANCE_TIER` coverage
  (`ruleType`, `maxDistance`) is stored, **not yet evaluated**: nothing evaluates distance until
  geocoding exists, since neither locations nor customer addresses carry coordinates today.
- **Travel buffer policies** (`bufferType`): only `FIXED_MINUTES` (`bufferValue` a non-negative whole
  number of minutes, DECISION-LOCATION-015) and `DISTANCE_TIER` (stored, not yet evaluated — same
  reason as coverage) are accepted. The former `FLAT_MINUTES` is renamed to `FIXED_MINUTES`;
  `PERCENTAGE_OF_TRAVEL` and `DISTANCE_MULTIPLIER`, which no decision defines and which would need
  routed travel time no service in this platform provides, are removed outright (pre-production, no
  compatibility shim). Migration V11 converts existing rows: `FLAT_MINUTES` → `FIXED_MINUTES`
  in place, and a removed type → `FIXED_MINUTES` with a 0-minute `bufferValue`, one `RAISE WARNING`
  per row naming the policy so an operator can pick a real value.
- The alpha coverage-rule fixture (`scripts/fixtures/seed/alpha/location/mobile-unit-coverage-rules.csv`)
  carries its own explicit `unit` column (always `MI` today) rather than assuming a unit: `seed-alpha.py`
  sends `maxDistance` as `{"value": <maxDistance>, "unit": <unit>}` and refuses (skipping the whole
  unit, with a `WARN`) a row that names a distance with no unit.

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
| `location.bay.updated`         | `bayId`, `locationId`, `name`, `bayType`, `status`, `serviceCapabilityCodes`, `maxConcurrentVehicles`, `maxDutyClass`, `acceptsGeneralWork`, `outOfServiceReason`, `outOfServiceNote`, `expectedReturnAt`, `displayOrder`  | bay created or changed, including every status change — **retiring a bay is an `updated` fact with `status = RETIRED`, not a delete** (#2264) |
| `location.mobile-unit.updated` | `mobileUnitId`, `baseLocationId`, `name`, `status`, `serviceCapabilityCodes`, `outOfServiceReason`, `outOfServiceNote`, `expectedReturnAt`  | unit created or changed, including a re-base or a retirement (`status = RETIRED`) |
| `location.bay-specialty-map.updated` | `tenantId`, `entries[]` (`bayType`, `operationCodes[]`, `acceptsGeneralWork`), `aggregateVersion` | a tenant's bay specialty map changes, and once per active tenant at startup |

`location.bay.deleted` (`BayDeletedV1`) and `location.mobile-unit.deleted` (`MobileUnitDeletedV1`)
are **no longer emitted** (DECISION-LOCATION-026, #2264): `DELETE` retires instead of hard-deleting,
so there is no tombstone. The record classes stay in `pos-domain-events` and consumers still handle
one defensively — a stray or replayed pre-#2264 delivery marks the replica row inactive rather than
removing it — but pos-location's own publishers no longer call them.

Records live in `pos-domain-events` (`com.positivity.domainevents.location`). Consumers —
pos-workorder's dispatch board and pos-shop-manager's unit roster — hold `ext_bay` /
`ext_mobile_unit` replicas fed only by these facts.

**`status` is the raw lifecycle string, never a derived `active` boolean.** Both `BayEntity.status`
and `MobileUnitEntity.status` are `ACTIVE` | `OUT_OF_SERVICE` | `RETIRED` (DECISION-LOCATION-026,
#2264 — mobile units previously also wrote `INACTIVE`; a **V8** migration moved every `INACTIVE` row
to `OUT_OF_SERVICE` with reason `OTHER` and note `'migrated from INACTIVE'`, with no compatibility
shim for the retired value). Consumers derive activeness themselves with an allow-list on `ACTIVE`,
so an unrecognised status, `OUT_OF_SERVICE` and `RETIRED` alike, reads as inactive rather than as an
error. Every status change, including retirement, travels on the `updated` fact — the replica keeps
the row and flips it inactive; nothing ever removes it any more.

A mobile unit with **no base location** publishes nothing. A base site is optional on that
aggregate, so a unit without one is a legitimate owner-side state rather than malformed data — but
pos-workorder rejects a site-less fact and counts it on `replica.payload.rejected`, the metric that
exists to detect producer contract drift, and pos-shop-manager would store a row its roster query
can never return. Such a unit cannot be dispatched from anywhere, so withholding the fact loses
nothing; assigning a base site publishes an ordinary update. A bay cannot hit this case —
`bays.location_id` is `NOT NULL`.

`DELETE` retires both a bay and a mobile unit (DECISION-LOCATION-026, #2264): the row stays,
`status` becomes `RETIRED`, and nothing is hard-deleted — there is no longer a distinction between
"created in error" and "standing down a real one". Retiring a mobile unit keeps its coverage rules;
they simply stop matching because the unit is no longer active, the same way an inactive service
area's rules are kept but stop matching (DECISION-LOCATION-027). Neither delete performs a usage
check, so callers must confirm the resource is not referenced by scheduled work first (an open
workorder stays on a resource that leaves service, issue #2001).

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
guard is sound (#1486). Neither aggregate publishes a tombstone any more (#2264) — retirement is an
ordinary `updated` fact at the row's own version, the same as any other change.

## Bay and mobile-unit lifecycle (DECISION-LOCATION-026, #2264)

Bays and mobile units share one status set — `ACTIVE`, `OUT_OF_SERVICE`, `RETIRED` — enforced by a
database `CHECK` on both tables (**V8**). Statuses had drifted before this: bays had `ACTIVE` /
`OUT_OF_SERVICE` with no `CHECK`, and mobile units had `ACTIVE` / `INACTIVE` (V6). `INACTIVE` is
retired outright, pre-production, with no compatibility shim: **V8** moves every mobile unit at
`INACTIVE` to `OUT_OF_SERVICE` with `outOfServiceReason = OTHER` and `outOfServiceNote = 'migrated
from INACTIVE'`.

- **`DELETE` retires** (`BayServiceImpl.deleteBay`, `MobileUnitServiceImpl.deleteMobileUnit`): the
  row and its name stay, `status` becomes `RETIRED`, and the published fact is an ordinary `updated`,
  never a tombstone. Retrying a delete on an already-`RETIRED` resource succeeds again rather than
  erroring.
- **`RETIRED` is reversible** (`PATCH` back to `ACTIVE` or `OUT_OF_SERVICE`) and carries **no error
  code of its own**. Placing work on, or booking, a retired resource returns the existing 422
  `SERVICE_POSITION_INACTIVE` (issue #2001), the same as any other non-active status.
- **Retired names stay reserved.** The name-uniqueness checks (`existsByLocationIdAndNameIgnoreCase`
  for bays, `existsByBaseLocationIdAndNameIgnoreCase` for mobile units) never filter by status, so
  creating or renaming to a retired resource's name still returns 409 `BAY_NAME_TAKEN` /
  `MOBILE_UNIT_NAME_TAKEN` — reactivation therefore never clashes.
- **Going `OUT_OF_SERVICE` requires a reason** — `outOfServiceReason` is one of `EQUIPMENT_FAILURE`,
  `SCHEDULED_MAINTENANCE`, `INSPECTION`, `SAFETY_HOLD`, `FACILITY_ISSUE`, `OTHER`
  (`com.positivity.location.internal.enums.OutOfServiceReason`), enforced by
  `LifecycleStatusSupport.requireReasonWhenOutOfService` for both bays and mobile units. Missing it
  is 422 `OUT_OF_SERVICE_REASON_REQUIRED`, naming `outOfServiceReason`; `OTHER` without a non-blank
  `outOfServiceNote` (≤255 chars) is the same code, naming `outOfServiceNote`. `expectedReturnAt`
  (timestamptz) is optional and purely advisory — its `@Schema` says "not used by scheduling", and
  nothing here reads it. **All three clear (become `null`) the moment the resource returns to
  `ACTIVE`**, regardless of what else the same request sends.
- **A mobile unit created without a `status`** now defaults to `OUT_OF_SERVICE` with the
  system-supplied reason `OTHER` (formerly it defaulted to the retired `INACTIVE`, which needed no
  reason) — a unit staged before its travel buffer policy, capabilities and coverage rules exist. A
  caller that also sends its own `outOfServiceReason` on that same status-less create keeps that
  reason instead of the default.
- **Bays gain `displayOrder`** (integer, nullable): `GET .../bays` sorts by `displayOrder` (nulls
  last), then `name`, imposed by `BayServiceImpl.listBays` regardless of the caller's `Pageable`.
- **Default lists hide `RETIRED`.** `GET .../bays` and `GET /v1/mobile-units` exclude `RETIRED` when
  no `status` filter is given (`findByLocationIdAndStatusNot` / `findByBaseLocation_IdAndStatusNot` /
  `findByStatusNot`); naming `status=RETIRED` explicitly still returns them
  (DECISION-LOCATION-008/026).

## Consumers no longer delete replica rows (#2264)

pos-shop-manager and pos-workorder's `LocationEventsListener.applyBayDeleted` /
`applyMobileUnitDeleted` used to call `deleteById` on `location.bay.deleted` /
`location.mobile-unit.deleted`. Since pos-location no longer emits either fact, both handlers now
treat a stray or replayed delivery defensively: if the replica row still exists, they mark it
`active = false` instead of removing it, the same outcome a retirement's `updated` fact already
produces through the ordinary `isActiveStatus` derivation. A retirement itself never reaches these
methods at all — it is an `updated` fact like any other status change.

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
