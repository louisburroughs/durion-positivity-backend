# pos-workorder

Core workorder service for the Durion Positivity ETSMS platform. Manages the full workorder lifecycle from estimate creation through WIP execution, technician time tracking, parts usage, invoice generation, and completion. Integrates with the customer, vehicle, tax, and invoice services.

## Responsibilities

- Create and manage workorders with state machine transitions (estimate, WIP, complete, cancelled)
- Build estimates from appointments with line item services and parts
- Track WIP status and dashboard summaries per shop
- Assign and reassign technicians to workorder service lines
- Record labor time entries and work sessions for payroll and billing
- Manage part usage, part substitutions, and part pick coordination
- Apply and validate promotional offers on workorder lines
- Generate invoices at completion by calling `pos-invoice`
- Calculate tax on estimate totals via `pos-tax`, resolving the estimate's shop-location address from `pos-location` as the tax jurisdiction
- Emit Kafka events for cross-service consumption (configurable; off by default)

## Key Classes

- `WorkorderService` — workorder lifecycle: create, update status, retrieve, cancel
- `EstimateService` — estimate creation, item management, and appointment conversion
- `WipService` — WIP board state and job-time totals
- `WorkorderLaborService` — labor line management on workorder service lines
- `WorkorderPartUsageService` — part consumption recording and adjustments
- `WorkorderInvoiceService` — invokes `pos-invoice` to generate an invoice at close
- `TechnicianAssignmentService` — assign and reassign technicians
- `DashboardService` — aggregated shop dashboard data (bays and mobile units, see below)
- `TaxClient` — outbound client for `pos-tax`; forwards `X-User: pos-workorder` and `X-Authorities: tax:calculate` on the tax-calculate call so the request satisfies `tax:calculate` enforcement (matching `pos-invoice`'s `TaxServiceClient`)

## API Endpoints

- `POST /v1/workorders` — create a workorder
- `GET /v1/workorders/{workorderId}` — retrieve a workorder
- `DELETE /v1/workorders/{workorderId}` — cancel a workorder
- `GET /v1/workorders/customer/{customerId}` — workorders for a customer
- `GET /v1/workorders/location/{locationId}` — workorders for a location
- `POST /v1/estimates` — create an estimate
- `GET /v1/estimates/{estimateId}` — retrieve an estimate
- `GET /v1/estimates/{estimateId}/summary` — estimate summary
- `DELETE /v1/estimates/{estimateId}/items/{itemId}` — remove an estimate line
- `GET /v1/workorders/wip` — WIP board summary
- `GET /v1/workorders/job-time-totals` — job time totals for WIP
- `GET /v1/workorders/pick-list` — pick list for parts
- `GET /v1/workorders/picked-items` — picked item status
- `POST /v1/workexec/time-tracking` — submit labor time entry
- `GET /v1/workexec/adjustments` — time entry adjustments
- `POST /v1/workorders/{workorderId}/notes` — record a note about the customer
- `GET /v1/workorders/{workorderId}/notes` — the workorder's customer notes

## Estimate/workorder snapshot facts (order parity E1)

`WorkorderFactPublisher` snapshots now carry `declined` and the new explicit `returnable`
flag per part line (resolved order-spec Q6 — set at settlement time, never inferred). A new
`EstimateFactPublisher` emits `workorder.estimate.updated` snapshots (header + full item set
with approval status) on every estimate mutation, feeding pos-order's source-document import
replicas. Both are gated by `workorder.kafka.enabled`.

## Estimated labor hours and guide-time defaulting (#1569)

A LABOR estimate item naming a `serviceId` asks the catalog labor guide for its book time via
`CatalogLaborTimeClientImpl` — the module's one granted synchronous edge to pos-catalog
(ADR-0044 amendment 2026-09-02, file-scoped). The guide answer is always snapshotted onto the
line (`guide_hours` + source/revision/match-grade/overlap metadata) and becomes the `quantity`
only when the writer omitted it: a prefill, never a lock, and `quantity` remains the agreed
hours. The vehicle key comes from `VehicleReferenceService` (CRM year/make/model, fail-soft).
When the edge cannot answer, the `ext_catalog_service` replica's vehicle-agnostic
`default_labor_hours` (fed by `catalog.service.updated` schema v2) prefills instead; failing
that the writer types the hours — estimating never blocks on a guide. Promotion carries the
snapshot onto `workorder_service`, and `EstimatedLaborService` computes the overlap-aware
`estimatedLaborHours` (included operations contribute zero; overlap-group lines contribute
max + `pos.workorder.labor.overlap-additional-factor` × the rest) for the dashboard summary
and the detail response, which also exposes `actualLaborHours` and the labor variance.

## Customer notes on a workorder (#1584)

A note about the customer — something they said while the job was open, not a note about the work —
is recorded through `POST /v1/workorders/{workorderId}/notes` and stored in `workorder_note`, which
this module owns. `WorkorderNoteServiceImpl` publishes `workorder.note.added.v1` to the
transactional outbox in the same transaction, so the note and its fact commit together;
pos-customer projects it onto the party's CRM timeline. Gated by `workorder.kafka.enabled` like the
other fact publishers: with Kafka off the note is still saved, it just is not published.

This is distinct from `workorder.completion_notes`, `workorder.approval_notes`, and
`change_request.approval_note`, which describe the work or a decision about it and are
single-valued.

## Part quantity divisibility (ADR-0055)

A part quantity must be a whole number unless the product it references declares otherwise. The
declaration is `product_uom.precision_scale` on the product's `BASE` row, owned by `pos-catalog`
and replicated here as `ext_product_uom` from `catalog.product.updated` facts (ADR-0044 §6). Scale
`0` — and equally, a product with no unit-of-measure rows, which is every product until seeding
lands — means whole units; a non-zero scale permits that many decimal places.

Enforced at estimate-item creation and update, at estimate-to-workorder promotion, and again on the
issue, consume, return and quantity-correction paths. Parts carrying no `productEntityId` (labour,
shop supplies, non-stocked consumables) are exempt and stay fractional. A violation returns HTTP
422 with `code: FRACTIONAL_QUANTITY_NOT_ALLOWED`, a `quantity` field error, and a `nextAction`
naming the quantity to enter instead.

### Unit of measure on part lines (ADR-0055 stage 3)

`estimate_item` and `workorder_part` carry a nullable `uom_code` column: the unit the line's
quantity is expressed in. **Null means the product's base unit** — today's implicit assumption,
and the default for every row that predates this column. `uomCode` is optional on
`AddEstimateItemRequest`, `UpdateEstimateItemRequest`, `IssuePartRequest`, `ConsumePartRequest`,
`ReturnPartRequest` and `CorrectPartQuantityRequest`. It is snapshotted from the estimate item onto
the promoted `workorder_part` the same way `quantity` itself is snapshotted.

**LABOR rows always carry a null `uomCode`.** `estimate_item.quantity` is shared between PART and
LABOR, but hours are not a catalog unit of measure and have no `product_uom` conversion row to
convert from. A non-null `uomCode` on a LABOR row is rejected with HTTP 400 at both add and update
time — checked in the service layer and enforced by a database check constraint
(`ck_estimate_item_labor_uom_null`) so it cannot be bypassed by a write that skips it.

When a line's `uomCode` differs from the product's base unit, the quantity is converted to base
via the `ext_product_uom` replica's `factor_to_base` — unrounded, so the divisibility check above
sees the true converted value rather than one silently rounded to fit — before the existing
`precision_scale` gate runs. A `uomCode` with no conversion row for the product returns HTTP 422
with `code: UOM_CONVERSION_UNDEFINED`, never a silent 1:1 assumption.

Issuing a part sends `uomCode` through unconverted on the `inventory.reservation.request-requested`
Kafka command; pos-inventory owns the actual document-to-base conversion for the reservation, using
`DOWN` rounding so it never promises more than exists — the same pattern purchase-order, ASN,
receiving and return lines already use via `DocumentQuantityConverter`.

**Read-side display (ADR-0055 stage 4, #1416):** `WorkorderPartResponse.unitOfMeasure` echoes the
line's own `uomCode` verbatim (null means the product's base unit) — no conversion, no catalog
lookup, just the same value the line was keyed in.

## Dispatch board: bays and mobile units (#1656)

`GET /v1/workexec/dashboard/today` returns `bays[]` **and** `mobileUnits[]`. They are separate
arrays because pos-location owns bays and mobile units as separate aggregates with separate
identity and lifecycle; `MobileUnitStatus` mirrors `BayStatus` field-for-field
(`unitId`/`unitName` in place of `bayId`/`bayName`) so the board renders both panels the same way.

`Workorder.resource_type` (`BAY` | `MOBILE_UNIT`, added by V27) is what tells the two apart. It
rides the assignment chain `AssignmentUpdatePayload` → `AssignmentUpdatedEvent` → `Workorder`.
The field is **optional inbound**: pos-shop-manager does not publish it yet, and an assignment that
arrives without it is applied as `BAY` — the meaning every assignment had before mobile units were
representable. V27 backfills existing assigned rows the same way. `AssignmentUpdatedEvent`
`resolveResourceType()` is the single place that fallback happens.

Resource identity comes from the `ext_bay` and `ext_mobile_unit` replicas (V28), fed by
`location.bay.*` / `location.mobile-unit.*` facts on `location.events.v1` per ADR-0044 §6 — no
synchronous call into pos-location and no cross-schema read. This is why `BayStatus.bayName` is
populated at all: it was declared-but-always-null until a replica existed to resolve it from.

Both panels list **every active unit at the location** (bays by `location_id`, units by
`base_location_id`), including units holding no work, which report `assignedWorkorderId: null`.
A unit reads as occupied only while its assigned workorder is open — `Workorder.isLocked()` is the
sole authority (`CANCELLED`, or `COMPLETED` and not reopened), so a reopened completed workorder
keeps its resource rather than being wrongly released.

Two edge behaviours are deliberate and live in `DashboardServiceImpl.buildResourcePanel`:

- **Unknown or inactive resource still holding open work** — the row is rendered anyway (name from
  the replica if the row exists, otherwise null). Hiding it would make live work invisible on the
  board. The lifecycle question "may a decommissioned unit hold open work at all?" belongs to
  pos-location and is an open follow-up.
- **Replica lag** — when an assignment fact overtakes the resource's own fact, the row appears with
  its id and a null name rather than being dropped.

Upstream dependency: pos-location does not publish bay or mobile-unit facts yet, so the replicas
start empty and the listener branches are never taken in production until it does. The consumer
tolerates that by design.

## Configuration

| Property                       | Default                    | Description                      |
| ------------------------------ | -------------------------- | -------------------------------- |
| `SPRING_DATASOURCE_URL`        | required                   | PostgreSQL connection URL        |
| `EUREKA_SERVER_URL`            | required                   | Eureka service discovery URL     |
| `pos.customer.base-url`        | `http://pos-customer:8084` | Customer service URL             |
| `pos.vehicle.base-url`         | `http://pos-vehicle:8088`  | Vehicle service URL              |
| `pos.tax.base-url`             | `http://pos-tax:8091`      | Tax service URL                  |
| `pos.location.base-url`        | `http://pos-location:8080` | Location service URL             |
| `workorder.kafka.enabled`      | `false`                    | Enable Kafka event emission      |
| `workorder.kafka.events-topic` | `workorder.events.v1`      | Kafka topic for workorder events |
| `workorder.kafka.catalog-events-topic` | `catalog.events.v1` | Catalog fact topic feeding the `ext_product_uom` replica |
| `workorder.kafka.catalog-events-consumer-group` | `pos-workorder-catalog-events` | Consumer group for the catalog fact topic |
| `workorder.kafka.location-events-topic` | `location.events.v1` | Location fact topic feeding the `ext_location`, `ext_bay` and `ext_mobile_unit` replicas |
| `workorder.kafka.location-events-consumer-group` | `pos-workorder-location-events` | Consumer group for the location fact topic |

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-shared-dtos` — invoice generation request DTOs
- `pos-tax-common` — tax calculation request/response types

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-workorder -am spring-boot:run
```
