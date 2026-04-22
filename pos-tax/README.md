# pos-tax

Tax calculation service for the Durion POS platform. Supports two operating modes: test mode with configurable flat rates per jurisdiction type, and production mode that proxies calls to an external tax API with retry and exponential backoff.

## Responsibilities

- Calculate tax for a set of line items given a postal address
- Break down tax by jurisdiction (state, county, city, special district)
- Handle tax-exempt line items
- Proxy requests to an external tax API in production mode with Resilience4j retry
- Emit audit events for all calculations via `pos-events`

## Key Classes

- `TaxCalculationService` — public service interface; the entry point for all tax calculations
- `TaxCalculationServiceImpl` — delegates to `TestModeTaxCalculator` or `ExternalTaxServiceClient` based on configuration
- `TestModeTaxCalculator` — applies configured flat rates per jurisdiction type for dev/test
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
| `pos.tax.external-service.base-url`      | required in prod | External tax provider URL                |
| `pos.tax.external-service.api-key`       | required in prod | External tax provider API key            |
| `pos.tax.retry.max-attempts`             | `3`              | Retry attempts for external API failures |

## Dependencies

- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-tax-common` — `TaxCalculationRequest` and `TaxCalculationResponse` DTOs

## Deployment Modes

`pos-tax` is primarily consumed as a library dependency by `pos-workorder`, `pos-invoice`, and similar services. When used this way it requires no separate deployment. It can also be deployed as a standalone internal microservice (Eureka registration disabled by default) but must not be added to the API gateway routes.

## Development

```bash
./mvnw -pl pos-tax -am spring-boot:run --spring.profiles.active=dev
```
