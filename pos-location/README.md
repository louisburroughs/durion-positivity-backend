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

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |

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
