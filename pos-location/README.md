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
- `GET /v1/locations/{storageLocationId}` — retrieve a storage location
- `POST /v1/locations/{siteId}/storage-locations` — create a storage location
- `PATCH /v1/locations/{siteId}/storage-locations/{storageLocationId}` — patch a storage location
- `GET /v1/mobile-units:eligible` — eligible mobile units for scheduling

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
| `location.bay.deleted`         | `bayId`                                             | bay hard-deleted |
| `location.mobile-unit.updated` | `mobileUnitId`, `baseLocationId`, `name`, `status`  | unit created or changed, including a re-base |
| `location.mobile-unit.deleted` | `mobileUnitId`                                      | unit hard-deleted |

Records live in `pos-domain-events` (`com.positivity.domainevents.location`). Consumers —
pos-workorder's dispatch board and pos-shop-manager's unit roster — hold `ext_bay` /
`ext_mobile_unit` replicas fed only by these facts.

**`status` is the raw lifecycle string, never a derived `active` boolean.** `BayEntity.status` is
`ACTIVE` | `OUT_OF_SERVICE`; `MobileUnitEntity.status` is written only as `ACTIVE` | `INACTIVE`.
Consumers derive activeness themselves with an allow-list on `ACTIVE`, so an unrecognised status
reads as inactive rather than as an error. Taking a unit out of service is a status change on the
`updated` fact — the replica keeps the row and flips it inactive; only a `deleted` fact removes it.

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

### Backfilling existing bays and mobile units

`location.outbox.replay-requested` **cannot** seed these replicas: it re-queues rows already in
`event_outbox`, and every bay and mobile unit that existed before #1668 has no outbox history. A
forward-only stream would leave those units permanently invisible.

Use the regenerate-from-state command on `location.commands.v1` instead:

```json
{"commandType": "location.fact-backfill.requested", "payload": {"aggregate": "all"}}
```

`payload.aggregate` accepts `bay`, `mobile-unit`, or `all` (the default when omitted); an
unrecognised value backfills nothing rather than everything. The run pages through the owner's
tables (`pos.location.fact-backfill.page-size`, default 500), one transaction per page, and is
idempotent — a replica applies an equal version and skips only a strictly-greater one, so re-running
repairs a stale replica without duplicating rows.

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |
| `pos.location.fact-backfill.page-size` | `500` | Rows per transaction when backfilling bay/mobile-unit facts |

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-bulk-ingest-lib` — bulk-ingest base controller

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-location -am spring-boot:run
```
