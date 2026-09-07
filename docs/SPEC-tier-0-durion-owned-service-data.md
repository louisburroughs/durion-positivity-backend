# Tier 0 — Durion-Owned Service Data

**Specification for [#1575](https://github.com/louisburroughs/durion-positivity-backend/issues/1575)
Tier 0, and the [#1569](https://github.com/louisburroughs/durion-positivity-backend/issues/1569)
scope it unblocks.**

**Status:** IMPLEMENTED — T0-1 … T0-5 and R1 … R4 are built and merged to
`claude/tier-0-spec-implementation-o5j539`. Deliberate deviations from the spec as written are
recorded in §9; T0-2's tire-fitment and Michelin-content exclusions (§6) stand unchanged.
**Date:** 2026-09-07
**Owner modules:** pos-catalog (primary), pos-price, pos-workorder
**Pins:** ADR-0058 (labor-time sourcing architecture), ADR-0059 (naming/taxonomy),
ADR-0044 (cross-module transport), ADR-0026 (grant surfaces), ADR-0054 (sell-price SoR split),
ADR-0053 (chunked-manifest import).
**Predecessor plan:** `pos-catalog/docs/service-time-sourcing-plan.md` (Phases 0–1 shipped).

---

## 0. What this specification covers, and why now

#1575 recommends a five-tier sourcing strategy. Tier 2 (a licensed commercial labor guide) is
the default paid integration and is **calendar-blocked on procurement** — the §5.4 licensing
checklist has to be answered per source before its adapter may be built. Tier 1 (direct OEM
access) and Tier 3 (advanced diagnostics) are likewise licence-gated.

**Tier 0 is the only tier Durion can build entirely on its own**, and #1575 names it as the
place "where Durion can develop differentiated intellectual property". It is therefore the
correct next build while procurement runs, and it is what the sourcing plan's Phase 2 entry
already anticipates: *"engineering continues on Tier-0 authoring meanwhile."*

**"With fake data"** means every Tier 0 row this build seeds is invented reference data, carried
in repeatable Flyway seeds, stamped with a provenance revision that identifies it as such
(§3 D6). Nothing here claims to be, or is derived from, a licensed guide. The shapes are real;
the numbers are not.

**"And #1569 after"** — #1569's scope items 1–7 all shipped in Phases 0–1. What remains of it
are the items that were *blocked on having Durion-owned data and a labor rate to work with*.
Those are specified in §5 and built after §4.

### Ordering

```
§4  Tier 0 build            T0-1 … T0-5
      ↓  (Durion-owned rows exist; a labor rate exists)
§5  #1569 residual          R1 … R4
```

---

## 1. Current state (evidence)

| Capability | Where | State |
|---|---|---|
| Operation taxonomy | `service.operation_code` / `operation_category` / `default_labor_hours` | Built (V17). 50 seeded services carry codes; 4 are `TIRE_SERVICE`. |
| Vehicle-keyed times with provenance | `service_labor_standard` (V18) | Built. Append-and-supersede, `ux_sls_active_key` partial unique index. |
| Time classes | `ck_sls_time_type` | `RETAIL_FLAT_RATE`, `OEM_WARRANTY`, `MANUFACTURER_INSTALL`, `DURION_STANDARD` |
| Vendor feed ingest | `service_operation_xref`, `labor_guide_import(_chunk)`, `labor_guide_unmapped_operation` (V19) | Built against `pos-reference-mock` only. |
| Source precedence | `labor_time_source_policy` (V19) | Built, keyed `(time_type, source_code)`. **No operation-category dimension.** |
| Resolution | `LaborTimeResolutionServiceImpl` | Built. Specificity → type preference → policy. No location/ownership dimension. |
| Grant edge to pos-workorder | `catalog.service.ServiceLaborTimeService`, `POST /v1/catalog/labor-times/resolve` | Built (ADR-0044 amendment 2026-09-02). Request carries `serviceId` + year/make/model/submodel/engine + `preferredTimeType`. |
| Estimate prefill | `LaborTimeDefaultingService` | Built. **Calls the edge with year/make/model only** — drops submodel, engine and `preferredTimeType`. |
| Guide snapshot on the quote | `estimate_item` / `workorder_service` guide columns (V26) | Built. |
| Overlap-aware total | `EstimatedLaborService` → `WorkorderSummary.estimatedLaborHours` | Built. `max(group) + 0.5 × others`. |
| Variance | detail response `estimatedLaborHours` / `actualLaborHours` / `laborVarianceHours` / `laborVariancePct` | Built. |
| **Labor rate** | pos-price | **Absent.** `ProductBasePrice`, tiers, promotions, price books — nothing hourly. `grep -rn "labor_rate\|LaborRate"` over pos-price/pos-shop-manager/pos-location/pos-workorder returns nothing. |
| **Shop labor matrix** | — | **Absent.** |
| **Service packages** | — | **Absent.** No bundle/kit/package entity in pos-catalog. |
| **Shop-owned times** | — | **Absent.** Every `service_labor_standard` row is platform-global. |
| **Historical actual times as an input** | pos-workorder | Actuals flow (`WorkorderLaborEntry.hoursWorked` → `totalLaborHours`) and variance is computed per workorder, but **nothing aggregates across workorders**. |

---

## 2. Tier 0 inventory → decisions

Each bullet #1575 lists under Tier 0, with its home and this build's verdict.

| #1575 Tier 0 item | Home | Verdict |
|---|---|---|
| Tire service operations | pos-catalog | **IN** — T0-1. Durion-authored `DURION_STANDARD` times for the tire operation set, vehicle-keyed. |
| Tire fitment data *"where appropriately licensed"* | pos-vehicle-fitment | **OUT** — the qualifier is the whole point: fitment data is licence-gated, and the fitment mirror track is §9 of the sourcing plan with its own phasing. Deferred, §6. |
| Michelin-specific procedures | pos-catalog | **PARTIAL — IN** for `MANUFACTURER_INSTALL` times under a `MICHELIN` source; **OUT** for procedure *content* (documents/steps), which is a document-management concern, not a time. §6. |
| Fleet-specific requirements | pos-catalog | **IN** — T0-4, as fleet-scoped service packages whose members are required. |
| Dealer-created labor operations | pos-catalog | **IN (scoped)** — T0-2 gives *times* an owner scope. Dealer-created *services* already work through `POST /v1/catalog-items/service`; see D2 for why the service row itself is not scoped. |
| Shop labor rates | pos-price | **IN** — T0-3. |
| Service packages | pos-catalog | **IN** — T0-4. |
| Historical actual repair times | pos-workorder → curation → pos-catalog | **IN** — T0-5, advisory only. |
| Shop-specific pricing rules | pos-price | **IN** — T0-3, as the labor rate matrix. |

---

## 3. Cross-cutting decisions

### D1 — Ownership is a property of the *time*, not a new table

A shop's own number for an operation is the same shape as a guide's number for it: hours, for a
vehicle key, from a source, at a revision. It differs only in **who owns it and who may see
it**. So `service_labor_standard` gains two columns rather than a parallel table:

- `owner_scope` — `PLATFORM` (visible to every location) or `SHOP`
- `owner_location_id` — required when `SHOP`, forbidden otherwise (CHECK-enforced)

`ux_sls_active_key` must be widened to include the owner, or a shop's row for an operation
collides with the platform row for the same vehicle key and the shop can never author one.

### D2 — Operations stay platform-level; only times are shop-scoped

"Dealer-created labor operations" could mean a dealer-private `service` row. Scoping `service`
itself would ripple through catalog-item listing, the `catalog.service.updated` fact, the
pos-marketing `ext_catalog` replica and the pos-workorder `ext_catalog_service` replica —
every consumer would need a location filter it has no way to apply, and a fact is broadcast,
not addressed.

**Decision:** the operation taxonomy stays global (it is the shared vocabulary vendor codes map
onto — ADR-0059 §3); the *time* is what a dealer owns. A dealer creating a genuinely new
operation creates an ordinary catalog service and authors a `SHOP`-scoped time for it. Recorded
as a deliberate narrowing, revisitable if a private-catalog requirement appears.

### D3 — Resolution precedence gains two dimensions

Today: vehicle specificity → time-type preference → `(time_type, source)` policy. Tier 0 adds:

1. **Owner scope, ahead of everything.** A `SHOP` row for the requesting location always beats
   a `PLATFORM` row — a shop that has priced its own work is not overruled by a guide. A `SHOP`
   row for a *different* location never matches at all.
2. **Operation category on the policy row.** `labor_time_source_policy` gains a nullable
   `operation_category`; a row stating one applies only to that category, and a more specific
   policy row wins over a category-less one. This is what makes "tire ops prefer
   `MANUFACTURER_INSTALL`, mechanical retail prefers the aggregator" data rather than code —
   sourcing-plan Phase 3 item 2, which Tier 0 needs *now* because `MICHELIN` and `DURION` rows
   would otherwise be ordered by the hard-coded `DEFAULT_TYPE_ORDER` alone.

Full order: `owner scope` → `vehicle specificity` → `time-type preference` → `policy precedence`.

### D4 — A service package carries its own authored hours

A package's labor time is **not** derived by re-running overlap arithmetic over its members.
Two reasons: the overlap logic lives in pos-workorder (`EstimatedLaborService`) and duplicating
it in pos-catalog creates two answers to one question; and a real shop prices a package as a
number it chose ("4-tire install, 1.2 hr"), not as a computed rollup of parts.

**Decision:** `service_package.package_labor_hours` is authored Durion-owned data. Members
define *what is included* (and therefore what must not be billed again); the hours are stated.

### D5 — Historical actuals are advisory and never auto-promote

The aggregation lives in **pos-workorder**, which owns the actuals; nothing crosses a module
wall to compute it. It is exposed as a read-only analytics endpoint returning *candidates*.
Promotion is a deliberate human action that calls pos-catalog's existing labor-standard
authoring API, which stamps the row `DURION` / `SHOP`-scoped like any other hand-authored time.

No fifth `time_type` is introduced: a promoted candidate is a `DURION_STANDARD` row, and adding
`DURION_HISTORICAL` would change a CHECK constraint, four enums and every precedence path for a
value that is only ever a suggestion until a human accepts it.

### D6 — Fake data is labelled as fake

Every Tier 0 seeded row carries `source_revision = 'tier0-fake-2026-09'` (labor standards) or
the equivalent marker column, and lives in a repeatable `R__` seed. One `DELETE ... WHERE
source_revision = 'tier0-fake-2026-09'` removes the whole fake set. Seeds are additive and
idempotent (`ON CONFLICT DO NOTHING`), matching `R__seed_reference_catalog_6_labor_guide.sql`.

### D7 — The labor rate belongs to pos-price

ADR-0054 splits sell-price system-of-record; an hourly labor rate is a sell price. pos-catalog
owns *how long*, pos-price owns *how much per hour*. pos-workorder multiplies them. This keeps
the #1569 non-goal boundary intact while satisfying #1575's Tier 0 "shop labor rates" — the
item moves in scope because Tier 0 names it, not because the boundary moved.

---

## 4. Tier 0 workstreams

### T0-1 — Durion-owned tire, Michelin and fleet operations with times (fake data)

**Schema:** none beyond T0-2.

**Data (repeatable seeds, pos-catalog):**

- Extend the operation taxonomy with the Tier-0 operation set that a tire-and-fleet provider
  actually sells and that no guide publishes: TPMS service, road-force balance, tire repair,
  torque re-check, DOT inspection items, fleet PM operations, commercial/LT tire operations.
  New `operation_code`s under `TIRE_SERVICE` and `MAINTENANCE`.
- `service_labor_standard` rows at `DURION_STANDARD` / source `DURION`, vehicle-keyed across
  the passenger/LT/commercial classes, including at least: one overlap group (a 4-tire
  operation sharing wheel-off with a brake operation), one included-operation case (a tire
  install including a balance), and one wildcard-vs-specific pair so the match-grade ladder is
  exercised by Tier-0 data and not only by mock-guide data.
- `MICHELIN`-source `MANUFACTURER_INSTALL` rows for the Michelin-specific operations, plus a
  `labor_time_source_policy` row making `MANUFACTURER_INSTALL`/`MICHELIN` win for
  `TIRE_SERVICE` (needs D3's category column — hence R1 lands with this).

**Tests:** resolution returns the Michelin time for a tire op on a fitted vehicle and the
`DURION` time otherwise; the overlap group and included-op rows survive a round trip through
the edge into `EstimatedLaborService`'s arithmetic.

---

### T0-2 — Shop-owned labor standards

**Schema — pos-catalog `V21__service_labor_standard_ownership.sql`:**

```sql
ALTER TABLE service_labor_standard ADD COLUMN owner_scope varchar(16) NOT NULL DEFAULT 'PLATFORM';
ALTER TABLE service_labor_standard ADD COLUMN owner_location_id uuid;
ALTER TABLE service_labor_standard ADD CONSTRAINT ck_sls_owner_scope
    CHECK (owner_scope IN ('PLATFORM','SHOP')
           AND ((owner_scope = 'SHOP' AND owner_location_id IS NOT NULL)
                OR (owner_scope = 'PLATFORM' AND owner_location_id IS NULL)));
DROP INDEX ux_sls_active_key;
CREATE UNIQUE INDEX ux_sls_active_key ON service_labor_standard (
    service_id, time_type,
    COALESCE(owner_location_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(vehicle_year,''), COALESCE(make,''), COALESCE(model,''),
    COALESCE(submodel,''), COALESCE(engine_code,'')
) WHERE superseded_at IS NULL;
CREATE INDEX ix_sls_owner ON service_labor_standard (owner_location_id)
    WHERE superseded_at IS NULL AND owner_location_id IS NOT NULL;
```

**API:**
- `ServiceLaborStandardRequestDto` gains `ownerScope` + `ownerLocationId` (default `PLATFORM`).
- `LaborTimeQuoteRequest` gains `locationId` (nullable — no location means platform rows only).
- `LaborTimeQuoteResponse` gains `ownerScope` so a caller can show "your shop's time".
- The imported-row rule is unchanged: only `DURION`-source rows are editable through the API,
  and a `SHOP` row must be `DURION`-source (a shop cannot forge a vendor's provenance) —
  enforced in `LaborTimeValidation`, answered `409`/`422` per existing convention.

**Resolution:** `SHOP` rows matching `owner_location_id == request.locationId` sort ahead of all
`PLATFORM` rows; `SHOP` rows for any other location are filtered out before ranking.

**Permissions:** reuse `catalog:labor_standard:manage` / `:view`. A shop-scoped write is still
a labor-standard write; per-location authorization is a downstream concern, noted in §6.

---

### T0-3 — Shop labor rates and the labor matrix (pos-price)

**Schema — pos-price `V4__labor_rate.sql`:**

```
labor_rate
  id uuid PK, location_id uuid NULL (NULL = platform default),
  operation_category varchar(32) NULL (NULL = all categories),
  currency char(3) NOT NULL, hourly_rate numeric(10,4) NOT NULL,
  effective_from timestamptz NOT NULL, effective_to timestamptz NULL,
  created_at, updated_at
  CHECK operation_category IN (REPAIR, DIAGNOSTIC, MAINTENANCE, TIRE_SERVICE) OR NULL
  CHECK hourly_rate > 0

labor_rate_adjustment            -- the shop labor matrix
  id uuid PK, location_id uuid NULL, operation_category varchar(32) NULL,
  adjustment_code varchar(64) NOT NULL,      -- e.g. CORROSION, AFTER_HOURS, FLEET_CONTRACT
  adjustment_type varchar(16) NOT NULL,      -- PERCENT | FIXED
  adjustment_value numeric(10,4) NOT NULL,
  sequence int NOT NULL,                     -- ordered application
  effective_from timestamptz NOT NULL, effective_to timestamptz NULL,
  created_at, updated_at
  UNIQUE (location_id, operation_category, adjustment_code, effective_from)
```

**Resolution rule:** most specific effective `labor_rate` wins —
`(location, category)` → `(location, null)` → `(null, category)` → `(null, null)`. Adjustments
whose `adjustment_code` the caller passes are applied in `sequence` order; `PERCENT` compounds
on the running rate, `FIXED` adds. The response itemises each step so an invoice can show why
the rate is what it is.

**API (pos-price):**
- `POST /v1/labor-rates`, `GET /v1/labor-rates` — authoring/listing (`price:labor_rate:manage`,
  `price:labor_rate:view`).
- `POST /v1/labor-rates/adjustments`, `GET /v1/labor-rates/adjustments` — the matrix.
- **Grant surface** `com.positivity.price.service.LaborRateService` +
  `POST /v1/labor-rates/quote` (`price:labor_rate:quote`), the scoped REST edge pos-workorder
  calls. This mirrors ADR-0058 §5 exactly, and needs its own **ADR-0044 amendment** plus the
  `DomainWallsTest` file-scoped exception for pos-workorder's client and a third entry in the
  pos-archunit grant census.
- Typed degradation, like the labor-time edge: a miss or an unreachable edge is a status
  (`NO_RATE_AVAILABLE`), never an exception — the writer types the price.

**Events:** `PRICE_LABOR_RATE_CREATE` / `_ADJUSTMENT_CREATE` (`write`), `_QUOTE` (`fastRead`),
`_LIST` (`fastRead`) in pos-price `EventTypes`.

**Fake data:** `R__seed_reference_price_labor_rates.sql` — a platform default rate, two
location rates, and a matrix (corrosion +15%, after-hours +25%, fleet contract −10%).

---

### T0-4 — Service packages and fleet requirements (pos-catalog)

**Schema — pos-catalog `V22__service_package.sql`:**

```
service_package
  id uuid PK, package_code varchar(64) NOT NULL UNIQUE, name varchar(255) NOT NULL,
  description text NULL,
  owner_scope varchar(16) NOT NULL DEFAULT 'PLATFORM',   -- PLATFORM | SHOP
  owner_location_id uuid NULL,
  fleet_party_id uuid NULL,          -- set = a fleet-specific requirement set
  package_labor_hours numeric(5,1) NULL,   -- authored, D4
  active boolean NOT NULL DEFAULT true,
  effective_from date NULL, effective_to date NULL,
  version bigint NOT NULL DEFAULT 0, created_at, updated_at
  CHECK owner scope/location consistency (as T0-2)

service_package_member
  id uuid PK, package_id uuid NOT NULL REFERENCES service_package(id) ON DELETE CASCADE,
  service_id uuid NOT NULL REFERENCES service(id),
  sequence int NOT NULL, quantity numeric(10,2) NOT NULL DEFAULT 1,
  required boolean NOT NULL DEFAULT true,
  created_at, updated_at
  UNIQUE (package_id, service_id)
```

**Fleet-specific requirements** are exactly a package with `fleet_party_id` set and
`required = true` members — "Fleet ACME: every visit includes a DOT brake check and a tread-depth
record". No separate table; `GET /v1/service-packages?fleetPartyId=…` is the query a workorder
flow asks.

**API:** `POST/GET/PUT /v1/service-packages`, `GET /v1/service-packages/{id}`,
`POST /v1/service-packages/{id}/members`, `DELETE …/members/{memberId}`.
**Permissions:** `catalog:service_package:manage`, `catalog:service_package:view`.
**Events:** `CATALOG_SERVICE_PACKAGE_CREATE/UPDATE/DELETE` (`write`), `_LIST/_GET` (`fastRead`).

**Fake data:** tire packages (4-tire install with balance and TPMS reset; road-force upgrade),
a maintenance package, and one fleet requirement set bound to a seeded commercial party id.

---

### T0-5 — Historical actual repair times (pos-workorder)

**Schema:** none. The inputs already exist: `workorder_service.guide_hours` (+ provenance,
V26) and `workorder_labor_entry.hours_worked`.

**Computation** (`LaborIntelligenceService`, pos-workorder): for completed service lines
grouped by `operation code × (make, model) × location`, produce

```
sampleCount, medianActualHours, meanActualHours, medianGuideHours,
varianceHours (= medianActual − medianGuide), variancePct,
suggestedStandardHours   -- medianActual, present only when sampleCount ≥ minSamples
```

`pos.workorder.labor-intelligence.min-samples` (default 5) gates the suggestion; below it the
row is returned with `suggestedStandardHours = null` so the caller can see the sample is thin
rather than being handed a number derived from one job.

Aggregation is done in Java over the fetched rows, not in SQL — same reasoning and same scale
note as the Phase-1 resolution path (`candidates load per service id, fine at reference-catalog
volume`), and it keeps the query portable across the profiles the test suite runs on.

**API:** `GET /v1/workorders/labor-intelligence/operations` with optional `operationCode`,
`make`, `model`, `locationId`, `minSamples`.
**Permission:** `workorder:labor_intelligence:view`.
**Event:** `WORKORDER_LABOR_INTELLIGENCE_LIST` (`search`).

**Non-promotion is enforced by omission:** this module writes nothing to pos-catalog and holds
no client to it beyond the existing read-only labor-time edge.

---

## 5. #1569 residual, unblocked by Tier 0

### R1 — Category-aware source precedence
`labor_time_source_policy` gains nullable `operation_category`; the unique key becomes
`(time_type, source_code, COALESCE(operation_category,''))`. Resolution loads the service's
category and prefers a policy row stating it over a category-less one.
*Sourcing plan Phase 3 item 2. Required by T0-1 (§3 D3).*

### R2 — Cross-source conflict surfacing
`GET /v1/catalog/labor-standards/conflicts?thresholdHours=0.3` — active rows for the same
`(service_id, vehicle key, time_type)` from different sources whose hours differ by more than
the threshold, for curation. Permission `catalog:labor_standard:view`.
*Sourcing plan Phase 3 item 3.*

### R3 — The full vehicle key and time-type preference reach the edge
`LaborTimeDefaultingService` today calls the edge with **year/make/model only**, discarding
submodel, engine and `preferredTimeType` — so the `EXACT` match grade is currently unreachable
from the estimate path and a warranty workorder cannot ask for `OEM_WARRANTY`. Widen
`CatalogLaborTimeClient.resolveLaborTime` to carry submodel, engine code, `locationId` (T0-2)
and `preferredTimeType`, and have the estimate path pass the workorder's warranty flag.
*Sourcing plan Phase 3 item 4 + the T0-2 dimension.*

### R4 — LABOR estimate lines default their unit price from the shop labor rate
With T0-3 in place, a `LABOR` estimate item naming a `serviceId` and omitting `unitPrice` gets
the resolved hourly rate (location + operation category, matrix applied), snapshotted with its
provenance next to the existing guide-hours snapshot. A writer's explicit price always wins,
and an unreachable rate edge leaves the field for the writer — same degradation contract as the
hours prefill. This closes the second half of the quote that #1569 flagged as hand-typed.

---

## 6. Out of scope (and why)

| Item | Why |
|---|---|
| Tire fitment data | #1575 qualifies it "where appropriately licensed"; it is the §9 mirror track with its own phasing. |
| Michelin procedure *content* | Documents and step content, not times — a document-management concern (pos-documents), separate from labor sourcing. |
| Licensed aggregator adapter (Tier 2) | Blocked on procurement + the §5.4 licensing checklist. Unchanged. |
| OEM direct access (Tier 1), diagnostics (Tier 3) | Licence-gated. |
| Per-location authorization of shop-scoped writes | T0-2 stores the owner; enforcing *which* location a user may write for is a security-domain concern (location-scoped authorities), tracked separately. Recorded as a known gap, not silently assumed. |
| Appointment duration from summed book time | pos-shop-manager consumer, unchanged non-goal. |
| Multi-source active-key collision (plan §7 Phase 2 item 4) | Still open: `ux_sls_active_key` has no `source_code`, so two STORE sources publishing the same key still collide. T0-2 widens the key by owner, not by source. Deliberately left for the Phase-2 scale pass. |

---

## 7. Verification

Every workstream must land with:

- Module quality build green — Spotless, Checkstyle, SpotBugs (`High`), unit tests.
- `./mvnw -pl pos-archunit -am -Dtest=ArchitectureTests test` green (grant census updated for
  the new pos-price grant).
- `openapi.yaml` regenerated for each touched module and ADR-0042 clean.
- Permission catalogs (`GatewayPermissionCatalog`, `DownstreamPermissionCatalog`,
  `PermissionCode`) updated in lockstep with version pins bumped; **bits appended, never
  renumbered** (renumbering invalidates every issued JWT bitset).
- New behavior covered by tests that fail without the change, not only by tests that pass with it.
- `API Artifacts Sync` run after push (regenerates specs, both SDKs, frontend tarballs).

**Permission bits** — next free is **510** (509 = `accounting:gl:reconcile`):

| Bit | Permission | Workstream |
|---|---|---|
| 510 | `price:labor_rate:manage` | T0-3 |
| 511 | `price:labor_rate:view` | T0-3 |
| 512 | `price:labor_rate:quote` | T0-3 (grant edge) |
| 513 | `catalog:service_package:manage` | T0-4 |
| 514 | `catalog:service_package:view` | T0-4 |
| 515 | `workorder:labor_intelligence:view` | T0-5 |

**Demo (end-to-end, fake data only):** create a workorder for a seeded fleet vehicle → add the
fleet requirement package → LABOR lines prefill hours from the Michelin/Durion Tier-0 standards
with the source shown, and prices from the shop's labor rate with the matrix itemised → the
summary shows overlap-adjusted `estimatedLaborHours` → clock actuals → variance renders → the
labor-intelligence endpoint returns a `suggestedStandardHours` candidate once the sample
threshold is met.

---

## 8. Traceability

| Source | Requirement | Satisfied by |
|---|---|---|
| #1575 Tier 0 | Tire service operations | T0-1 |
| #1575 Tier 0 | Michelin-specific procedures | T0-1 (times); §6 (content) |
| #1575 Tier 0 | Fleet-specific requirements | T0-4 |
| #1575 Tier 0 | Dealer-created labor operations | T0-2 (+ D2 narrowing) |
| #1575 Tier 0 | Shop labor rates | T0-3 |
| #1575 Tier 0 | Service packages | T0-4 |
| #1575 Tier 0 | Historical actual repair times | T0-5 |
| #1575 Tier 0 | Shop-specific pricing rules | T0-3 (matrix) |
| #1575 Tier 0 | Tire fitment data | §6 deferred |
| #1575 Architecture | Keep the paid provider replaceable | Unchanged — `LaborTimeProviderPort`; Tier 0 adds sources, not coupling |
| #1569 scope 1–7 | — | Shipped in Phases 0–1 |
| Plan Phase 3 item 2 | Category precedence | R1 |
| Plan Phase 3 item 3 | Conflict surfacing | R2 |
| Plan Phase 3 item 4 | Warranty vs retail on resolve | R3 |
| Plan Phase 4 | Labor intelligence | T0-5 |
| #1569 "adjacent" | Labor rate in pos-price | T0-3 / R4 |
| #1569 "adjacent" | Shop labor matrix | T0-3 |

---

## 9. What the build changed about this specification

Recorded rather than quietly amended, because each was a decision the code forced.

### R2 could not be built as specified

The spec (and the sourcing plan before it) described a conflict as *same operation, same vehicle,
different sources*. That comparison cannot fire: `ux_sls_active_key` covers
`(service, time_type, owner, vehicle key)` with **no source column**, so two STORE sources cannot
hold the same time type at the same vehicle key at all — the second import's insert collides.
An endpoint built to the letter of the spec would have returned empty forever.

What sources actually do is publish at different levels of specificity — a tyre manufacturer
states one wildcard time while an aggregator states a time per year/make/model — and *those*
disagree invisibly. So the implemented rule is **overlapping** vehicle keys: every field one row
states, the other either states identically or leaves wild. Time types are never compared across
each other; warranty time is meant to differ from retail.

### R3 shipped only its location half

`locationId` now reaches the resolve edge, which is what lets Tier 0's shop-owned times answer.
Submodel, engine code and `preferredTimeType` are still sent null, and the client says so at the
call site: the CRM vehicle record carries year/make/model and nothing finer, and no workorder or
estimate flags warranty work. Sending invented values would widen nothing and risk a wrongly
`EXACT`-graded answer.

**Consequence:** the `EXACT` match grade remains unreachable from the estimate path, and a
warranty workorder still cannot ask for `OEM_WARRANTY`. Both need a real upstream source first —
vehicle trim/engine on the CRM record, and a warranty flag on the workorder.

### T0-5 groups by shop and technician, not vehicle class

For the same reason: `ExtVehicleReplica` carries VIN, plate, unit number and odometer, and no
make or model. This turns out to match #1575's own Tier 4 sketch, which names industry book time,
shop median and technician median — not a vehicle-class median.

### D2's narrowing is visible in T0-4 as well

Service packages carry the same `owner_scope` / `owner_location_id` pairing as labor standards, so
a shop may own a package. The *operation taxonomy* stays global in both cases.

### Permission bits actually used

| Bit | Permission | Catalog version |
|---|---|---|
| 510–512 | `pricing:labor_rate:manage` / `:view` / `:quote` | 77 |
| 513–514 | `catalog:service_package:manage` / `:view` | 78 |
| 515 | `workorder:labor_intelligence:view` | 79 |

`workorder:labor_intelligence:view` is deliberately its own permission rather than the shared
`workorder:analytics:view`: it exposes individual technician productivity, and folding that into
a general analytics grant would hand it to everyone holding the first.

### Still open after this build

- The Phase 2 active-key gap (`ux_sls_active_key` has no `source_code`) — R2's report is fuller
  than the data can currently exercise, and becomes fully operative when that key widens.
- `EXACT` match grades and warranty time preference from the estimate path (above).
- Tier 2 licensing, and everything in §6.
- SDK regeneration: run `API Artifacts Sync` for the three changed specs (`pos-catalog`,
  `pos-price`, `pos-workorder`) so both SDKs and the frontend tarballs pick up the new surface.
