# pos-price

Pricing engine service for the Durion POS platform. Manages base prices, promotion offers, restriction rules, eligibility evaluation, pricing snapshots, and price quote calculation.

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
