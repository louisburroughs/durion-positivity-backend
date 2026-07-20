# pos-tax

Tax calculation service for the Durion Positivity ETSMS platform. Supports two operating modes: test mode with configurable flat rates per jurisdiction type, and production mode that proxies calls to an external tax API with retry and exponential backoff.

## Responsibilities

- Calculate tax for a set of line items given a postal address
- Break down tax by jurisdiction (state, county, city, special district)
- Handle tax-exempt line items
- Proxy requests to an external tax API in production mode with Resilience4j retry
- Emit audit events for all calculations via `pos-events`

## Key Classes

- `TaxCalculationService` — public service interface; the entry point for all tax calculations
- `TaxCalculationServiceImpl` — delegates to `TestModeTaxCalculator` or `ExternalTaxServiceClient` based on configuration
- `TestModeTaxCalculator` — applies configured flat rates per jurisdiction type for dev/test, selecting the effective-dated rate set for the transaction date
- `TaxTotalsReconciler` — package-private helper that enforces the rounding invariant across line, jurisdiction, and total amounts
- `ExternalTaxServiceClient` — `RestClient`-based client for the external tax provider with retry
- `TaxController` — REST controller at `/v1/tax`

## API Endpoints

- `POST /v1/tax/calculate` — calculate tax for a set of line items
- `GET /v1/tax/mode` — returns current operating mode (`test` or `production`)

## Configuration

| Property                                 | Default          | Description                              |
| ---------------------------------------- | ---------------- | ---------------------------------------- |
| `pos.tax.test-mode.enabled`              | `false`          | Enable flat-rate test mode               |
| `pos.tax.test-mode.default-rates.STATE`  | `0.0725`         | State rate in test mode                  |
| `pos.tax.test-mode.default-rates.COUNTY` | `0.01`           | County rate in test mode                 |
| `pos.tax.test-mode.default-rates.CITY`   | `0.0025`         | City rate in test mode                   |
| `pos.tax.test-mode.rate-schedule`        | empty            | Ordered effective-dated rate overrides (see below) |
| `pos.tax.external-service.base-url`      | required in prod | External tax provider URL                |
| `pos.tax.external-service.api-key`       | required in prod | External tax provider API key            |
| `pos.tax.retry.max-attempts`             | `3`              | Retry attempts for external API failures |

### Effective-dated test-mode rates

`pos.tax.test-mode.rate-schedule` is an ordered list of `{effective-from, rates{…}}` entries.
For a given transaction the calculator selects the entry with the greatest `effective-from`
that is not after the transaction date. When the schedule is empty (the default), or when no
entry is effective on or before the transaction date, `default-rates` is used, preserving prior
behavior. The transaction date defaults to today (injected `Clock`) when the request omits it;
an unparseable `transactionDate` fails fast with a deterministic error (ADR-0021).

```yaml
pos:
  tax:
    test-mode:
      rate-schedule:
        - effective-from: 2025-01-01
          rates:
            STATE: 0.0700
            COUNTY: 0.0100
            CITY: 0.0025
        - effective-from: 2026-01-01
          rates:
            STATE: 0.0725
            COUNTY: 0.0125
            CITY: 0.0025
```

### Rounding reconciliation

Tax is computed on a per-line × per-jurisdiction raw (unrounded) matrix with a single rounding
stage, after which `TaxTotalsReconciler` enforces the invariant
`Σ lineItemTaxes.taxAmount == totalTax == Σ jurisdictions.taxAmount` (and per line,
`Σ jurisdiction-cell amounts == line.taxAmount`). Residual cents are distributed
deterministically, largest-raw-amount-first (mirrors Odoo `_distribute_delta_amount_smoothly`).

### Effective tax rate

`effectiveTaxRate` is `totalTax` divided by the exempt-filtered taxable base (exempt lines are
excluded from the denominator). An all-exempt or zero-base cart yields `0.00`.

## Dependencies

- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-tax-common` — `TaxCalculationRequest` and `TaxCalculationResponse` DTOs

## Deployment Modes

`pos-tax` is primarily consumed as a library dependency by `pos-workorder`, `pos-invoice`, and similar services. When used this way it requires no separate deployment. It can also be deployed as a standalone internal microservice (Eureka registration disabled by default) but must not be added to the API gateway routes.

## Development

```bash
./mvnw -pl pos-tax -am spring-boot:run --spring.profiles.active=dev
```
