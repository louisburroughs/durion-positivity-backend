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
