# Service Time Estimate Sourcing Plan (and Parts-Fitment Sourcing Mirror)

**Status:** DRAFT for vetting — round 1
**Owner module:** pos-catalog (system of record per decision recorded on
[#1569](https://github.com/louisburroughs/durion-positivity-backend/issues/1569), 2026-08-29)
**Inputs:** [#1569](https://github.com/louisburroughs/durion-positivity-backend/issues/1569)
(work session / estimated service time gap analysis),
[#1573](https://github.com/louisburroughs/durion-positivity-backend/issues/1573)
(timekeeping boundary),
[#1575](https://github.com/louisburroughs/durion-positivity-backend/issues/1575)
(repair specifications and book-time data strategy).
**Location note:** parked in `pos-catalog/docs/` per module-plan convention
(`pos-accounting/docs/flyway-baseline-reset-plan.md`, `pos-people/docs/PLAN-726-*`). The
parts-fitment track (§9) may later split into `pos-vehicle-fitment/docs/` once vetted.

---

## 1. Objective and end state

pos-workorder needs a defensible **estimated service time** (book time / flat-rate time) for
every `LABOR` line, resolved per operation *and* per vehicle, with source and revision
attribution, so that:

- `EstimateItem.quantity` defaults from book time instead of being hand-typed;
- `WorkorderSummary.estimatedLaborHours` is populated (today it is declared and always null);
- estimate-vs-actual variance exists (`totalLaborHours` from `WorkorderLaborEntry` is already
  flowing — the estimate side is the missing operand);
- pos-shop-manager can eventually derive appointment duration from summed book time.

**End state (target):** a **multi-source strategy** — OEM warranty/service times and parts-
manufacturer installation times (e.g. Michelin tire operations, Tier 0/1 of #1575) as primary
sources where we have them, with **commercial aggregators (MOTOR / Mitchell 1 / ALLDATA) as the
licensed backstop** for everything else. Sources are resolved through a precedence engine; every
stored time carries provenance.

**Middle state:** a single licensed **aggregator** behind the provider abstraction is the sole
real source (Tier 2 of #1575, "the default paid integration").

**First state:** a **locally hosted mock provider service** that speaks the same normalized
provider contract, so the entire pipeline — SPI, ingestion, storage, transport to pos-workorder,
estimate defaulting, variance — is built and demonstrable end-to-end before any licensing spend.

The same first/middle/end shape applies to **parts-fitment sourcing** (§9): mock → aggregator
(ACES/PIES-style feed) → OEM/manufacturer feeds with aggregator backup. Both tracks share one
architecture (§3) so the second track is mostly repetition, not new design.

### Non-goals (adjacent, tracked separately)

- Labor **rate** modelling in pos-price (book time × rate; pos-price has no hourly rate today).
- Shop labor **matrix** (percentage adjustments by job class/condition — shop policy, not
  catalog master data; belongs with location/shop configuration).
- Appointment duration derivation in pos-shop-manager (consumer of this work, not part of it).
- Timekeeping. Per the owner's comment on #1573: *time entry is clock-in/clock-out and breaks;
  workorder time comes from service times, not from time entry.* Nothing in this plan reads or
  writes `work_session`, `time_entry`, or `TimekeepingEntry`. The only touchpoint is the
  variance report, which compares this plan's estimate total against the already-computed
  actual `totalLaborHours` in pos-workorder.

### Naming (prerequisite from #1569)

Both `work_session` tables are permanent clock tables with live writers, so this feature never
uses that name. Canonical terms used throughout, and to be used in code:

| Concept | Name in code/schema |
|---|---|
| One vehicle-specific published time for an operation | **labor time** (`labor_time`) |
| The catalog-side stored row with provenance | **service labor standard** (`service_labor_standard`) |
| The workorder-level aggregate | **estimated labor hours** (`estimatedLaborHours`, already declared) |
| The upstream feed abstraction | **labor time provider** (`LaborTimeProvider`) |

Unit is **decimal hours in tenths** (`numeric(5,1)`, 0.1 hr = 6 min) per industry convention —
never minutes, never seconds.

---

## 2. Domain rules the design must honor (from #1569 / #1575)

These are constraints, not features; each shapes the schema or the resolution logic below.

1. **A time belongs to a defined operation.** We need an operation taxonomy with stable Durion
   operation codes; vendor codes map onto ours, never the reverse (#1575 "normalize vendor data
   into Durion-defined repair-operation identifiers").
2. **A time is vehicle-specific.** Lookup is by year/make/model/submodel/engine (long-term:
   VIN → decode → key; ACES vehicle id where a vendor supplies it). A scalar column on
   `ServiceEntity` models nothing real.
3. **A time is a baseline, not the billed number.** Adjustments (corrosion, access, programming,
   calibration) are separately itemised lines — the model stores the guide baseline and the
   quote records both guide time and agreed time.
4. **Overlap is real.** A workorder total is not a naive sum of lines; the model must carry
   included/additional-operation and overlap relationships (aggregators publish these; the mock
   must simulate at least one case so the summation logic is honest from Phase 1).
5. **Diagnostic time is its own line** (0.5–2.0 hr blocks), never folded into a repair op.
6. **Guides disagree and have vintages.** Every stored time carries `source`, `source_revision`,
   and `published_at`; two sources may hold different times for the same (operation, vehicle)
   simultaneously — resolution picks one, storage keeps all.
7. **Warranty time ≠ retail time.** `time_type` distinguishes `RETAIL_FLAT_RATE`,
   `OEM_WARRANTY`, `MANUFACTURER_INSTALL`, `DURION_STANDARD` (Tier 0 owned ops).
8. **Licensing gates transport.** Whether a licensed time may be stored, replicated on a Kafka
   fact, or shown only at point of use is a *per-source contract term*. The architecture must
   support both "ingest and store" and "query-through, cache-bounded, never persist" per source
   (§5.4). This is why the licensing question precedes the transport build in sequencing.

---

## 3. Architecture: one sourcing pattern for both tracks

### 3.1 The pattern (mirrors pos-supplier, deliberately not pos-vehicle-reference-*)

The repo already contains two candidate patterns for third-party data:

- **pos-supplier stock inquiry** (ADR-0044 amendment 2026-08-10, ADR-0050): SPI port
  (`internal/spi/SupplierStockInquiryPort`), vendor adapters (`internal/adapter/michelins2s/`),
  sandbox base-url override per vendor profile (`SupplierProfileProperties.Sandbox`), typed
  degradation statuses instead of exceptions, granted interface in the module's `service/`
  package with `package-info.java` stating the grant, file-scoped ArchUnit exception for the
  callers. **Alive, consumed, enforced.**
- **pos-vehicle-reference-carapi / -nhtsa**: standalone read-through-cache microservices.
  **Dead**: consumed by no module, commented out of docker-compose, logic duplicated inline
  into pos-vehicle-fitment, path collision (`/v1/vehicle-fitment`) with the module they were
  meant to feed.

**Decision proposed:** adopt the pos-supplier shape. Sourcing SPIs and vendor adapters live
*inside the owning domain module* (pos-catalog for labor times, pos-vehicle-fitment for
fitment), not in per-vendor microservices. The reference-module experiment demonstrates the
failure mode of the alternative: an extra network hop and deployment unit with no owner, which
the consumer eventually inlines around. (§9.4 proposes what to do with the two dead modules.)

### 3.2 Component diagram (labor-time track)

```
                              ┌──────────────────────────────────────────────┐
 vendors                      │ pos-catalog                                  │
 ────────                     │                                              │
 pos-reference-mock ──HTTP──▶ │ internal/spi/LaborTimeProviderPort           │
 (Phase 1, local)             │   ├─ internal/adapter/mockguide/   (Ph. 1)   │
 Aggregator API/feed ─HTTP──▶ │   ├─ internal/adapter/<aggregator>/(Ph. 2)   │
 OEM portals/feeds ───HTTP──▶ │   ├─ internal/adapter/<oem>/       (Ph. 3)   │
 Manufacturer feeds ──HTTP──▶ │   └─ internal/adapter/<mfr>/       (Ph. 3)   │
                              │                                              │
                              │ internal/laborstandard/service/              │
                              │   LaborStandardIngestService  (bulk import)  │
                              │   LaborTimeResolutionService  (precedence)   │
                              │        │                                     │
                              │        ▼                                     │
                              │ service_labor_standard (+ xref, +import log) │
                              │        │                                     │
                              │ service/ (grant surface, ADR-0026 D1–D5)     │
                              │   ServiceLaborTimeService + model records    │
                              └───────┬──────────────────────────────────────┘
                                      │ REST edge (ADR-0044 file-scoped grant)
                                      ▼
                              pos-workorder internal/client/CatalogLaborTimeClient
                                      │
                                      ▼
                              EstimateItem defaulting → WorkorderServiceLine snapshot
                                      │
                              WorkorderSummary.estimatedLaborHours (overlap-aware sum)

  degraded path: CatalogServiceUpdatedV2 fact (vehicle-agnostic default hours only)
                 ──▶ pos-workorder ext_catalog_service replica (and pos-marketing, tolerant)
```

### 3.3 The SPI (Durion-normalized; vendors adapt to it)

Sketch — final signatures settled in Phase 1 review. Lives in
`com.positivity.catalog.internal.spi`; **not** a grant surface (providers are private plumbing).

```java
public interface LaborTimeProviderPort {

    /** Identity + capability declaration (drives resolution precedence + licensing mode). */
    LaborTimeProviderDescriptor descriptor();

    /** Live lookup: operations applicable to a vehicle, optionally filtered by search text. */
    List<ProviderOperation> findOperations(VehicleKey vehicle, @Nullable String search);

    /** Live lookup: one time for (vehicle, provider operation), with included/overlap info. */
    Optional<ProviderLaborTime> getLaborTime(VehicleKey vehicle, String providerOperationCode);

    /**
     * Batch mode for storage-licensed sources: stream a feed revision as chunks for the
     * chunked-manifest import (§5.3). Sources licensed query-only throw
     * UnsupportedOperationException and are used live-only.
     */
    ProviderFeedRevision openFeedRevision(@Nullable String sinceRevision);
}
```

Supporting records (in `internal/spi/model`):

- `VehicleKey(year, make, model, submodel, engineCode, acesVehicleId?)` — normalized, string
  fields matching pos-vehicle-fitment vocabulary (§4.3); `acesVehicleId` nullable until an
  ACES-licensed source exists.
- `ProviderLaborTime(providerOperationCode, hours, timeType, includedOperations[],
  overlapGroup?, sourceRevision, publishedAt, notes)`.
- `LaborTimeProviderDescriptor(sourceCode, displayName, licenseMode STORE|QUERY_ONLY,
  timeTypes[], defaultPrecedence)`.

Provider selection is config-driven (`pos.catalog.labor-guide.providers[...]`), one adapter per
`sourceCode`, mirroring `SupplierProfileProperties` including the **sandbox base-url override**
— that override is exactly how Phase 1 points every adapter at the mock.

### 3.4 Resolution and precedence (end-state behavior, stubbed simple in Phase 1)

`LaborTimeResolutionService.resolve(serviceId | operationCode, VehicleKey)`:

1. Query `service_labor_standard` for all stored rows matching (operation, vehicle-key match)
   — vehicle matching is exact-first, then widening (submodel → engine → model/year) with an
   explicit `match_grade` in the response so callers can show confidence.
2. Order candidates by per-`time_type` precedence policy (default:
   `MANUFACTURER_INSTALL` > `OEM_WARRANTY`(when quoting warranty work) > `RETAIL_FLAT_RATE`
   aggregator > `DURION_STANDARD` fallback; policy is data, not code — table
   `labor_time_source_policy`).
3. If no stored row and a `QUERY_ONLY` provider is configured: live call through the port,
   bounded cache (TTL from license terms), never persisted beyond cache.
4. Nothing found → typed miss (`NO_TIME_AVAILABLE`), never an exception — callers must degrade
   like `SupplierStockService` callers do (render without a default, writer types the hours).

Phase 1 ships steps 1 and 4 only; 2 is trivial with one source; 3 arrives with the first
query-only license.

---

## 4. pos-catalog data model

### 4.1 The blocking decision from #1569 scope item 1 — resolved here (for vetting)

> Either `ServiceEntity` gains a vehicle-keyed child table of times inside pos-catalog, or
> pos-catalog holds the vehicle-agnostic operation and a service-fitment analogue of
> `PartFitmentEntity` lives in pos-vehicle-fitment.

**Proposed: vehicle-keyed child table inside pos-catalog** (`service_labor_standard`).

Reasons:

- The decision on #1569 makes `ServiceEntity` the *system of record for estimated service
  time*; putting the times in another module immediately re-splits the record.
- Every quote resolution would otherwise be a cross-module join at request time
  (pos-workorder → pos-catalog → pos-vehicle-fitment), tripling the latency-critical path and
  requiring a second ADR-0044 edge.
- pos-vehicle-fitment's `PartFitmentEntity` is keyed by `partNumberId` (a `Long`, product-side
  legacy) — it is a *parts* applicability table, not a generic vehicle-applicability service.
- No cross-service FKs exist in this platform anyway; what pos-catalog borrows from
  pos-vehicle-fitment is the *vocabulary* (make/model/engine naming, §4.3), not rows.

What pos-vehicle-fitment contributes instead: the shared vehicle vocabulary and, in its own
track (§9), the same provider architecture for parts applicability. The rejected alternative
stays documented here so the vetting round can overturn it deliberately.

### 4.2 Schema (new Flyway migrations in pos-catalog)

`V16__service_operation_taxonomy.sql` (numbers indicative):

```sql
-- Durion-owned operation identity; ServiceEntity keeps UX naming, this adds taxonomy.
ALTER TABLE service ADD COLUMN operation_code varchar(64);        -- e.g. BRAKE-PAD-FRONT-R&R
ALTER TABLE service ADD COLUMN operation_category varchar(32);    -- REPAIR | DIAGNOSTIC | MAINTENANCE | TIRE_SERVICE
ALTER TABLE service ADD COLUMN default_labor_hours numeric(5,1);  -- vehicle-agnostic fallback ONLY (degraded mode + fact v2)
CREATE UNIQUE INDEX ux_service_operation_code ON service (operation_code) WHERE operation_code IS NOT NULL;

-- Vendor code ↔ Durion service mapping (one vendor op may map to one Durion op per source).
CREATE TABLE service_operation_xref (
    id                uuid PRIMARY KEY,
    service_id        uuid NOT NULL REFERENCES service (id),
    source_code       varchar(32)  NOT NULL,   -- MOCKGUIDE | <AGGREGATOR> | OEM_<MAKE> | MFR_<NAME> | DURION
    provider_op_code  varchar(128) NOT NULL,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    UNIQUE (source_code, provider_op_code)
);
```

`V17__service_labor_standard.sql`:

```sql
CREATE TABLE service_labor_standard (
    id                uuid PRIMARY KEY,                    -- UUID v7
    service_id        uuid NOT NULL REFERENCES service (id),
    -- vehicle key (denormalized strings, vocabulary from pos-vehicle-fitment; nullable = wildcard)
    vehicle_year      varchar(16),                         -- single year or range, matches PartFitmentEntity convention
    make              varchar(64),
    model             varchar(64),
    submodel          varchar(64),
    engine_code       varchar(64),
    aces_vehicle_id   bigint,                              -- when a licensed source supplies it
    -- the time
    labor_hours       numeric(5,1) NOT NULL,               -- decimal hours, tenths
    time_type         varchar(24)  NOT NULL,               -- RETAIL_FLAT_RATE | OEM_WARRANTY | MANUFACTURER_INSTALL | DURION_STANDARD
    -- relationships that make summation honest
    overlap_group     varchar(64),                         -- lines sharing a group share setup time
    included_op_codes text[],                              -- operations whose time is included in this one
    -- provenance (non-negotiable, #1569 "defensible on an invoice")
    source_code       varchar(32)  NOT NULL,
    source_revision   varchar(64)  NOT NULL,
    published_at      date,
    import_manifest_id uuid,                               -- ties row to its import (§5.3)
    superseded_at     timestamptz,                         -- append-preferred: new revision supersedes, not updates
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL
);
CREATE INDEX ix_sls_lookup ON service_labor_standard (service_id, make, model, vehicle_year) WHERE superseded_at IS NULL;
CREATE INDEX ix_sls_source ON service_labor_standard (source_code, source_revision);

CREATE TABLE labor_time_source_policy (
    id           uuid PRIMARY KEY,
    time_type    varchar(24) NOT NULL,
    source_code  varchar(32) NOT NULL,
    precedence   int NOT NULL,                             -- lower wins
    enabled      boolean NOT NULL DEFAULT true,
    UNIQUE (time_type, source_code)
);
```

`V18__labor_guide_import_log.sql` — clone of the supplier-price import bookkeeping
(`supplier_price_import` / `_chunk`, ADR-0053 shape): `labor_guide_import`
(`import_manifest_id` PK assigned by producer, `source_code`, `source_revision`,
`expected_chunk_count`, `expected_line_count`, `content_checksum`, `chunks_applied`,
`lines_applied`, `status APPLYING|COMPLETE|INCOMPLETE`, `completed_at`) and
`labor_guide_import_chunk` (`UNIQUE (import_manifest_id, chunk_sequence)`).

Design points to vet:

- **Append + supersede** rather than update-in-place: an invoice quoted against revision N must
  stay explainable after revision N+1 imports. Cheap with partial index on
  `superseded_at IS NULL`.
- `default_labor_hours` on `service` is deliberately present *and* deliberately second-class:
  it is what rides the Kafka fact (degraded/offline default) and what a single-scalar shop can
  author by hand; the resolution service always prefers `service_labor_standard` rows.
- `included_op_codes text[]` vs. a join table: array chosen for v1 (data arrives
  denormalized from guides; we never navigate it relationally). Revisit if overlap policy
  grows logic.

### 4.3 Vehicle vocabulary alignment

`VehicleKey` strings must be the same strings pos-vehicle-fitment stores (`Make.name`,
`Model.name`, `engineType`, `submodel`), else fitment-driven part picks and labor-time lookups
disagree about what vehicle is on the lift. Mechanism:

- Phase 1: by convention — the mock emits vocabulary copied from pos-vehicle-fitment's NHTSA
  cache shape; ingest normalizes case exactly like `VehicleFitmentServiceImpl.createFitment`
  (case-insensitive resolve).
- Phase 2+: pos-workorder resolves the vehicle once (VIN decode or picker backed by
  pos-vehicle-fitment's `/v1/vehicle-fitment` hierarchy endpoints) and passes the resolved
  `VehicleKey` to the labor-time edge — one vocabulary authority (pos-vehicle-fitment), zero
  new cross-module joins.
- ACES ids become the join key wherever both sides have them (fitment track §9 ingests them
  from the same aggregator family).

### 4.4 Authoring surface (manual writes stay possible forever)

Tier 0 of #1575 (Durion-owned tire/fleet operations, dealer-created ops) and one-off shop
corrections need a human write path, not just feeds:

- Extend `CatalogItemRequestDto` + `CatalogServiceImpl.copyOntoServiceEntity` with
  `operationCode`, `operationCategory`, `defaultLaborHours` (fixes the "copies name and two
  descriptions and nothing else" gap from #1569).
- New CRUD on `/v1/catalog-items/service/{id}/labor-standards` for `service_labor_standard`
  rows with `source_code = 'DURION'` (only DURION rows are hand-editable; imported rows are
  correctable only by supersession with an audit note).
- Permissions (registered code-first in `CatalogPermissionRegistry`):
  `catalog:labor_standard:view`, `catalog:labor_standard:manage`,
  `catalog:labor_standard:import`.
- `@EmitEvent` ids in `CatalogEventTypes` (threshold presets in parentheses):
  `CATALOG_LABOR_STANDARD_CREATE/UPDATE/SUPERSEDE` (`write`),
  `CATALOG_LABOR_STANDARD_SEARCH` (`search`), `CATALOG_LABOR_GUIDE_IMPORT` (`write`),
  `CATALOG_LABOR_TIME_RESOLVE` (`fastRead`).

---

## 5. Ingestion

### 5.1 Two license-shaped modes

| Mode | When | Mechanics |
|---|---|---|
| **STORE** | License permits persistence (typical for bulk feed contracts, all mock/Tier-0 data) | Chunked-manifest import (§5.3) into `service_labor_standard` |
| **QUERY_ONLY** | License forbids persistence (some OEM portals, per-lookup pricing) | Live call via `LaborTimeProviderPort`, TTL cache only, provenance shown at point of use |

The licensing review for each contracted source decides its mode **before** the adapter is
built (sequencing rule from #1569 scope item 3). The mock runs in STORE mode so Phase 1
exercises the full import path, plus a second mock source configured QUERY_ONLY so the live
path has coverage too.

### 5.2 Trigger

Import runs are operator-triggered (admin endpoint
`POST /v1/catalog/labor-guide-imports?sourceCode=...`, permission
`catalog:labor_standard:import`) and optionally scheduled per source. No lazy read-through
refresh for STORE sources — the pos-vehicle-fitment inline-NHTSA "refresh on read, delete-all
and refill" pattern is explicitly not copied (it couples request latency to a vendor and has
already produced an inverted-cache-check bug there; see §9.5 fix list).

### 5.3 Chunked-manifest import (clone of ADR-0053 supplier price import)

Reuse the proven shape, adapted from Kafka-consumer to adapter-pull (the data enters through
the SPI, not a topic — vendors are outside our event mesh):

1. `LaborStandardIngestService` asks the adapter for `openFeedRevision(sinceRevision)`;
   adapter returns manifest (`import_manifest_id` it assigns, `expected_chunk_count`,
   `expected_line_count`, `content_checksum`, `source_revision`) and a chunk stream.
2. Each chunk applies idempotently: chunk log row keyed
   `(import_manifest_id, chunk_sequence)` — re-delivery and resume are no-ops, mirroring
   `SupplierPriceCatalogEventsListener`'s two-guard scheme.
3. Lines upsert by natural key `(source_code, provider_op_code, vehicle key, time_type)`:
   unchanged → skip; changed → supersede old row, insert new.
4. Unmapped `provider_op_code` (no `service_operation_xref` row) → line lands in a review
   queue (`labor_guide_unmapped_operation` table), import continues. Mapping is curation work,
   surfaced via `GET /v1/catalog/labor-guide-imports/unmapped`.
5. Completion check: counts + checksum → `COMPLETE` or `INCOMPLETE` with gap report;
   `GET /v1/catalog/labor-guide-imports/incomplete` mirrors the supplier-price status
   endpoint.

CSV/offline feeds (some OEMs ship files, not APIs) enter the same funnel via pos-bulk-loader →
a new `POST /v1/catalog/labor-standards/bulk-ingest` (`AbstractBulkIngestController`, same
`pos-bulk-ingest-lib` envelope as the ~19 existing targets), which internally synthesizes a
manifest so provenance bookkeeping is identical either way.

### 5.4 Licensing checklist (must be answered per source, blocks its adapter)

- May times be **persisted** in our DB? For how long after contract end?
- May times be **replicated to other services in our own platform** (the Kafka fact question —
  decides whether even `default_labor_hours` derived from licensed data may ride
  `CatalogServiceUpdatedV2`)?
- May times be **shown on customer-facing quotes/invoices** and must attribution appear?
- Per-lookup vs. flat pricing (drives cache TTL and the QUERY_ONLY cache policy)?
- Central (Durion-negotiated) vs. bring-your-own-subscription per dealer (#1575 "Why central
  licensing matters")? BYO forces per-tenant credentials in the provider config — the
  `SupplierProfileProperties`-style per-profile config already accommodates this; flag it now
  so the config schema includes an optional per-location credential ref from day one.

---

## 6. Transport to pos-workorder (ADR-0044)

Two mechanisms, both used, for different halves of the data — this is the same split the
platform already made for supplier data (prices ride events; live stock is a scoped REST edge
because it "cannot be replicated"):

### 6.1 Scoped REST edge (primary path): vehicle-specific resolution at quote time

The vehicle-keyed matrix is large, licensed, and query-shaped (you need one answer for one
vehicle now) — replicating it into pos-workorder would copy a licensed dataset into a second
store and still miss QUERY_ONLY sources. So:

- **Grant surface** in `com.positivity.catalog.service`:
  `ServiceLaborTimeService.resolveLaborTime(LaborTimeQuoteRequest): LaborTimeQuoteResponse`,
  records in `catalog.service.model`. `package-info.java` states the grant, callers, and
  degradation contract exactly like `pos-supplier/.../service/package-info.java`. This becomes
  the platform's **second** ADR-0026 D2 grant; needs an ADR amendment naming it (draft:
  "amendment 2026-09-xx: pos-workorder → pos-catalog labor-time resolution, file-scoped").
- **REST edge**: `POST /v1/catalog/labor-times/resolve` in a thin internal controller,
  `@PreAuthorize` on new `catalog:labor_time:resolve` permission,
  `@EmitEvent(id = "CATALOG_LABOR_TIME_RESOLVE")`.
- **Response contract** (typed degradation, never throws for miss):
  `status = RESOLVED | NO_TIME_AVAILABLE | SOURCE_UNAVAILABLE`, and on `RESOLVED`:
  `laborHours`, `timeType`, `sourceCode`, `sourceRevision`, `matchGrade
  (EXACT | ENGINE_WILDCARD | MODEL_LEVEL | DEFAULT_HOURS)`, `overlapGroup`,
  `includedOpCodes`, `attributionText?` (license-driven).
- **Caller**: `pos-workorder/internal/client/CatalogLaborTimeClient(+Impl)` — load-balanced
  `RestClient` per the `DocumentClient` pattern (`loadBalancedRestClientBuilder`,
  `http://${pos.catalog.service-id:catalog}`; do **not** copy `TaxClientConfig`'s hardcoded
  base-url inconsistency).
- **ArchUnit**: add the file pair to `DomainWallsTest.SCOPED_FILE_EXCEPTIONS` (file-scoped, not
  module-scoped, for the same reason documented there: a third caller must argue its own case).

### 6.2 Fact v2 (degraded/default path): vehicle-agnostic default only

- Bump `CatalogServiceUpdatedV1` → **`CatalogServiceUpdatedV2`** (`catalog.service.updated`,
  schema v2) adding `operationCode`, `operationCategory`, `defaultLaborHours`. Vehicle-specific
  rows never ride the fact (volume + licensing).
- pos-workorder gains a small `ext_catalog_service` replica (id, name, operationCode,
  defaultLaborHours, active) — its first catalog replica; used when the REST edge is down
  (`SOURCE_UNAVAILABLE` → fall back to replica default → writer can still override) and for
  offline estimate drafting.
- pos-marketing's `CatalogEventsListener` must tolerate the added fields (additive change;
  verify its deserializer ignores unknowns — the repo's records + Jackson generally do, but the
  listener test should pin it).
- Replay via the existing `ServiceFactReplayServiceImpl` covers replica bootstrap.

### 6.3 In pos-workorder: defaulting, snapshotting, summing

1. **Defaulting**: when a writer adds a `LABOR` `EstimateItem` with a `serviceId` and the
   workorder has a resolved vehicle, call the edge; prefill `quantity` with `laborHours`.
   Writer can override — prefill, not lock.
2. **Snapshot both numbers** (#1569 scope item 5): new columns on `estimate_item` and
   `workorder_service` — `guide_hours numeric(5,1)`, `guide_source_code`,
   `guide_source_revision`, `guide_match_grade`; `quantity` remains the agreed hours.
   Promotion copies all of them onto `WorkorderServiceLine` (extend the existing
   `originEstimateItem` promotion mapping).
3. **Summation** (#1569 scope item 6): `estimatedLaborHours = Σ agreed hours` with overlap
   handling: lines sharing an `overlap_group` contribute `max(group)` plus configured
   per-additional-line delta rather than the naive sum; lines whose op code appears in another
   line's `includedOpCodes` contribute 0 and are flagged on the response. Finally populates
   `WorkorderSummary.estimatedLaborHours` — the field stops lying.
4. **Variance** (#1569 scope item 7): expose `estimatedLaborHours` vs. already-computed
   `totalLaborHours` on the workorder detail response (`varianceHours`, `variancePct`). Later
   feeds Tier 4 (§8).

---

## 7. Phase plan (labor-time track)

Phases are cumulative; each has a demo and exit criteria. Phase numbers here are the "states"
from the task: Phase 1 = mock state, Phase 2 = aggregator state, Phase 3 = multi-source end
state.

### Phase 0 — Decisions and scaffolding (no runtime behavior)

Deliverables:

- This plan vetted; the §4.1 keying decision and §3.1 pattern decision ratified or overturned.
- ADR drafts: (a) labor-time sourcing architecture + the pos-catalog grant (ADR-0044
  amendment), (b) taxonomy/naming ADR fixing the terms in §1.
- `V16` taxonomy migration + `CatalogItemRequestDto`/`copyOntoServiceEntity` extension +
  backfill of `operation_code`/`operation_category` for the 50 seeded services
  (`R__seed_reference_catalog_3_services.sql` gains codes; e.g. the
  `Spark Plug Replacement - 6-cylinder` name-workaround rows get distinct codes and become the
  Phase 1 dedup test case).
- Permission + event-type registry entries.

Exit: build green including `pos-archunit`; `/v1/catalog-items/service` round-trips the new
fields.

### Phase 1 — Mock provider, full pipeline end-to-end (FIRST STATE)

The point: every seam exists and is exercised before money or licensing enters.

Deliverables:

1. **`pos-reference-mock` module** (§10) serving the normalized labor-guide contract (and, for
   the fitment track, an ACES-ish applications contract) from checked-in JSON fixtures: the 50
   seeded services × a ~20-vehicle matrix, deterministic hours, at least one overlap group
   (front+rear brakes sharing wheel-off), one included-op case, one diagnostic block op, and
   both `RETAIL_FLAT_RATE` and `OEM_WARRANTY` rows for one op so `time_type` precedence is
   testable.
2. `LaborTimeProviderPort` SPI + `mockguide` adapter (STORE mode) + second config profile of
   the same adapter as QUERY_ONLY to cover the live path.
3. `V17`/`V18` migrations; `LaborStandardIngestService` with chunk/manifest bookkeeping,
   unmapped-op queue, supersede-on-change; admin import + status endpoints.
4. `LaborTimeResolutionService` (exact + widening match, single-source precedence).
5. Grant surface + REST edge + ArchUnit file exception + `CatalogLaborTimeClient` in
   pos-workorder.
6. `EstimateItem` defaulting + dual snapshot columns + promotion mapping.
7. Overlap-aware summation → `WorkorderSummary.estimatedLaborHours`; variance fields on the
   detail response.
8. `CatalogServiceUpdatedV2` + pos-workorder replica + pos-marketing tolerance test.
9. docker-compose: `pos-reference-mock` container; `application-dev.yml` defaults point
   adapters at `http://localhost:${mock-port}` (locally) / `http://pos-reference-mock:8095`
   (compose).

Exit criteria (demoable): create a workorder for a fixture vehicle → add two brake `LABOR`
lines → quantities prefill from mock-sourced standards with source shown → summary shows
overlap-adjusted `estimatedLaborHours` ≠ naive sum → clock actual hours → variance renders.
Kill the mock container → resolution degrades to `SOURCE_UNAVAILABLE` → replica default hours
prefill instead, flow never 500s.

### Phase 2 — Licensed aggregator (MIDDLE STATE)

Deliverables:

1. Vendor selection + license executed; §5.4 checklist answered and recorded in the ADR
   (this gates everything below and is calendar-bound by procurement, not engineering —
   engineering continues on Tier-0 authoring meanwhile).
2. `<aggregator>` adapter implementing the port for the licensed mode; per-tenant credential
   support if BYO-subscription was negotiated.
3. `service_operation_xref` seeding for the vendor's taxonomy; curation workflow for the
   unmapped queue becomes real work (assign owner).
4. Scale pass: import volume (aggregator feeds are millions of rows once vehicle-keyed) —
   partitioning/pruning decision on `service_labor_standard`, import batch sizing, resolve-path
   latency budget (< 50 ms p95 inside pos-catalog).
5. Attribution rendering on estimate/quote documents per license.
6. The mock is retired from *default* config but kept green in CI as the contract-test double
   for the SPI (adapters are additionally contract-tested against recorded vendor fixtures).

Exit: same Phase-1 demo runs against real aggregator data for real vehicles; mock only in tests.

### Phase 3 — Multi-source: OEM + manufacturers primary, aggregator backup (END STATE)

Deliverables:

1. Adapters per negotiated source: OEM warranty-time feeds (per-OEM shape, often files →
   bulk-loader funnel §5.3), parts-manufacturer install times (Michelin tire ops first —
   doubles as Tier 0 differentiation from #1575).
2. `labor_time_source_policy` becomes operative: precedence per `time_type` and
   `operation_category` (tire ops prefer `MANUFACTURER_INSTALL`; mechanical retail prefers
   aggregator `RETAIL_FLAT_RATE`; warranty workorders prefer `OEM_WARRANTY`).
3. Cross-source conflict surfacing: `GET /v1/catalog/labor-standards/conflicts` (same op +
   vehicle, sources disagree beyond a threshold) for curation.
4. Workorder type awareness: warranty vs. retail flag on the resolve request selects
   `time_type`.
5. Fallback semantics: primary-source miss falls through policy order to aggregator; response
   `matchGrade`/`sourceCode` always says which source answered.

Exit: for a fixture set of operations, resolution demonstrably answers from OEM/manufacturer
when present and aggregator otherwise, with policy editable as data.

### Phase 4 — Durion labor intelligence (Tier 4, continuous)

Not a sourcing phase — a feedback loop. Sketch only; own plan when Phase 3 stabilizes:

- Nightly aggregation of `WorkorderLaborEntry` actuals vs. snapshot `guide_hours` per
  (operation, vehicle-class, shop, technician) into `DURION_STANDARD` candidate rows —
  suggested, never auto-promoted; curation promotes them (they then compete via the policy
  table like any source).
- Scheduling capacity export to pos-shop-manager (summed book time as slot-length input).
- The variance data from Phase 1 is the raw material; nothing else needs pre-building now.

---

## 8. Relationship to #1573 / timekeeping (boundary statement)

Estimated service time and time entries are **unrelated systems** (owner ruling on #1573).
This plan: never touches `time_entry`, `work_session`, `TimekeepingEntry`, or the CAP-139
approvals surface; consumes only `WorkorderLaborEntry.hoursWorked` (task-clock actuals already
summed as `totalLaborHours`) for variance. If CAP-139 work later adds list endpoints in
pos-people, nothing here changes. The only shared concern is naming discipline (§1) so the two
vocabularies never collide again.

---

## 9. Mirror track: parts-fitment sourcing plan (pos-vehicle-fitment)

Same three states, same architecture, applied to parts applicability. Kept deliberately
parallel so decisions vetted once apply twice.

### 9.1 Current state (from code, 2026-08-29)

- Only write path is bulk ingest (`POST /v1/fitments/bulk-ingest` ← pos-bulk-loader CSV).
- Vehicle hierarchy (manufacturer/make/model/type) is fetched **inline** from public NHTSA
  vPIC (`VehicleFitmentServiceImpl`, hardcoded URL, 24 h delete-and-refill cache) — vendor
  call on the read path, no SPI.
- `pos-vehicle-reference-carapi` / `-nhtsa` duplicate that logic, are consumed by nobody, are
  commented out of docker-compose, and `-nhtsa`'s controller collides on
  `/v1/vehicle-fitment`.
- No provenance on `PartFitmentEntity` (no source, no revision), `partNumberId` is a bare
  `Long`, and `createFitment` creates make/model rows on the fly from whatever strings arrive.

### 9.2 Target architecture (mirror of §3)

- `internal/spi/FitmentProviderPort` + `internal/spi/VehicleReferenceProviderPort` in
  pos-vehicle-fitment; adapters: `mockfitment` (Phase F1), an ACES/PIES-capable aggregator
  (Phase F2), OEM/manufacturer catalogs — tire fitment guides first, Michelin per Tier 0 —
  with aggregator backup (Phase F3).
- Vehicle-reference refresh becomes an explicit scheduled/triggered sync through the port —
  never on the read path; fixes the inverted `isCacheExpired` bug (§9.5) by deleting that
  code path entirely.
- Fitment rows gain provenance: `source_code`, `source_revision`, `import_manifest_id`,
  `superseded_at` + the same import-log pair of tables (shared design §5.3; implementation
  copied, not shared as a library, until a third copy justifies extraction into
  `pos-bulk-ingest-lib`).
- ACES vehicle ids stored when supplied — the long-term join key with
  `service_labor_standard.aces_vehicle_id` (§4.3).

### 9.3 Phases

- **F0**: provenance migration (`V3__fitment_provenance.sql`), decision on `partNumberId`
  Long → UUID alignment with pos-catalog product ids (its own small migration plan — flagged,
  not solved here), retire the `/api/...` stale paths in `README-VEHICLE-HINTS.md`.
- **F1 (mock)**: `pos-reference-mock` (§10) serves `GET /mock/fitment/v1/applications`
  (ACES-ish: part → vehicle applications) and `GET /mock/vehicle-reference/v1/...`
  (makes/models/engines) from fixtures aligned with the labor-guide fixtures (same 20
  vehicles). Ports + adapters + scheduled reference sync + import funnel. Bulk-ingest path
  retained unchanged (CSV loads remain a source, now with `source_code = 'CSV_LOAD'`).
- **F2 (aggregator)**: licensed ACES/PIES data provider adapter; xref of vendor part/vehicle
  ids; same scale/curation items as Phase 2.
- **F3 (multi-source)**: manufacturer fitment feeds (tire first) primary where present,
  aggregator backup, same policy-table precedence idea.

### 9.4 Fate of pos-vehicle-reference-carapi / -nhtsa (decision for vetting)

**Proposed: retire both modules** once F1's `VehicleReferenceProviderPort` + NHTSA adapter
lands inside pos-vehicle-fitment (the NHTSA call logic moves; the carapi adapter is written
only if carapi is actually wanted — note the existing module never handled carapi
authentication, so it never worked against the real API anyway). They are already
compose-disabled and consumer-less, so retirement is deleting dead code, not a migration.
Alternative (rejected): promote them to real shared reference services — rejected because no
second consumer exists and the inline duplication already proved the consumer won't pay the
hop.

### 9.5 Known-bug fix list folded into F1 (cheap while touching the code)

- `VehicleFitmentServiceImpl.isCacheExpired` polarity inversion (fresh cache reported
  expired / vice-versa on three of four fetch paths).
- `-nhtsa` controller path collision (`/v1/vehicle-fitment`) — moot if §9.4 retirement is
  ratified.
- `RestClient.create()` plain client → module-standard builder with proxy/observability
  defaults.

---

## 10. The mock service: `pos-reference-mock`

One new Maven module, one Spring Boot app, serving **both** tracks' vendor contracts — a fake
*external vendor*, not a platform service. Design rules:

- **Outside the mesh**: no Eureka registration, no gateway route, no JWT — reached only via
  adapter base-url config (the pos-supplier sandbox-override pattern,
  `pos.catalog.labor-guide.providers[mockguide].base-url`). This keeps Phase-2 cutover a pure
  config change and prevents anything from accidentally treating mock data as a platform API.
- Fixed port **8095** (8080 gateway / 8761 eureka / 8090 bulk-loader taken); docker-compose
  service `pos-reference-mock` on `pos-network`; trivially runnable standalone
  (`cd pos-reference-mock && ../mvnw spring-boot:run`) for dev-profile work.
- **Fixture-driven**: JSON under `src/main/resources/fixtures/{laborguide,fitment,vehicleref}`,
  checked in, deterministic, documented per §7 Phase 1 item 1 (overlap, included-ops,
  diagnostic, warranty-vs-retail cases are mandatory fixtures, not nice-to-haves — they keep
  the summation and precedence logic honest from day one).
- **Contract-first**: the mock's OpenAPI file is the normative description of the *Durion
  normalized provider contract*; Phase-2 adapters translate vendor reality onto it. Mock stays
  alive forever as the SPI contract-test double (Phase 2 item 6).
- Endpoints (v1): `GET /mock/labor-guide/v1/operations`,
  `GET /mock/labor-guide/v1/labor-times`, `GET /mock/labor-guide/v1/feed/manifest`,
  `GET /mock/labor-guide/v1/feed/chunks/{seq}` (STORE-mode pull), and the §9.3 fitment +
  vehicle-reference sets. Optional chaos knobs (`?delayMs=`, `?failRate=`) for degradation
  testing — the Phase-1 exit criterion "kill the mock, flow never 500s" wants them.
- Excluded from coverage aggregation thresholds if `pos-coverage-aggregate` would otherwise
  count it; included in the reactor build so it can't rot.

---

## 11. Cross-cutting checklists

**ArchUnit / ADR compliance**

- New grant surface `com.positivity.catalog.service.ServiceLaborTimeService` (+ model): ADR-0026
  D2 grant naming, D4 no-`internal.*` dependency check, `package-info.java` per the
  pos-supplier exemplar.
- `DomainWallsTest.SCOPED_FILE_EXCEPTIONS`: add
  `pos-workorder/.../CatalogLaborTimeClientImpl.java → pos-catalog`.
- Run `./mvnw -pl pos-archunit -am -Dtest=ArchitectureTests test` after every package-layout
  change; each touched module's own `ArchitectureTest` must stay green.
- `pos-reference-mock` needs its own `ArchitectureTest` exemption note (it has no
  `internal/` split to enforce — decide whether to give it the standard layout anyway for
  uniformity; proposed: yes, it's cheap).

**Events & permissions**

- `CatalogEventTypes` + initializer entries for the §4.4 ids; `CatalogPermissionRegistry`
  additions (`catalog:labor_standard:view|manage|import`, `catalog:labor_time:resolve`);
  gateway `GatewayPermissionCatalog` bit allocation — append-only, never reuse bits
  (#1569 note on bits 274–277).
- `CatalogServiceUpdatedV2` in `pos-domain-events`; publisher bump in `CatalogFactPublisher`;
  replay support; pos-marketing tolerance test; pos-workorder listener + replica migration.

**Testing**

- SPI contract test suite runs against `pos-reference-mock` fixtures (both tracks).
- Import idempotency tests: chunk re-delivery, resume after `INCOMPLETE`, supersede-not-update.
- Resolution matrix tests: match-grade widening, precedence policy, QUERY_ONLY cache TTL,
  typed misses.
- pos-workorder: defaulting override, dual-snapshot promotion, overlap summation (fixture with
  known non-naive total), variance arithmetic, degraded path (edge down → replica default).

**Operational**

- Runbook entries (docs/OPERATIONS_RUNBOOK.md): triggering imports, reading
  `/imports/incomplete`, unmapped-operation curation, source policy editing.
- Observability: import counters (lines applied/skipped/unmapped), resolve latency + hit/miss
  by source, per-adapter vendor-call metrics (mirror supplier client conventions).

---

## 12. Open questions for the vetting rounds

1. **Keying (§4.1)**: ratify vehicle-keyed child table in pos-catalog vs. service-fitment in
   pos-vehicle-fitment. Everything downstream reshapes if this flips.
2. **Grant transport (§6.1)**: REST edge is proposed as primary. Counter-position — replicate
   the full standards table into pos-workorder via chunked events (supplier-price style) and
   resolve locally. Rejected in draft for volume + QUERY_ONLY sources + licensing; a reviewer
   holding strong event-only convictions (ADR-0044's default) should attack this section.
3. **Aggregator shortlist and license posture** (Phase 2 item 1): MOTOR vs. Mitchell 1 vs.
   ALLDATA — data licensing (embeddable feed?) vs. API-only, redistribution terms, ACES id
   availability, per-lookup pricing. Procurement task; needs an owner and a date.
4. **`partNumberId` Long → UUID** (§9.3 F0): scope and sequencing of aligning
   pos-vehicle-fitment's part key with pos-catalog product ids.
5. **Warranty workorders**: does pos-workorder model warranty vs. retail today (Phase 3 item 4
   assumes a flag can exist)? If not, where does that live — workorder type or line-level?
6. **Overlap arithmetic v1** (§6.3): `max(group) + delta` is a simplification; real guides
   publish explicit overlap deductions. Good enough until Phase 2 data arrives, or model
   deductions now?
7. **Mock scope creep guard**: the mock intentionally serves both tracks; if fixtures diverge
   or a third track appears (pricing?), split per-track then, not preemptively. Agree?
8. **Retirement of the two reference modules** (§9.4): confirm no roadmap consumer exists
   before deletion lands in F1.
9. **`default_labor_hours` on the fact**: if Phase-2 licensing forbids even derived defaults
   riding Kafka, the degraded path thins to "no prefill" — acceptable? (Tier-0/DURION-sourced
   defaults are always safe to ride the fact; the question is only about licensed-derived
   values.)
10. **Per-tenant/BYO credentials** (§5.4): does the platform have a per-location secret
    storage convention the provider config can reference, or does that need designing first?

---

## 13. Issue → plan traceability

| Source | Item | Where addressed |
|---|---|---|
| #1569 scope 1 | vehicle-keying decision | §4.1 (proposed), §12 Q1 |
| #1569 scope 2 | book-time fields + authoring surface | §4.2, §4.4, Phase 0/1 |
| #1569 scope 3 | feed importer on chunked-manifest pattern; licensing first | §5, Phase 2 item 1 |
| #1569 scope 4 | transport to pos-workorder; marketing tolerance | §6, Phase 1 items 5/8 |
| #1569 scope 5 | default `EstimateItem.quantity`; dual snapshot | §6.3 items 1–2 |
| #1569 scope 6 | overlap-aware `estimatedLaborHours` | §6.3 item 3 |
| #1569 scope 7 | estimate vs. actual | §6.3 item 4, Phase 4 |
| #1569 naming | own name, avoid `work_session` | §1 naming table |
| #1573 | timekeeping boundary | §8 |
| #1575 Tier 0 | Durion-owned ops, tire/fleet IP | §4.4, Phase 3 item 1, Phase 4 |
| #1575 Tier 1 | direct OEM access, no bulk ingest w/o license | §5.1 QUERY_ONLY, Phase 3 |
| #1575 Tier 2 | one licensed provider as default paid integration | Phase 2 |
| #1575 Tier 3 | advanced diagnostic content as optional subscription | out of scope here; provider config's per-source enablement leaves the door open |
| #1575 Tier 4 | Durion labor intelligence | Phase 4 |
| #1575 architecture | `LaborGuideProvider` abstraction, vendor-replaceable | §3.3 (`LaborTimeProviderPort`) |
| #1575 recommendation | buy commodity data, own workflow, build tire/fleet IP | the phase ordering itself |
