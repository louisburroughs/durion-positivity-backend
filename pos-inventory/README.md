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
- `GET /v1/inventory/shortage/options` — list shortage resolution options
- `POST /v1/inventory/shortage/resolve` — resolve shortage with selected strategy
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

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |

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
