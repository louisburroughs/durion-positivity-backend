# pos-inventory

Inventory management service for the Durion Positivity ETSMS platform. Manages stock levels, receiving and putaway workflows, pick lists, cycle counting, purchase orders, ASNs, replenishment, reservations, allocation, shortages, and returns.

## Responsibilities

- Receive goods against ASNs and purchase orders; generate putaway tasks
- Manage storage locations and bin-level inventory inquiry
- Track stock movements and maintain real-time availability
- Generate and execute pick lists for workorder part fulfillment
- Run cycle count plans and process variance adjustments
- Drive replenishment recommendations and purchase order creation
- Handle back-order reservations and allocation/reallocation
- Ingest manufacturer and distributor stock feeds
- Support bulk inventory import via `POST /v1/inventory/bulk-ingest`

## Key Classes

- `ReceivingService` — goods receipt against ASN/PO; triggers putaway generation
- `PickListGenerationService` — creates pick lists for workorder demand
- `CycleCountService` — manages cycle count sessions and variance adjustments
- `InventoryAvailabilityService` — real-time available-to-promise queries
- `ReservationService` — reserves inventory against workorder demand
- `PurchaseOrderService` — PO lifecycle and vendor communication

## API Endpoints

- `GET /v1/inventory/{locationId}/inventory-inquiry` — location-level stock inquiry
- `GET /v1/inventory/{productId}` — product stock summary
- `GET /v1/inventory/ledger` — paged inventory ledger query with filter params
- `GET /v1/inventory/ledger/{entryId}` — fetch a single inventory ledger entry
- `GET /v1/inventory/asns/{asnId}` — retrieve an ASN
- `GET /v1/inventory/goods-receipts/{receiptId}` — retrieve a goods receipt
- `GET /v1/inventory/{poId}` — retrieve a purchase order
- `GET /v1/inventory/{pickListId}` — retrieve a pick list
- `GET /v1/inventory/{pickListId}/tasks` — pick list task detail
- `GET /v1/inventory/{planId}` — retrieve a cycle count plan
- `GET /v1/inventory/lead-time` — supplier lead time data
- `GET /v1/inventory/policies` — replenishment policies
- `GET /v1/inventory/returns/returnable-items` — returnable item lookup for a workorder
- `GET /v1/inventory/returns/reason-codes` — return reason code catalog
- `POST /v1/inventory/returns/submit-to-stock` — submit return lines to stock (async accepted)
- `GET /v1/inventory/shortage/options` — compute shortage resolution options (BACKORDER, SUBSTITUTE, TRANSFER_IN, EMERGENCY_PURCHASE, CANCEL_LINE), each with an expected-resolution date and cost delta where computable (params: `allocationId`, `sku`, `shortQuantity`, optional `workorderLineId`, `locationId`)
- `POST /v1/inventory/shortage/resolve` — execute the chosen option atomically, creating the backing artifact (backorder / substitute reservation / transfer order / purchase suggestion); requires an `idempotencyKey` (retry-safe)
- `GET /v1/inventory/backorders` — list backorders (filters: status, sku, location, workorderLine)
- `GET /v1/inventory/backorders/{backorderId}` — retrieve a backorder
- `GET /v1/inventory/locations` — paged location reference data
- `GET /v1/inventory/storage-locations` — paged storage-location reference data
- `GET /v1/inventory/location-zones` — paged location-zone reference data
- `POST /v1/inventory/bulk-ingest` — bulk import inventory records
- `GET /v1/inventory/sourcing-strategies` — list sourcing strategy configuration rows
- `PUT /v1/inventory/sourcing-strategies` — upsert the sourcing strategy for one scope
- `DELETE /v1/inventory/sourcing-strategies/{configId}` — deactivate a sourcing strategy configuration
- `GET /v1/inventory/lots` — list lot master records (filters: stockItemId, status, lotNumber)
- `GET /v1/inventory/lots/{lotId}` — lot details with per-location on-hand from the per-lot summary rows

## Lot Tracking — Inbound Capture (odoo-parity E1)

Products whose catalog replica (`ext_product.tracking_level`) says `LOT` require a `lotNumber`
on every inbound receipt line (goods receipt, receive-into-staging, PO receive); a missing lot
number is a deterministic `422 LOT_NUMBER_REQUIRED`. The lot is found-or-created per
(stockItemId, lotNumber) in `inventory_lot` (receivedAt/vendorId stamped on first sight) and
its id is stamped on the receipt's ledger entries (`inventory_ledger_entry.lot_id`).

The stock summary uses dual-row bookkeeping: every posting updates the lot-agnostic
(`lot_id IS NULL`) row exactly as before — that row remains what all availability, rollup, and
forecast readers consume — and a lot-tagged posting additionally applies the same deltas to a
per-lot row keyed `(stock_item_id, location_id, lot_id)` (unique `NULLS NOT DISTINCT`), from
which the lot read API serves per-lot on-hand. Rebuild and drift verification replay the
identical rule.

Untracked products (tracking level `NONE`, unknown products, free-text SKUs) see zero behavior
change: no validation, `lot_id` null everywhere, a single summary row. `SERIAL` is treated as
`NONE` until parity-E4; outbound lot stamping (picks, consumption, transfers, scraps — and the
cross-dock receipt+issue pair) is parity-E2. Expiry (`expiration_date`) is populated by the
parity-E3 flows.

## Sourcing Strategy Engine (odoo-parity H1/H2)

`SourcingStrategyService` (internal) orders candidate locations for a SKU per a configured
removal/sourcing strategy — consumed by consumption allocation-close ordering, pick-task
location suggestion, and (from parity-F5) replenishment source selection via
`selectSource(selection, neededQuantity)`.

- **Strategies**: `FIFO` (earliest `GOODS_RECEIPT`/`TRANSFER_IN` ledger timestamp per location —
  a documented per-location approximation), `FEFO` (earliest lot expiry via the
  `LotExpiryProvider` SPI; falls back to FIFO until the lot stories E2/E3 register a real
  provider), `PROXIMITY` (hop distance to a reference location, BFS over
  `ext_storage_location` parent links plus `ext_location_parent` edges; falls back to FIFO
  without a reference), `HIGHEST_STOCK` (most `onHand - allocated` first). All orderings
  tie-break by ascending location id. LIFO and least-packages are explicit non-goals.
- **Resolution**: active `SKU_CATEGORY` config (skipped while the catalog replica carries no
  category — `SkuCategoryProvider` SPI) → active `SITE` config → active `DEFAULT` config →
  platform default FIFO. Configuration lives in `sourcing_strategy_config` (V17), administered
  via the `sourcing-strategies` endpoints (`inventory:location:admin`).
- **Audit**: the effective strategy is recorded as `sourcingReason` on pick tasks
  (and, from F5, replenishment tasks) so ops can answer "why this bin".

## Counter-sale consumption (order parity H2)

When `pos.inventory.kafka.enabled` is on, `OrderEventsListener` consumes
`order.order.completed` and posts a `GOODS_ISSUE` ledger movement per fulfillable line
(`stockItemId` = the line's SKU, at the order's shop location; WORKORDER-sourced lines never
move stock — spec R7.5). Each line posts in its own transaction: a rejected post (insufficient
stock, unknown item) raises `inventory.counter-sale.consumption-failed` on
`inventory.events.v1` and never affects the completed sale.

## Supplier availability hints (CAP-322, #1312)

`SupplierStockHintEventsListener` consumes `supplier.stockreport.updated` from
`supplier.events.v1` (producer #1228) into `supplier_stock_hint` — one row per
`(vendorProfileId, article identity)` holding what that vendor last said about its own stock.

**This is not owned stock.** It never enters valuation (ADR-0048), it is never part of on-hand
ATP, and it never satisfies a gate on committing stock. The guarantee is structural rather than
procedural: the hints live in their own tables that no valuation or ATP query joins, and two
ArchUnit rules in `ArchitectureTest` hold the repository to the supplier-hint classes.

What the feed's shape forces, and how each is handled:

- **No terminal event, no republish command, and an empty snapshot publishes nothing.**
  Completeness is in-band only: `supplier_stock_snapshot_chunk` logs the sequences that arrived
  and `supplier_stock_snapshot_receipt` compares the count against the `chunkCount` every chunk
  states. Incomplete is the resting state, so a lost chunk needs no timer to be noticed and every
  hint from that snapshot reads as `snapshotComplete: false`.
- **Three-way quantity distinction.** A stated quantity (`0` included — an explicit "we have
  none"), a `NULL` quantity (the vendor listed the article and stated no quantity), and no row at
  all (the vendor never mentioned it). A snapshot that omits a previously reported article leaves
  its hint standing with its own `asOf`; a vendor's silence never reads as an out-of-stock.
- **Two timestamps.** `snapshotAsOf` is the vendor's own figure and is nullable; `fetchedAt` is
  when we asked. Freshness is judged on the vendor's figure where there is one, and `asOfSource`
  says which is in play. Supersession is ordered on `fetchedAt`, the one instant that always
  exists.
- **Staleness ceiling.** Past `pos.inventory.supplier-hints.staleness-ceiling` (per-vendor
  override available) a hint reads as `STALE_UNKNOWN` with its quantity suppressed — never as
  zero.
- **Resolution is out of band, against a local replica.** `SupplierStockHintResolver` sweeps
  `PENDING` hints against `ext_product_code` — this module's own copy of pos-catalog's product
  identity codes, maintained by `CatalogEventsListener` from `catalog.product.updated` facts.
  Never a synchronous call to pos-catalog: ADR-0044 R1 forbids it and R3 makes the replica the
  sanctioned read path, and pos-supplier resolves PRICAT lines the same way against its own copy
  (CAP-318 #1224). Only EAN is matched; vendor and buyer article codes carry no uniqueness
  guarantee and are never guessed at. Unresolved hints are retained and remain readable by code,
  and an unseeded replica defers rather than reporting every hint as a catalog miss.

Read path: `GET /v1/inventory/supplierStockHints/byProduct/{productId}` and
`GET /v1/inventory/supplierStockHints/byCode` (`inventory:supplier_stock_hint:view`). Results are
per vendor and never aggregated.

Not in this module: publishing hints onward as an `inventory.supplier-availability.updated` fact
for estimates. That waits on resolution being settled — see #1312.

## Decimal quantities, gated by the product's declaration (ADR-0055, #1414)

The ledger and everything derived from it carry `numeric(19,4)` / `BigDecimal` quantities:
`inventory_ledger_entry.change_in_quantity` and `quantity_after`, the reservation / allocation /
backorder chain, the `inventory_stock_summary` read model, the costing state, cycle-count
variances, returns, manual adjustments and shortage records.

**Widening the columns did not make stock divisible.** A product's divisibility is declared by the
catalog as the `precision_scale` of its `BASE` row in `product_uom`, replicated here as
`ext_product_uom`. Scale `0` — and equally a product with no unit-of-measure rows, which is every
product until seeding lands — means whole units and is still refused a fraction. A non-zero scale
permits that many decimal places and no more. `UomConversionService.declaredBaseScale` answers the
question; `QuantityScaleGuard` enforces it.

Enforcement is symmetric. pos-workorder gates the demand side at estimate-item entry and part issue
(#1413); this module gates the supply side and both raise HTTP 422
`FRACTIONAL_QUANTITY_NOT_ALLOWED` from the same declaration. The guard took over the three
`intValueExact()` calls in receiving, ASN and returns — keeping their fail-closed character,
losing their hardcoding — and manual stock movements, which post to the ledger just as directly,
gained the same gate.

On the read side, the `Math.toIntExact` calls that used to narrow the availability math are
replaced by the same scale check (`QuantityScaleGuard.requireReportable`). Those calls threw rather
than wrapping, and that fail-loud property is preserved: a stored quantity carrying more precision
than the product declared stops the computation instead of being reported as though it were fine.
The check only runs when the ledger's `stockItemId` names a catalog product. The ledger's posting
paths disagree about whether that column holds a product UUID or a human SKU, and a reference that
names no product declares nothing — which cannot be evidence that a value already in the ledger is
wrong. Such a row is reported as stored rather than refused; refusing would turn an availability
read into a 500 and stall the replicas behind it.

**Still integral, deliberately:** pick-task quantities, putaway and transfer-order line quantities,
and the cycle-count capture DTOs (`SubmitCountRequest.actualQuantity`, `CountEntryResponse`). These
widen with the unit-of-measure work in ADR-0055 stages 3 and 4. Where they meet the ledger they
widen at the boundary (`BigDecimal.valueOf`), never narrow it. Sales-order line quantities stay
`int` by decision — see ADR-0055.

Compare these quantities with `compareTo`, never `equals`: PostgreSQL returns `numeric(19,4)` at
scale 4, so a stored `4.0000` and a computed `4` are the same quantity and are not equal.

### Rollout: breaking on the wire, deployed in lockstep

`InventoryAvailabilityUpdatedV1`, `ReservationOutcomeV1`, `BackorderCreatedV1`,
`BackorderResolvedV1`, `ProductValueChangedV1` and `StorageLocationOnHandUpdatedV1` changed their
quantity fields from `int`/`long` to `BigDecimal`. That is a **breaking payload change** for every
consumer: the `ext_inventory_availability` replicas in pos-order, pos-workorder and pos-catalog, and
the `ext_storage_location_on_hand` replica in pos-location.

**No dual-read shim was added, deliberately.** The platform is pre-production with no live data, and
every producer and consumer of these topics ships from this one Maven reactor, so there is no
version skew to bridge — only a deployment ordering to respect. The repository's pre-production
policy is explicit that clean code beats compatibility scaffolding, and a versioned-claim or
dual-read path here would be scaffolding for a skew that cannot occur.

What that costs is a constraint on the rollout, and it is stated rather than mitigated:

- **Deploy the fleet together.** pos-inventory, pos-order, pos-workorder, pos-catalog and
  pos-location must go out in the same release. A consumer running the old code against a new
  payload rejects it as a databind failure (counted on `replica.payload.rejected`) rather than
  landing a wrong number — loud, not silent — but the replica stops advancing until it is upgraded.
- **Run the migrations first.** Each module's widening migration (`V39` here; `V21`, `V18`, `V13`,
  `V5` in pos-order, pos-workorder, pos-catalog and pos-location) is a pure `ALTER … TYPE`
  widening. `numeric(19,4)` holds every value the integer columns could, so the implicit cast
  preserves existing rows exactly and needs no data-preservation logic.
- **Drain or accept a stalled topic.** In-flight events published under the old shape deserialize
  with the three core availability quantities missing, which the record constructor rejects. The
  forecast triple defaults to zero as it always did for schema-v1 payloads.

## Configuration

| Property                                             | Default  | Description                                            |
| ---------------------------------------------------- | -------- | ------------------------------------------------------ |
| `SPRING_DATASOURCE_URL`                              | required | PostgreSQL connection URL                              |
| `EUREKA_SERVER_URL`                                  | required | Eureka service discovery URL                           |
| `POS_INVENTORY_SUPPLIER_HINT_STALENESS_CEILING`      | `PT24H`  | Age past which a supplier hint reads as unknown        |
| `POS_INVENTORY_SUPPLIER_HINT_RESOLUTION_ENABLED`     | `false`  | Run the EAN resolution sweep against pos-catalog       |
| `POS_INVENTORY_SUPPLIER_HINT_RESOLUTION_BATCH_SIZE`  | `200`    | Hints resolved per pass                                |

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-shared-dtos` — shared DTOs
- `pos-bulk-ingest-lib` — bulk-ingest base controller

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-inventory -am spring-boot:run
```
