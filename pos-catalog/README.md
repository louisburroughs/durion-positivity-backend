# pos-catalog

Product catalog service for the Durion Positivity ETSMS platform. Manages the product master, unit-of-measure conversions, supplier item costs, product lifecycle transitions, and the platform's **reference pricing data**: price books, MSRP records, and location-level reference price overrides (see the sell-price boundary below — transactional pricing is pos-price's).

## Sell-Price Boundary (ADR-0054)

pos-catalog's pricing surface is **list/MSRP reference data only**: price books, MSRP history,
list prices, and reference series (including PRICAT suggested retail per ADR-0053 §4) are
displayable and reportable, but **never the source of a transactional price**. Every
transactional sell-price resolution (quotes, workorder/estimate pricing, checkout) is owned
exclusively by pos-price. Catalog customer-tier price books define **reference/list prices for a
tier**; pos-price customer-tier *discounts* are the applied transactional mechanism — the two are
never competing resolvers.

`supplier_item_cost`'s successor — the append-only supplier price entries of ADR-0053 §2 — is
**cost input**, also outside sell-price resolution: neither the pos-price quote chain nor the
catalog price-book resolver reads it.

### Which module owns a price fact?

| Price fact | Owning module | Governing ADR |
| --- | --- | --- |
| Transactional sell price (quote, workorder/estimate, checkout) | pos-price | ADR-0054 §1 |
| Base price (effective-dated, history-retaining) | pos-price | ADR-0054 §4 |
| Location price override (transactional) | pos-price | ADR-0054 §1 |
| Customer-tier discount (applied transactional mechanism) | pos-price | ADR-0054 §3 |
| MSRP / list reference price | pos-catalog | ADR-0054 §1 |
| Customer-tier reference books (tier list prices) | pos-catalog | ADR-0054 §3 |
| Supplier cost (PRICAT supplier price entries — cost input, outside sell-price resolution) | pos-catalog | ADR-0053 §2 |
| Inventory valuation cost | pos-inventory | ADR-0048 |
| PRICAT suggested retail (reference series) | pos-catalog | ADR-0053 §4 |

Routing rule for new stories: computing **what a customer pays** → pos-price; showing a
**list/MSRP/reference price** → pos-catalog.

## Responsibilities

- Maintain product master data (inventory and non-inventory products)
- Own product stock-attribute contract data for pos-inventory (#1023): per-product UoM conversion sets (purchase/pack UoM → base UoM factor + precision scale), stock tracking level (`NONE`/`LOT`/`SERIAL`), and substitution groups of interchangeable products
- Manage price books and associated pricing rules (reference/list role, ADR-0054)
- Handle unit-of-measure conversions between stocking and selling units
- Track supplier item costs and MSRP records per product
- Apply and resolve location-level **reference** price overrides (list-price display; never a transactional price source)
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
- `GET /v1/catalog/pricing/effective-price/{locationId}/{productId}` — reference price at a location (list/MSRP role, ADR-0054; not a transactional quote)
- `GET /v1/catalog/{priceBookId}` — retrieve a price book
- `POST /v1/catalog/bulk-ingest` — bulk import products (auth: `catalog:product:create`)
- `PUT /v1/products/{productId}/tracking-level` — set stock tracking level (`NONE`/`LOT`/`SERIAL`)
- `POST|GET /v1/products/{productId}/uoms`, `PUT|DELETE /v1/products/{productId}/uoms/{uomId}` — per-product UoM conversion set
- `POST|GET /v1/products/substitution-groups`, `GET|DELETE /.../{groupId}`, `POST /.../{groupId}/members`, `DELETE /.../{groupId}/members/{productId}` — substitution groups (a product belongs to at most one group)
- `POST|GET /v1/catalog-items/service/{serviceId}/labor-standards`, `POST /.../{standardId}/supersede` — vehicle-keyed estimated service times (book time) with provenance (auth: `catalog:labor_standard:manage` / `:view`)

## Estimated service time (labor standards, #1569)

`ServiceEntity` is the system of record for estimated service time (ADR-0058/0059). The `service` table carries the operation taxonomy — `operation_code` (Durion-owned identity, unique when present), `operation_category` (`REPAIR`/`DIAGNOSTIC`/`MAINTENANCE`/`TIRE_SERVICE`) and `default_labor_hours` (vehicle-agnostic fallback only) — authored through `/v1/catalog-items/service`. Vehicle-specific book times live in `service_labor_standard` (V18): year/make/model/submodel/engine key with null-as-wildcard, decimal hours in tenths, `time_type` (retail vs OEM-warranty vs manufacturer-install vs Durion standard), overlap/included-operation metadata, and non-negotiable source + revision provenance. Rows are append-and-supersede: corrections replace a row rather than update it, so a quoted number stays explainable. Only `DURION`-source rows are writable through the API; imported guide rows arrive via the labor-guide sourcing pipeline (`docs/service-time-sourcing-plan.md`) in a later phase, which also brings the resolution service (exact + widening vehicle match) and the transport to pos-workorder.

## Domain Events

Product mutations queue a `catalog.product.updated` fact (payload `ProductUpdatedV1`, schema version 2) on `catalog.events.v1` via the transactional outbox (`pos.catalog.kafka.enabled`), reconciled hourly on `catalog.manifest.v1`. Schema version 2 (#1023, additive) added `baseUom`, `trackingLevel`, `uomConversions[]` (`uomCode`, `uomType`, `factorToBase`, `precisionScale`), `substitutionGroupId`, and `substitutionProductIds[]` so pos-inventory can replicate UoM conversions, tracking level, and substitution membership (`ext_product_uom` et al.). UoM and substitution-membership mutations bump the product's `updatedAt` (the envelope `aggregateVersion`) and re-emit the fact for every affected product.

Issue #1514 added `subcategoryId` and `subcategory` additively within schema version 2, following the precedent set when `productCode` was added: pos-inventory replicates the product's category *and* subcategory so putaway rules can route on them, and the subcategory level is what carries hazard containment (`Batteries` is a subcategory of `Electrical System`). Consumers match on the **id**, not the name — pos-catalog publishes product facts, not category facts, so a category rename only reaches a replica after a product replay, which makes the name an un-refreshed snapshot.

## Category and subcategory resolution (#1514)

`CategoryNameResolver` resolves human-authored category and subcategory **names** to ids for ingest paths that carry names rather than ids, against the Flyway-seeded reference taxonomy (`R__seed_reference_catalog.sql`: 12 categories, 40 subcategories).

- **Bulk ingest now honours `categoryName` and `subcategoryName`.** It previously accepted both and discarded them, so every bulk-loaded product landed uncategorized — and an uncategorized product matches no category putaway rule.
- **Matching is trimmed and case-insensitive.** The names come from hand-maintained CSV fixtures where casing and trailing whitespace are editing accidents; the seeded names are unique under case-folding, so this adds no ambiguity.
- **An unknown name fails the row** (`CATALOG_INGEST_FAILED` per row on bulk ingest, HTTP 400 on the single-product API) rather than landing the product uncategorized. Landing it would look like success while producing exactly the inert state #1514 exists to fix. Ambiguous names fail for the same reason.
- **An absent name is not an unknown one**: null or blank means "not classified" and resolves to null without error.
- `ProductMasterDataServiceImpl` now writes `subcategory` as well as `category`. Before this, only the Flyway product seed carried a subcategory, so on any API-created product `subcategoryId` published null forever and subcategory-level putaway precedence silently degraded to category-only.

Service mutations queue a `catalog.service.updated` fact (payload `CatalogServiceUpdatedV1`, schema version 1) on the same topic (#1306). Services previously published nothing, which left every consumer able to resolve a `product:` reference and unable to resolve a `service:` one — pos-marketing replicates both into an `ext_catalog` table so a campaign's `catalogFocusRef` can be checked before the campaign is scheduled. `ServiceEntity` carries no lifecycle column, so the fact's `active` flag is true on create/update and false on the tombstone published when a service is deleted; `aggregateVersion` is the service's `updatedAt` epoch millis, and the tombstone's is the delete time floored to one millisecond past it so it cannot tie with the upsert it supersedes. `POST /v1/catalog-items/services/facts/replay` re-emits these facts for replica seeding, the service counterpart of the product replay below and paged the same way; a deleted service leaves no row to replay, so its tombstone exists only in the live stream. Product deletion publishes a tombstone too (`active=false`, emitted before the row is removed), so a replica stops resolving a product that no longer exists. Seeding a catalog replica means running **both** replays — see `docs/OPERATIONS_RUNBOOK.md` → "Replica seeding and drift repair (replay)" → "pos-catalog: seeding a catalog replica".

### Replay for replica consumers (#1309)

`POST /v1/products/facts/replay` re-emits `catalog.product.updated` facts so a consumer's replica
can be seeded or repaired — the mechanism ADR-0044 §4 has always required owners to provide, and
which this module lacked until pos-supplier's PRICAT matching became the first consumer that cannot
function without it.

- **Producer-side only.** No replica-holding module needs code for a replay to reach it.
- **Indistinguishable from live facts.** The same `CatalogFactPublisher` produces them, with
  `aggregateVersion` still the product's `updatedAt` epoch millis — so a consumer's ordinary stale
  guard prevents a replayed older fact regressing newer replica state. New `eventId`s are expected.
- **Paged and resumable.** `afterProductId` is a cursor over the product id (not an offset, which
  would shift under concurrent writes and drop a product out of the window), `updatedSince` narrows
  the set, and `limit` is capped at 1000 per call. A short page means the catalog end was reached.

A first deployment of a replica consumer should replay before trusting the replica: it holds only
facts published after it started.

## Live supplier stock on Product Detail (#1225)

Product Detail carries a `supplierAvailability` component when
`pos.supplier.stock.vendor-profile-id` names a vendor profile. Unset, the component is **absent** rather
than present-and-degraded: a block that always says "unavailable" teaches a reader to skip it.

This is the **one synchronous cross-domain read** in this module (ADR-0044 amendment 2026-08-10).
Everything else it needs from another domain arrives as events. Vendor stock is the exception because it
cannot be replicated — it lives at the vendor, changes without telling us, and is worthless once stale,
so a replica of it would be a confidently wrong number on a customer's screen. `DomainWallsTest`
allowlists `SupplierStockClientImpl` **by file name**; any other client here that reaches for
pos-supplier still fails the build.

Three different nothings reach the page, and the component keeps them apart:

| What happened | `status` | `vendorStatus` | `availableQuantity` |
| --- | --- | --- | --- |
| Vendor stated a quantity | `OK` | `AVAILABLE` | the quantity |
| Vendor said it has none | `OK` | `UNAVAILABLE` | `0` — a fact |
| Vendor does not carry it | `OK` | `NOT_LISTED` | `null` |
| Vendor said nothing, or could not be reached | `UNAVAILABLE` | `NOT_ANSWERED` or absent | `null` |

Only the second row justifies telling a customer the vendor is out of stock. Nothing on this path
defaults a missing quantity to zero.

## Configuration

| Property                                | Default  | Description                                                     |
| --------------------------------------- | -------- | --------------------------------------------------------------- |
| `SPRING_DATASOURCE_URL`                 | required | PostgreSQL connection URL                                        |
| `EUREKA_SERVER_URL`                     | required | Eureka service discovery URL                                     |
| `pos.supplier.stock.vendor-profile-id`  | unset    | Vendor profile asked for live stock; unset disables the component |

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
