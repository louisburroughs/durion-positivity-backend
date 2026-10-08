# pos-tax

> **Status: a collection of stubs.** pos-tax exists so that pos-accounting stories can begin. Its rates, rules and
> answers are placeholders, not tax law. Every tax question is held for expert advice: rates, which supplies are
> taxable, what is recoverable and at what share, evidence thresholds, registration formats, claim limits and filing.
> When accounting needs a new tax function, it is stubbed here and added to the [stub register](#stub-register).
> The platform owner set this rule on 2026-10-08 (durion `domains/accounting/SPEC-accounting-workspace.md`, AW48;
> louisburroughs/durion#553).

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

## Stub register

pos-tax is a collection of stubs (status note above). This register lists every tax function that callers rely on,
what the stub answers today, and which questions wait for expert advice.

**Rules for a stub**

- It has a fixed contract and a deterministic answer. Placeholder values (rates, thresholds, shares, formats) come
  from configuration or fixture rows, never from a literal in code, and tests use fixture values.
- It never claims to be tax law. Its row names the questions held for expert advice.
- Callers code against the contract, not the placeholder. Replacing a stub with the advised rule changes pos-tax or
  its configuration, never the caller.
- A new tax function that accounting needs is added the same way. The accounting story names the stub, its contract
  and its placeholder behaviour; the pos-tax change adds a row here; the durion spec cites it
  (`SPEC-accounting-workspace.md` §7.3).
- A stub becomes a real rule only after the expert advice is recorded in the spec's decision log.

| Function | Contract | Stub behaviour today | Callers | Held for expert advice |
| --- | --- | --- | --- | --- |
| Sales tax calculation | `POST /v1/tax/calculate` (`tax:calculate`) | Test mode (`TAX_TEST_MODE`, default `true`; Compose sets it): the configured flat rates per jurisdiction type, or the effective-dated schedule, for any address. A line marked `taxExempt` with no exemption claim is taxed zero. A claim (a reason code or a certificate id) is taxed zero only when an active certificate is valid on the transaction date for the destination's state; otherwise the line is taxed and flagged, never refused | pos-order, pos-workorder, pos-invoice | Real rates, which supplies are taxable, every non-US regime |
| Refund calculation | `/calculate` with `calculationType = REFUND` | Positive amounts at the same test-mode rates, as of the `transactionDate` the caller sends (for a refund, the original sale date; it defaults to today). A finalized invoice's credit reverses its stored tax and never calls this | none in code | As above |
| Rate lookup | `GET /v1/tax/rates` (`tax:rates:view`) | Test mode answers from the configured rates. Any other provider answers 501 `TAX_RATE_LOOKUP_UNSUPPORTED` | none in code | Real rates |
| Provider document lifecycle | `POST /v1/tax/transactions/{referenceId}/commit` and `/void` (`tax:commit`) | Test mode: a no-op that always succeeds, logged in `tax_provider_transaction`. The AvaTax adapter exists, but no environment enables it | pos-invoice | Filing and the provider choice |
| Exemption certificates | `/v1/tax/exemption-certificates` (`tax:exemption:view`, `tax:exemption:manage`) | A tenant registry. A claim without an active certificate is taxed and flagged, never refused | none outside pos-tax | Which exemptions are valid, and what evidence they need |
| Use tax (planned) | `/calculate` with `calculationType = USE` (AW44; louisburroughs/durion-positivity-backend#2604) | Priced exactly like `SALE`; test mode always answers | pos-accounting | Which purchases owe use tax, per-state rules, filing (louisburroughs/durion-positivity-backend#2599) |
| Canadian rates (planned) | `GET /v1/tax/rates?countryCode=CA`; `/calculate` rows gain `taxType` and `inputTaxRecoverable` (AW57; louisburroughs/durion-positivity-backend#2522) | A configured rate per province and tax type, in every provider mode, `source = STUB`; every row typed; placeholder recoverability GST, HST and QST yes, PST no. A country facet stops the US defaults from pricing a Canadian address (today they do) | pos-accounting, pos-invoice (the `taxType` hand-off) | Rates, which supplies are taxable, what is recoverable |
| Tax registration status (planned) | A tenant's registration per regime (`GST_HST`, `QST`) as of a date (AW49, AW57) | Operator-set and effective-dated; transport and entry path wait for the Chief Architect (durion spec OI-22) | pos-accounting, pos-order | Registration rules |
| Registration-number shape (planned) | `wellFormed` for a supplier's number (AW53, AW57) | A configurable pattern; by default any non-blank value is well formed | pos-accounting | Number formats |
| Evidence rule (planned) | `GET /v1/tax/evidence-rules?countryCode=CA&asOf=` (AW53, AW57) | One configured row: from 100.00 CAD, `appliesTo` drawer receipts and vendor bills | pos-accounting, pos-order | The threshold, the $500 tier, what is compared, whether bills are in scope |
| Receipt-tax plausibility (planned) | `POST /v1/tax/plausibility-checks` (AW55, AW57) | Per tax: maximum `T × r / (1 + r)` rounded up to the minor unit, plus a tolerance (default 5 minor units, configurable), `r` from the Canadian rates stub; no combined bound. A bookkeeping control against typing errors, not a tax rule | pos-order (drawer entry) | How taxes stack on one receipt |

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

## Multitenancy (ADR-0062, WS3 wave 11)

This module runs on the ADR-0062 runtime: it depends on `pos-tenancy-common`, and every entity extends `TenantScopedEntity` (there are no global tables). The request tenant is
bound by `TenantContextFilter` from `X-Tenant-Id` (the gateway injects it from the token's `tid`), and every
connection checkout binds `app.current_tenant` for row-level security. `pos.tenancy.default-tenant-id` still binds
the alpha default tenant on every unbound path.

The application pool connects as the non-owner `pos_app` role (Compose: `SPRING_DATASOURCE_USERNAME`
/ `POS_APP_PASSWORD`); Flyway alone uses the owner credential (`SPRING_FLYWAY_USER` /
`SPRING_FLYWAY_PASSWORD`, `FlywayConfig`).

There is no outbox, no consumer and no scheduled job. The one native statement,
`TaxProviderTransactionRepository.insertIfAbsent`, names the tenant explicitly from the caller's resolved tenant and
carries `@TenantAudited`, so the lifecycle row is the bound tenant's on Postgres and on the H2 slices alike.

Proof: `TenantIsolationIT` (tenant A's `exemption_certificate` row is invisible to tenant B and to an unbound
connection, through the repository and through raw SQL) and `TenancySchemaConformanceIT` (every
non-whitelisted table has `tenant_id`, RLS enabled and forced, and the `tenant_isolation` policy; the pool is
`pos_app` with no bypass), both on Testcontainers Postgres (`./mvnw -pl pos-tax -am verify`).

## Dependencies

- `pos-events` — `@EmitEvent` annotation and event registration
- `pos-tax-common` — `TaxCalculationRequest` and `TaxCalculationResponse` DTOs

## Deployment Modes

`pos-tax` is primarily consumed as a library dependency by `pos-workorder`, `pos-invoice`, and similar services. When used this way it requires no separate deployment. It can also be deployed as a standalone internal microservice (Eureka registration disabled by default) but must not be added to the API gateway routes.

## Development

```bash
./mvnw -pl pos-tax -am spring-boot:run --spring.profiles.active=dev
```
