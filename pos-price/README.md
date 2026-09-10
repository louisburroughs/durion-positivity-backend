# pos-price

Pricing engine service for the Durion Positivity ETSMS platform. Manages base prices, promotion offers, restriction rules, eligibility evaluation, pricing snapshots, and price quote calculation.

## Sell-Price Boundary (ADR-0054)

pos-price is the **system of record for transactional sell-price resolution**: every price a
customer actually pays (quotes, workorder/estimate pricing, checkout) is resolved here and only
here, via the quote chain **base price → location override → customer-tier discount**. pos-price
holds **no list/MSRP reference data** — list prices, MSRP history, and reference series (including
PRICAT suggested retail) are pos-catalog's reference role and are never a transactional price
source. Customer-tier *discounts* in pos-price are the applied transactional mechanism; pos-catalog
customer-tier *books* are reference/list data — the two are never competing resolvers.

pos-price is a **utility** module (ADR-0044 §1): it remains synchronously callable by other
services.

Effective dating uses half-open windows on `Instant`: a base price row is effective for instants
`t` where `effectiveFrom <= t < effectiveTo`; a null `effectiveTo` means the window is open-ended
(inclusive start, exclusive end).

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

- Store and resolve base prices per product and effective date
- Manage promotion offers with eligibility criteria and discount structures
- Define and evaluate restriction rules that block or limit pricing application
- Evaluate customer eligibility for promotions
- Generate immutable pricing snapshots for audit and reproducibility
- Calculate price quotes for a given product/customer/location context
- Support bulk base price import via `POST /v1/price/bulk-ingest`

## Key Classes

- `PriceQuoteService` — entry point for price calculation; applies base price, promotions, and restrictions
- `PromotionOfferService` — promotion offer CRUD and code lookup
- `EligibilityEvaluationService` — evaluates whether a customer qualifies for a promotion
- `RestrictionRuleService` — restriction rule management
- `RestrictionEvaluationService` — evaluates restriction rules against a pricing context
- `PricingSnapshotService` — immutable pricing snapshot creation and retrieval

## API Endpoints

- `GET /v1/price/{id}` — retrieve a base price record
- `POST /v1/price` — create a base price
- `DELETE /v1/price/{ruleId}` — remove a base price
- `GET /v1/price/by-code/{promoCode}` — look up a promotion by code
- `POST /v1/price/apply` — apply a pricing context and return effective price
- `POST /v1/price/normalize` — normalize pricing inputs
- `POST /v1/price/restrictions:evaluate` — evaluate restriction rules
- `POST /v1/price/evaluate` — evaluate promotion eligibility
- `GET /v1/price/{snapshotId}` — retrieve a pricing snapshot
- `POST /v1/price/bulk-ingest` — bulk import base prices (auth: `pricing:base_price:create`)

## Configuration

| Property                | Default  | Description                  |
| ----------------------- | -------- | ---------------------------- |
| `SPRING_DATASOURCE_URL` | required | PostgreSQL connection URL    |
| `EUREKA_SERVER_URL`     | required | Eureka service discovery URL |

## Multitenancy (ADR-0062, WS3 wave 10)

This module runs on the ADR-0062 runtime: it depends on `pos-tenancy-common`, and every entity extends `TenantScopedEntity` (there are no global tables). The request tenant is
bound by `TenantContextFilter` from `X-Tenant-Id` (the gateway injects it from the token's `tid`), and every
connection checkout binds `app.current_tenant` for row-level security. `pos.tenancy.default-tenant-id` still binds
the alpha default tenant on every unbound path.

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`, `FlywayConfig`).

There is no outbox, no consumer and no scheduled job. The restriction evaluation runs each item on a virtual
thread, which does not inherit the caller's binding, so `RestrictionEvaluationServiceImpl` re-binds the request
tenant on that thread before the rule lookup. The H2 fixtures (`src/test/resources/data.sql`) name `tenant_id`
explicitly: H2 has no `app_current_tenant()`.

Proof: `TenantIsolationIT` (tenant A's `labor_rate` row is invisible to tenant B and to an unbound
connection, through the repository and through raw SQL) and `TenancySchemaConformanceIT` (every
non-whitelisted table has `tenant_id`, RLS enabled and forced, and the `tenant_isolation` policy; the pool is
`pos_app` with no bypass), both on Testcontainers Postgres (`./mvnw -pl pos-price -am verify`).

## Dependencies

- `pos-security-common` — JWT-based security filter
- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-bulk-ingest-lib` — bulk-ingest base controller

## Database

Uses Flyway with PostgreSQL. Migrations at `src/main/resources/db/migration`.

## Development

```bash
./mvnw -pl pos-price -am spring-boot:run
```
