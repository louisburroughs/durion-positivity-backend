# pos-catalog

Product catalog service for the Durion Positivity ETSMS platform. Manages the product master, price books, unit-of-measure conversions, supplier item costs, MSRP records, location-level price overrides, and product lifecycle transitions.

## Responsibilities

- Maintain product master data (inventory and non-inventory products)
- Own product stock-attribute contract data for pos-inventory (#1023): per-product UoM conversion sets (purchase/pack UoM → base UoM factor + precision scale), stock tracking level (`NONE`/`LOT`/`SERIAL`), and substitution groups of interchangeable products
- Manage price books and associated pricing rules
- Handle unit-of-measure conversions between stocking and selling units
- Track supplier item costs and MSRP records per product
- Apply and resolve location-level price overrides
- Control product lifecycle (active, discontinued, archived)
- Search and filter products with Caffeine-backed caching
- Support bulk product import via `POST /v1/catalog/bulk-ingest`

## Key Classes

- `CatalogService` — product CRUD and catalogue-level operations
- `ProductDetailService` — assembles full product detail view including pricing and costs
- `PriceBookService` — price book lifecycle and rule management
- `ProductSearchService` — paginated product search with filtering
- `ItemCostService` — supplier item cost tracking and audit
- `ProductLifecycleService` — state transitions with guardrail policy enforcement

## API Endpoints

- `GET /v1/catalog/{productId}` — retrieve a product
- `GET /v1/catalog/{productId}/detail` — full product detail including pricing
- `POST /v1/catalog` — create a product
- `DELETE /v1/catalog/{catalogId}` — archive a product
- `GET /v1/catalog/{itemId}/costs` — list item costs
- `GET /v1/catalog/{itemId}/costs/audit` — cost audit trail
- `GET /v1/catalog/pricing/effective-price/{locationId}/{productId}` — effective price at a location
- `GET /v1/catalog/{priceBookId}` — retrieve a price book
- `POST /v1/catalog/bulk-ingest` — bulk import products (auth: `catalog:product:create`)
- `PUT /v1/products/{productId}/tracking-level` — set stock tracking level (`NONE`/`LOT`/`SERIAL`)
- `POST|GET /v1/products/{productId}/uoms`, `PUT|DELETE /v1/products/{productId}/uoms/{uomId}` — per-product UoM conversion set
- `POST|GET /v1/products/substitution-groups`, `GET|DELETE /.../{groupId}`, `POST /.../{groupId}/members`, `DELETE /.../{groupId}/members/{productId}` — substitution groups (a product belongs to at most one group)

## Domain Events

Product mutations queue a `catalog.product.updated` fact (payload `ProductUpdatedV1`, schema version 2) on `catalog.events.v1` via the transactional outbox (`pos.catalog.kafka.enabled`), reconciled hourly on `catalog.manifest.v1`. Schema version 2 (#1023, additive) added `baseUom`, `trackingLevel`, `uomConversions[]` (`uomCode`, `uomType`, `factorToBase`, `precisionScale`), `substitutionGroupId`, and `substitutionProductIds[]` so pos-inventory can replicate UoM conversions, tracking level, and substitution membership (`ext_product_uom` et al.). UoM and substitution-membership mutations bump the product's `updatedAt` (the envelope `aggregateVersion`) and re-emit the fact for every affected product.

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
./mvnw -pl pos-catalog -am spring-boot:run
```
