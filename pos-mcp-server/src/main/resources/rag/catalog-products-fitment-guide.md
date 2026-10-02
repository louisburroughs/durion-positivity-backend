# Catalog Products and Vehicle Fitment Guide

## Purpose

RAG id: `catalog.products-fitment`
RAG scope: `inventory`
Required permissions: `catalog:product:view`, `vehicle-fitment:catalog:view`
Audience: internal staff (service advisors, technicians, parts and inventory staff, managers).
This document is reference context only and grants no access; access is enforced by permission codes at request time.

This guide grounds questions about what the shop sells and which parts fit a vehicle: what a catalog product, a
service item and a non-inventory item are, how a product is identified (SKU, manufacturer part number, UPC or EAN),
how the catalog differs from stock on hand, how vehicle fitment is recorded and queried by year, make and model, and
where prices for a product come from. Products are owned by `pos-catalog`; fitment is owned by `pos-vehicle-fitment`.

Typical questions: "Look up product BRK-9920 in the catalog", "Show me rotors and brake pads for a 2019 Civic",
"Which brake pads fit a 2018 Silverado?", "Do we carry wiper blades?", "Is this part discontinued, and what replaces
it?".

## Catalog item types

`pos-catalog` holds three kinds of catalog item. The generic item endpoints take the type as a path value, one of
`product`, `service` or `noninventory`; any other value is rejected with 400.

| Type | What it is | Read permission |
| --- | --- | --- |
| `product` | A physical part or good with a SKU, manufacturer data, category and dimensions. Products are what inventory stocks and what fitment applies to. | `catalog:product:view` |
| `service` | A labor operation the shop performs (for example a brake job), with an operation code, an operation category (`REPAIR`, `DIAGNOSTIC`, `MAINTENANCE`, `TIRE_SERVICE`) and default labor hours. A service has no stock. | `catalog:service_type:view` |
| `noninventory` | An item sold without stock tracking, such as a fee or shop supplies. | `catalog:non_inventory:view` |

Two catalog structures sit on top of the items. A **catalog grouping** (`/v1/catalogs`, `catalog:catalog_grouping:view`)
is a named list of items. A **service package** (`/v1/service-packages`, `catalog:service_package:view`) is a set of
service operations sold as a unit; a location sees its own packages plus every platform package, and a fleet account
can have its own requirement set.

Products are also classified by a category and subcategory from a seeded reference taxonomy of 12 categories and 40
subcategories. Brake parts, for example, sit under the category `Brake System` with the subcategories
`Brake Pads & Shoes`, `Brake Rotors & Drums` and `Brake Hardware & Calipers`; wiper blades under `Body & Lighting` →
`Wiper Blades`; oil filters under `Filters` → `Oil Filters`. Category and subcategory names can be used as exact,
case-insensitive search filters.

## Product identifiers

A product carries several identifiers. Use the one the caller gives you and do not invent a format for any of them.

| Identifier | Field | Rules in the platform |
| --- | --- | --- |
| Product id | `id` | UUID, opaque to callers. Most product endpoints address a product by this id. |
| SKU | `sku` | Client-supplied text with no pattern and no auto-generation, stored in a column of at most 255 characters and unique per tenant. The product-master endpoints (`createProduct`, `updateProduct`) require it at create, reject a duplicate (case-insensitive) with 409 and refuse to change it afterwards. The generic item endpoints (`createCatalogItem`, `updateCatalogItem`) neither require nor check it and copy whatever the body carries, including on update. |
| Manufacturer part number | `mpn` / `manufacturerPartNumber` | Required at create. The pair manufacturer id plus MPN is unique. |
| Product code | `productCode` with `productCodeType` | A `UPC` or `EAN` value, unique within its scheme. A UPC supplied at create also becomes the product code. |

A SKU is a seller's own code, while UPC and EAN are standard barcode numbers shared across the supply chain (see
Sources [1], [2]). Because a SKU has no platform format, a value such as `BRK-9920` is just an opaque code: look it up
by exact match, never by guessing its structure. The SKU entry in the identifier glossary
(`glossary.identifiers`) says the same.

EAN-13 extends UPC-A by putting one extra digit in front of the 12-digit UPC (see Sources [2]), so the same item can
reach a scanner or a vendor file as a 12-digit UPC or as a 13-digit code starting with `0`. The platform stores each
code under one scheme and compares it as text, so those two spellings do not match each other. When a code is not
found, retry it under the other scheme (with or without the leading `0`) before saying the product is not in the
catalog.

## Looking a product up

| Need | Operation | Permission |
| --- | --- | --- |
| Find products by partial name or description text, optionally filtered by brand, category, subcategory or exact SKU | `searchCatalogProducts` (`GET /v1/products/search`) | `catalog:product:view` |
| Resolve an exact SKU | `searchCatalogProducts` with the `sku` filter | `catalog:product:view` |
| Resolve a scanned or vendor UPC or EAN to one product | `findProductByCode` (`GET /v1/products/by-code`) | `catalog:product:view` |
| Read the master record when the id is known | `getProductById` (`GET /v1/products/{productId}`) | `catalog:product:view` |
| Sales view of one product at one location: catalog data, store price, availability and lead time | `getProductDetailView` (`GET /v1/products/{productId}/detail`) | `catalog:product:view` |
| Exact whole-name match only | `listProductsByName` | `catalog:product:view` |

The free-text query (`q`) matches product name and description only; it does not match the SKU. To look up
"product BRK-9920", pass the code as the `sku` filter. Search is paged by an opaque cursor, 20 rows by default and at
most 100; `detailed=true` adds the lifecycle state and the active MSRP to each row. An empty result is a 200 with no
items, not an error.

The `CatalogFacadeTool` offers three reads under `catalog:product:view`: `getProduct` (by id), `searchCatalog` (sends
the text as the free-text query, first page only) and `getCatalogByCategory` (category filter, first page only).

`findProductByCode` matches exactly within one scheme: a code that differs by a leading zero is a different code, an
unmatched code is a 404 rather than a near match, and a code still duplicated in the data is refused with 409.

## Product lifecycle, status and substitutes

`lifecycleState` is `ACTIVE`, `INACTIVE` or `DISCONTINUED` (`ACTIVE` when never set). Discontinuation is one-way: a
`DISCONTINUED` product can never be reactivated, moving a product into it needs
`product:lifecycle:override_discontinued` and a reason, and a replacement is recorded instead. Lifecycle changes need
`catalog:product:edit` or `product:lifecycle:update`. Separately, `status` is the operational flag `ACTIVE` or
`INACTIVE`, and `trackingLevel` says how inventory tracks the product: `NONE`, `LOT` or `SERIAL`.

Two different "what else can I sell" answers exist:

- **Replacements** supersede a product, normally a discontinued one. `listProductReplacements` returns the option rows
  in priority order; `getPartSubstitutes` returns the full product records of those replacements.
- **Substitution groups** (`catalog:substitution_group:view`) are sets of interchangeable products; a product belongs to
  at most one group.

Neither of these is fitment: a substitute is interchangeable with another product, while fitment says which vehicles a
product applies to.

## Catalog versus stock on hand

The catalog says what a product is; it does not say how many are on the shelf. On-hand quantity, availability to
promise, reservations, receiving and transfers belong to `pos-inventory`, which receives product facts (SKU, category,
units of measure, tracking level, substitution membership) from `pos-catalog` by event and keeps its own copy.

- "Do we carry wiper blades?" is a catalog question: search the catalog for the item.
- "How many brake pads are on hand at this store?" is an inventory question: it needs the inventory availability or
  on-hand permissions, not `catalog:product:view`.
- `getProductDetailView` is the one catalog read that combines both. For a given location it asks `pos-inventory` for
  availability (by SKU) and `pos-price` for the store price. Either source may be degraded: each block has a `status`
  (`OK`, `UNAVAILABLE`, `STALE`, `ERROR`), and the response carries an overall `confidence` (`LOW`, `MEDIUM`, `HIGH`).
  A degraded block is not a quantity of zero.
- When a vendor profile is configured, the detail view can also show live supplier stock. A vendor status of
  `AVAILABLE` carries the vendor's quantity and `UNAVAILABLE` (quantity 0) means the vendor said it has none;
  `NOT_LISTED` means the vendor does not carry the product, and a component `status` of `UNAVAILABLE` (vendor status
  `NOT_ANSWERED` or absent) means the vendor did not answer. Only the vendor's own "none" justifies telling a customer
  the vendor is out of stock.

## Prices for a product

`pos-catalog` holds **reference** prices only: MSRP history (`catalog:msrp:read`), price books and their rules
(`catalog:price_book:read`), and location reference prices (`catalog:location_price_override:read`). These are for
display and reporting. **What a customer actually pays** (quotes, estimate and workorder pricing, checkout) is
resolved by `pos-price` (ADR-0054). The `storePrice` in the product detail view comes from `pos-price`; the `msrp`
next to it is catalog reference data. Supplier cost (`catalog:supplier_cost:read`) is what the vendor charges the shop
and takes no part in sell-price resolution. Questions about why a price is what it is belong to the pricing guides.

## Vehicle fitment

**Fitment** answers which vehicles a part applies to. In the automotive aftermarket the exchange standard for it is
ACES, the Auto Care Association's "data standard for the management and communication of product fitment data";
a typical ACES use is a part that needs a year, make and model lookup to be found (see Sources [3]). PIES is the
companion standard for the product record itself: descriptions, attributes, pricing, warranty, interchanges (see
Sources [5]). The platform does not import ACES or PIES files; it records fitment in its own, simpler form.

In `pos-vehicle-fitment`, a product's fitment is held as **vehicle applicability hints**. A hint belongs to one product
(by product id) and carries a list of fitment tags, each a tag type and a value. The tag types are `MAKE`, `MODEL`,
`YEAR_RANGE`, `TIRE_SIZE`, `AXLE_POSITION`, `ENGINE_SIZE` and `TRIM_LEVEL`. A `YEAR_RANGE` value is a single year
(`2020`) or a range (`2018-2022`).

**Querying fitment by vehicle.** `filterProductsByVehicleAttributes` (`POST /v1/vehicle-fitment/hints/filter-products`,
permission `vehicle-fitment:catalog:view`) takes a map of vehicle attributes, for example
`{"make": "Chevrolet", "model": "Silverado", "year_range": "2018"}`, and returns the matching product ids and their
count. Matching rules, from the service code:

- Attribute keys name a tag type, case-insensitively (`make`, `model`, `year_range`, `tire_size`, `axle_position`,
  `engine_size`, `trim_level`). An unknown key is ignored, not rejected.
- Values match case-insensitively. A year matches a `YEAR_RANGE` tag when it falls inside the range or equals the
  single year.
- A hint that has no tag of a given type does **not** exclude the product on that attribute: it counts as compatible.
  A product whose hint carries only `MAKE` matches every model and year of that make.
- The result is product ids only. To answer "rotors and brake pads for a 2019 Civic", filter by the vehicle, then read
  or search the catalog for those products (for example with the subcategory filter) to get names, SKUs and
  lifecycle, and ask inventory for stock.
- `vehicleAttributes` is required and must not be empty: an empty or missing map is rejected with 400 before any
  matching runs.
- An empty result means no hint matched. It does not prove the part does not fit; fitment data may simply not have
  been recorded for that product.

**Querying fitment by product.** `listVehicleHintsByProduct` (`GET /v1/vehicle-fitment/hints/product/{productId}`,
permission `vehicle-fitment:hint:view`) returns a product's hints with their tags. An unknown product or one without
hints is an empty list. Creating, changing and deleting hints need `vehicle-fitment:hint:create`, `:update` and
`:delete`; a hint's product id is stored as given and is not checked against the catalog.

**Choosing the vehicle.** The manufacturer, make, model and vehicle-type lists (`listManufacturers`,
`listMakesByManufacturer`, `listModelsByMake`, `listVehicleTypesByMake`, all `vehicle-fitment:catalog:view`) are a
local copy of vehicle reference data from NHTSA's public vPIC service, cached in `pos-vehicle-fitment`. vPIC is built
from the VIN information vehicle manufacturers submit to NHTSA (see Sources [4]): it says which makes, models and
vehicle types exist, not which parts fit them. The platform asks vPIC for models by make only, with no model year, so
`listModelsByMake` cannot tell whether a model was sold in a given year, even though vPIC itself can list models by
make and model year for model years after 1995 (see Sources [4]). These lists help a user pick a valid make and model; they are reference data
shared by every tenant, not a customer's vehicle. A customer's own vehicle record (VIN, plate, year, make, model)
lives in `pos-vehicle-inventory` and is covered by the customer and vehicle guide.

**Bulk-loaded part fitment rows.** `bulkIngestVehicleFitments` (`POST /v1/fitments/bulk-ingest`, permission
`vehicle-fitment:hint:create`) loads part-to-vehicle rows keyed by a numeric part number id, with manufacturer, make,
model, vehicle type, year or year range, engine type and submodel. No published endpoint reads these rows, and the
product-by-vehicle filter does not use them: fitment questions are answered from applicability hints only.

**Labor times are not fitment.** Vehicle-specific book times for a service (`catalog:labor_standard:view`) are keyed by
year, make, model, submodel and engine too, but they say how long a job takes on a vehicle, not which parts fit it.

## What platform fitment cannot express

ACES describes vehicles with coded reference data from the Vehicle Configuration database, at whatever level of
detail a product needs (see Sources [3]), and replaces free-text fitment notes with coded qualifiers from the
Qualifier database (see Sources [6]). The platform's applicability hints are much narrower, so say so rather than
guess when a question goes beyond them:

- Only seven attributes exist: `MAKE`, `MODEL`, `YEAR_RANGE`, `TIRE_SIZE`, `AXLE_POSITION`, `ENGINE_SIZE` and
  `TRIM_LEVEL`. There is no tag for transmission, drive type, body style, fuel type or brake system, and no way to
  key a hint to a VIN.
- Tag values are free text of at most 120 characters, compared case-insensitively. "Chevrolet" and "Chevy" are
  different values, and nothing checks a value against the vPIC make and model lists.
- There are no qualifiers or notes on a hint, so a condition such as "except with off-road package" cannot be
  recorded. The bulk-loaded part fitment rows carry a notes field, but nothing reads those rows.
- A missing tag means "any", so a hint with fewer tags matches more vehicles. A match is "nothing recorded rules it
  out", not a confirmed fit.

## Staff questions and answer patterns

| Question | Interpretation |
| --- | --- |
| "Look up product BRK-9920." | Exact SKU lookup with the `sku` search filter; if nothing matches, say so and ask whether the code is a UPC/EAN or a manufacturer part number. |
| "Which brake pads fit a 2018 Silverado?" | Filter products by make, model and year, then narrow to the `Brake Pads & Shoes` subcategory in the catalog. Report an empty result as "no fitment recorded", not "does not fit". |
| "Show me rotors and brake pads for a 2019 Civic." | Filter products by make, model and year, then narrow to the `Brake Rotors & Drums` and `Brake Pads & Shoes` subcategories; stock and price are separate reads. |
| "Do we carry wiper blades?" | Catalog search by text or the `Wiper Blades` subcategory. |
| "How many are in stock?" | Inventory availability, not the catalog. |
| "What does it cost?" | The customer price comes from `pos-price`; MSRP and price books are reference only. |
| "Is it discontinued? What replaces it?" | Lifecycle state, then replacements or substitutes. |

## Permissions summary

| Code | Grants |
| --- | --- |
| `catalog:product:view` | Product search, product record, product detail view, code lookup, lifecycle and replacement reads |
| `catalog:service_type:view` | Service item reads and search |
| `catalog:non_inventory:view` | Non-inventory item reads |
| `catalog:service_package:view` | Service package reads |
| `catalog:substitution_group:view` | Substitution group reads |
| `catalog:msrp:read`, `catalog:price_book:read` | Reference price reads |
| `vehicle-fitment:catalog:view` | Vehicle reference lists and the product-by-vehicle fitment filter |
| `vehicle-fitment:hint:view` | A product's applicability hints |
| `vehicle-fitment:hint:create`, `:update`, `:delete` | Fitment data maintenance, including the bulk fitment load |

Which roles hold each code is decided in the security service and can differ per tenant; answer from the caller's
own permissions, never from a role name.

## Sources

Platform sources:

- `pos-catalog/openapi.yaml` (operations `searchCatalogProducts`, `findProductByCode`, `getProductById`,
  `getProductDetailView`, `createProduct`, `updateProduct`, `createCatalogItem`, `updateCatalogItem`, `getProductLifecycle`,
  `updateProductLifecycle`, `listProductReplacements`, `getPartSubstitutes`, `listServicePackages`; schemas
  `ProductDto`, `ProductDetailView`, `PricingInfo`, `AvailabilityInfo`)
- `pos-catalog/README.md` (sell-price boundary, product facts, supplier stock on product detail, labor standards)
- `pos-catalog/src/main/resources/permissions.yaml` and
  `pos-catalog/src/main/java/com/positivity/catalog/internal/security/CatalogPermissions.java`
- `pos-catalog/src/main/java/com/positivity/catalog/internal/repository/ProductRepository.java` (search matching)
- `pos-catalog/src/main/java/com/positivity/catalog/internal/service/ProductDetailServiceImpl.java`
- `pos-catalog/src/main/java/com/positivity/catalog/internal/service/ProductMasterDataServiceImpl.java` (SKU
  uniqueness and immutability) and `.../CatalogServiceImpl.java` (generic item create and update)
- `pos-catalog/src/main/resources/db/migration/V1__baseline_catalog.sql` (`product.sku` column and unique constraint)
- `pos-catalog/src/main/resources/db/migration/R__seed_reference_catalog.sql` (category taxonomy)
- `pos-vehicle-fitment/openapi.yaml` (operations `filterProductsByVehicleAttributes`, `listVehicleHintsByProduct`,
  `createVehicleHint`, `bulkIngestVehicleFitments`, `listManufacturers`; schema `FitmentTagDto`)
- `pos-vehicle-fitment/src/main/java/com/positivity/vehiclefitment/internal/dto/FilterProductsRequest.java`
  (`vehicleAttributes` validation)
- `pos-vehicle-fitment/src/main/resources/permissions.yaml` and
  `pos-vehicle-fitment/src/main/java/com/positivity/vehiclefitment/internal/security/VehicleFitmentPermissions.java`
- `pos-vehicle-fitment/src/main/java/com/positivity/vehiclefitment/internal/service/VehicleApplicabilityHintServiceImpl.java`
  (matching rules) and `.../VehicleFitmentServiceImpl.java` (vPIC cache)
- `pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/tools/CatalogFacadeTool.java`
- `durion/domains/product/.business-rules/AGENT_GUIDE.md` (product domain boundaries)
- `durion/docs/adr/0054-sell-price-system-of-record-split.adr.md`

External sources:

1. "Stock keeping unit", Wikipedia (Wikimedia Foundation), <https://en.wikipedia.org/wiki/Stock_keeping_unit>,
   accessed 2026-10-02.
2. "Universal Product Code", Wikipedia (Wikimedia Foundation), <https://en.wikipedia.org/wiki/Universal_Product_Code>,
   accessed 2026-10-02.
3. "Aftermarket Catalog Exchange Standard (ACES)", Auto Care Association, <https://www.autocare.org/aces>, accessed
   2026-10-02; with "Vehicle Configuration database (VCdb)", Auto Care Association,
   <https://www.autocare.org/data-and-information/data-standards/databases/vehicle-configuration-database-vcdb>,
   accessed 2026-10-02.
4. "Vehicle API" (vPIC, Product Information Catalog Vehicle Listing), National Highway Traffic Safety Administration,
   U.S. Department of Transportation, <https://vpic.nhtsa.dot.gov/api/>, accessed 2026-10-02.
5. "Product Information Exchange Standard (PIES)", Auto Care Association, <https://www.autocare.org/pies>, accessed
   2026-10-02.
6. "Qualifier database (Qdb)", Auto Care Association,
   <https://www.autocare.org/data-and-information/data-standards/databases/qualifier-database-qdb>, accessed
   2026-10-02.
