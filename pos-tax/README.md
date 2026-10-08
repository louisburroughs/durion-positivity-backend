# pos-tax

> **Status: a collection of stubs.** pos-tax exists so that pos-accounting stories can begin. Its rates, rules and
> answers are placeholders, not tax law. Every tax question is held for expert advice: rates, which supplies are
> taxable, what is recoverable and at what share, evidence thresholds, registration formats, claim limits and filing.
> When accounting needs a new tax function, it is stubbed here and added to the [stub register](#stub-register).
> The platform owner set this rule on 2026-10-08 (durion `domains/accounting/SPEC-accounting-workspace.md`, AW48;
> louisburroughs/durion#553).
>
> **One tax port, pluggable providers (ADR-0071).** pos-tax picks a [provider plug-in](#provider-plug-ins) per tenant and
> country. People never call it: they reach it through front-door domain modules. Services call its computation directly.

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
- `GET /v1/tax/rates` — rate-only lookup for an address (`tax:rates:view`)
- `GET /v1/tax/tax-types?countryCode=` — a country's configured tax types, regimes and currency (`tax:rates:view`, service
  authority; CAP:550 S32a)
- `GET /v1/tax/evidence-rules?countryCode=&asOf=` — a country's evidence rules in effect on a date (`tax:rates:view`, service
  authority; CAP:550 S32b)
- `POST /v1/tax/plausibility-checks` — the stated-tax plausibility check of a receipt (`tax:rates:view`, service authority;
  CAP:550 S32b); the supplier's registration number is never echoed or logged
- `POST /v1/tax/transactions/{referenceId}/commit` and `/void` — provider document lifecycle (`tax:commit`)
- `GET /v1/tax/mode` — returns current operating mode (`test` or `production`)

Error codes beyond validation: 422 `TAX_JURISDICTION_NOT_CONFIGURED` (a profiled country has no rate row for the region on
the date), 422 `CURRENCY_NOT_SUPPORTED` (a calculation for a profiled country states another currency than the profile's;
ADR-0067 PC-9) and 501 `TAX_RATE_LOOKUP_UNSUPPORTED` (rate lookup on a deployment-wide provider other than test mode).

The plausibility check (CAP:550 S32b) refuses in this order (ADR-0017); the first step that fails answers and lists all of
its own field errors in `fieldErrors`, never with the rejected value:

| Status · code | When |
| --- | --- |
| 400 `VALIDATION_ERROR` | Request shape: a missing or malformed field, a negative stated amount, `receiptTotal` ≤ 0, or a regime stated twice |
| 422 `TAX_JURISDICTION_NOT_CONFIGURED` | The country has no tax profile (`fieldErrors[countryCode]`) |
| 422 `CURRENCY_NOT_SUPPORTED` | `currencyCode` is not the profile's currency (ADR-0067 PC-9; `fieldErrors[currencyCode]`) |
| 422 `AMOUNT_PRECISION_EXCEEDS_CURRENCY` | `receiptTotal` or a stated amount has more decimal places than the currency's minor unit (ADR-0067 PC-6); each offending amount is named. Trailing zeros do not count |
| 422 `TAX_REGIME_NOT_DECLARED` | A stated regime is not declared for the country (`fieldErrors[statedTaxes[i].regime]`) |
| 422 `TAX_AMOUNT_IMPLAUSIBLE` | A stated amount, or their sum, reaches the total, or an amount is above its maximum; each offending amount carries its maximum, the sum is `fieldErrors[statedTaxes]` |

## Provider plug-ins

ADR-0071 (durion `docs/adr/0071-tax-per-tenant-pluggable-providers.adr.md`; spec AW58, AW59) decides how pos-tax is reached and
which engine answers. Today the interim per-country default `pos.tax.default-providers.<country>` routes a configured country to
its plug-in in every provider mode (CAP:550 S32a); every other country keeps the deployment-wide switch (`TAX_TEST_MODE`,
`pos.tax.provider`). The plug-in binding below (ADR-0071 step 1, louisburroughs/durion-positivity-backend#2629) replaces both
switches and makes the default map its fallback.

| Plug-in | What it is | Status |
| --- | --- | --- |
| `US_SELF` | The test-mode calculator: configured placeholder rates (stubs) | Exists, as test mode |
| `<country>_SELF` | The configuration-driven self-hosted plug-in, one per country profiled under `pos.tax.countries` (`SelfHostedTaxPlugin`): typed placeholder rates from the profile's rows; commit and void are logged no-ops | Exists (CAP:550 S32a, #2636), routed by the per-country default |
| `CA_SELF` | The first configured country's self-hosted plug-in (typed rates; number shape, evidence rule and plausibility from S32b; registration status follows in S32c) | Exists (#2636), routed by the per-country default `pos.tax.default-providers.CA`; no rate ships |
| `AVALARA` | The AvaTax adapter (`AvalaraTaxProvider`) | Exists; no environment enables it |

- **Binding.** A tenant-scoped `tax_provider_binding` names the plug-in per tenant and country (`countryCode`, `providerId`,
  `providerProfile`, effective dates). It resolves from the country of `destinationAddress` as of the transaction date; with no
  binding, `pos.tax.default-providers.<country>` applies (`US` → `US_SELF`, `CA` → `CA_SELF`), and with neither, 422
  `TAX_JURISDICTION_NOT_CONFIGURED`. The provider transaction log keeps the plug-in that priced each document, so commit, void
  and refund use it after a binding change.
- **Accounts.** One platform-held account per provider; its credential lives in the secret store, never per tenant.
  `providerProfile` is the tenant's non-secret company or profile code on that account.
- **Capabilities.** A capability the bound plug-in lacks is refused with 422 `TAX_CAPABILITY_UNSUPPORTED` naming it; `501` stays
  for documented stub endpoints (ADR-0017), so today's 501 `TAX_RATE_LOOKUP_UNSUPPORTED` moves to that code when the binding lands.
- **Front doors.** People reach pos-tax only through a domain module that checks their permission and forwards the actor:
  registrations through pos-accounting, exemption certificates through pos-customer, provider bindings through pos-tenant. Each
  write endpoint accepts only its front door, authenticated by a per-caller shared secret. Computation (calculate, refund,
  commit, void, rate lookup, plausibility) stays a direct call from pos-order, pos-workorder, pos-invoice, pos-mcp-server and
  pos-accounting.
- **Facts.** Registrations are published as `tax.registration.changed` v1 on `tax.events.v1` through an outbox, with the
  per-tenant manifest `tax.manifest.v1` (AW58).

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
| Rate lookup | `GET /v1/tax/rates` (`tax:rates:view`) | A country routed by `pos.tax.default-providers` is answered by its plug-in in every mode (typed rows, below). Otherwise test mode answers from the configured rates, and any other provider answers 501 `TAX_RATE_LOOKUP_UNSUPPORTED` | none in code | Real rates |
| Provider document lifecycle | `POST /v1/tax/transactions/{referenceId}/commit` and `/void` (`tax:commit`) | Test mode: a no-op that always succeeds, logged in `tax_provider_transaction`. The AvaTax adapter exists, but no environment enables it | pos-invoice | Filing and the provider choice |
| Exemption certificates | `/v1/tax/exemption-certificates` (`tax:exemption:view`, `tax:exemption:manage`) | A tenant registry. A claim without an active certificate is taxed and flagged, never refused | none outside pos-tax; pos-customer becomes its front door (ADR-0071, no story yet) | Which exemptions are valid, and what evidence they need |
| Use tax (planned) | `/calculate` with `calculationType = USE` (AW44; louisburroughs/durion-positivity-backend#2604) | Priced exactly like `SALE`; test mode always answers | pos-accounting | Which purchases owe use tax, per-state rules, filing (louisburroughs/durion-positivity-backend#2599) |
| Typed rates (per-country profile) | `GET /v1/tax/rates`, `POST /v1/tax/calculate` and `GET /v1/tax/tax-types` for a profiled country; rate rows and line rows carry `taxType` and `inputTaxRecoverable` (AW57; CAP:550 S32a, louisburroughs/durion-positivity-backend#2636) | Built. The country's plug-in answers in every provider mode from its configured rows, `source = STUB`: one typed component or row per tax type in effect for the region on the date, HALF_UP at the currency exponent per row; no row → 422 `TAX_JURISDICTION_NOT_CONFIGURED`, never another country's rates. An exemption claim is taxed and flagged. **No rate ships**: the first configured country (`CA`) has placeholder tax types, regimes and recoverability only. Other countries (the US) are unchanged, with both fields null | pos-accounting, pos-invoice (the `taxType` hand-off) | Rates, which supplies are taxable or exempt, what is recoverable, how taxes stack, the tax-type list and regime grouping |
| Tax registration status (planned) | A tenant's registration per regime (`GST_HST`, `QST`) as of a date (AW49, AW57) | Effective-dated, overlap refused; written only by pos-accounting, the front door (AW59); published as `tax.registration.changed` by outbox (AW58) | pos-accounting, pos-order (replicas) | Registration rules |
| Registration-number shape | `wellFormed(regime, number)`, reached through `POST /v1/tax/plausibility-checks` and the tenant-registration writes (`RegistrationNumberShapes`; CAP:550 S32b, louisburroughs/durion-positivity-backend#2637) | Configured template per regime (`#` digit, letters literal); no shape → startup fails; a shape without a letter → startup fails; nothing passes by default; shapes change only by a reviewed commit | pos-order (drawer entry), pos-tax registrations (via pos-accounting) | Number formats |
| Evidence rule | `GET /v1/tax/evidence-rules?countryCode=&asOf=` (`tax:rates:view`, service authority; AW53; CAP:550 S32b, #2637) | Built. `pos.tax.countries.<country>.evidence-rules`, `source = STUB`: the rules in effect on `asOf` (default today), amounts in the profile's currency; a country with none → an empty list. The first configured country (`CA`) ships one placeholder row: `SUPPLIER_REGISTRATION_NUMBER` from 100.00, `appliesTo [DRAWER_RECEIPT, VENDOR_BILL]`, undated; no $500 tier. A caller that cannot obtain the rule retries or holds, never treats it as absent (AW49) | pos-accounting, pos-order | The threshold, the $500 tier, what is compared, whether bills are in scope |
| Receipt-tax plausibility | `POST /v1/tax/plausibility-checks` (`tax:rates:view`, service authority; AW55; CAP:550 S32b, #2637) | Built, keyed by regime. Per stated amount: 422 `TAX_AMOUNT_IMPLAUSIBLE` when it, or the sum of all, reaches the total `T`, or when it is above `T × r / (1 + r)` rounded up to the minor unit plus `pos.tax.plausibility.tolerance-minor-units` (placeholder 5); `r` is decided per regime (Accounting ruling on #2637, comment 6071110619): **rated** — a row of the regime's tax types is in effect in the region on `asOf`, use its rate; **not levied** — no row and the regime does not cover the region (its `regions` are neither empty nor contain it), `r = 0`, so the maximum is the tolerance; **unrated** — no row but the regime covers the region, no rate bound, and the regime is in neither `ratesUsed` nor `maximums`. The total check always applies; no combined bound. `RATE_UNAVAILABLE` when at least one stated amount above zero is unrated, otherwise `PLAUSIBLE` (zero or absent amounts included). Refusals in order: 400 shape (including a repeated regime), 422 `TAX_JURISDICTION_NOT_CONFIGURED`, `CURRENCY_NOT_SUPPORTED`, `AMOUNT_PRECISION_EXCEEDS_CURRENCY`, `TAX_REGIME_NOT_DECLARED`, `TAX_AMOUNT_IMPLAUSIBLE`. Also answers `supplierRegistrationRequired` (the evidence rule for `DRAWER_RECEIPT`) and `supplierRegistrationNumberWellFormed` (the country's `supplier-registration-regime` shape, or null). Pure: no tenant data, no state, no event; the number is never echoed, logged or stored. A bookkeeping control against typing errors, not a tax rule | pos-order (drawer entry) | How taxes stack on one receipt |

## Configuration

| Property                                 | Default          | Description                              |
| ---------------------------------------- | ---------------- | ---------------------------------------- |
| `pos.tax.default-providers.<country>`    | `CA: CA_SELF`    | Interim per-country default plug-in (see below) |
| `pos.tax.countries.<country>`            | `CA` placeholders, no rates | Per-country tax profile (see below) |
| `pos.tax.countries.<country>.supplier-registration-regime` | `CA: GST_HST` | Regime whose shape a supplier's number must match (S32b) |
| `pos.tax.countries.<country>.evidence-rules` | `CA`: one placeholder row | Evidence rules (S32b, see below) |
| `pos.tax.registration.formats`           | `GST_HST`, `QST` placeholders | Registration-number shape per regime; shipped configuration only (S32b, see below) |
| `pos.tax.plausibility.tolerance-minor-units` | `5` | Minor units added to each plausible maximum; required (S32b) |
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

### Per-country profiles and default providers (CAP:550 S32a)

Every value here is a **placeholder held for expert advice** (AW48, OI-4), never tax law. Code names no country, regime or tax
type: adding a country is a configuration block and a `default-providers` entry. Tax-type codes are a configuration-only
vocabulary (no enum; `TaxTypeCodes` in pos-tax-common only fixes their shape, 1–32 upper-case letters, digits or underscores);
consumers store the code as a string, so a new code needs no code change or migration anywhere.

```yaml
pos.tax:
  default-providers:
    XX: XX_SELF                 # the self-hosted plug-in of a profiled country is <country>_SELF; every profiled country needs one
  countries:
    XX:                         # upper case; cannot be set through environment variables (they lower-case the key)
      currency: EUR             # ISO 4217; its minor-unit exponent is the rounding scale
      tax-types:                # a list with explicit codes (1-32 upper-case letters, digits or underscores)
        - { code: ZZ_LEVY, regime: R_1, jurisdiction-type: COUNTRY, input-tax-recoverable: true }
        - { code: ZZ_LOCAL, jurisdiction-type: PROVINCE, input-tax-recoverable: false }   # no regime
      regimes:                  # what a tenant registers under and recovery is keyed by
        - { code: R_1, regions: [] }   # empty = the whole country
      rates:                    # none ship; tests and dev use fixtures marked "not tax law"
        - { region-code: X1, tax-type: ZZ_LEVY, rate: 0.01, effective-from: 2026-01-01, effective-to: 2026-12-31 }
```

Tax types and regimes are **lists with an explicit `code`**, not map keys: Spring's relaxed binding strips `_` from an
unbracketed map key, so a code would be silently mangled. Country codes stay map keys and must be upper case.

- **Routing.** An address whose country has a `default-providers` entry is answered by that plug-in for rate lookup,
  calculation (sale and refund), commit and void, in every provider mode. Every other country keeps the switch above. A plug-in
  serves only its own country.
- **Rows.** `effective-from` and `effective-to` are inclusive; `effective-to` is optional. The rows of the destination region in
  effect on the date answer, one per tax type; with none, 422 `TAX_JURISDICTION_NOT_CONFIGURED`. A calculation must state the
  profile's currency (rows round at its exponent and are never converted), otherwise 422 `CURRENCY_NOT_SUPPORTED`.
- **Startup check** (`TaxCountryProfiles`). Startup fails, naming the property, when a country code is not ISO 3166-1 alpha-2
  (assigned or user-assigned, so a fixture may use `ZZ`) or is not upper case; a currency is missing or not ISO 4217; a
  tax-type or regime `code` is missing, malformed (1–32 upper-case letters, digits or underscores) or declared twice; a tax type names an undeclared regime, lacks `jurisdiction-type` or `input-tax-recoverable`; a region code is not 1–3
  letters or digits; a rate row names an undeclared tax type, has a rate outside [0, 1), lacks `effective-from` or ends before it
  starts; two rows of one region and tax type, or of one region and regime, are in effect on the same date (one rate per regime);
  a `default-providers` key is not alpha-2 or names a plug-in other than its own country's `<country>_SELF` (or a country
  with no profile); or a profiled country has no `default-providers` entry (it would otherwise fall through to the switch).
- **Lifecycle log.** A committable calculation priced by a plug-in records an `ESTIMATED` row in `tax_provider_transaction`
  naming the plug-in (`provider`), so its commit and void reach the same plug-in as logged no-ops; the re-commit job ignores
  `ESTIMATED` rows. A re-price by another provider re-points only a row with no live provider document (`ESTIMATED`,
  `VOIDED`); a `PENDING_COMMIT`, `FAILED` or `COMMITTED` row always stays with the provider that owns its document. A re-point
  clears the previous provider's `external_transaction_id` and is logged at INFO (`Tax document re-pointed: referenceId=…
  fromProvider=… toProvider=… status=… priorExternalTransactionId=…`); durable history is ADR-0071 §3's log (#2629). Any
  logged provider id other than the switch's `TEST_MODE`, `EXTERNAL` and `AVALARA` is treated as a self-hosted plug-in. A logged `<country>_SELF` keeps its documents even if its profile is later removed (its no-op commit
  and void need no profile); only rows priced by the switch fall back to it. No new column was needed: `provider` already names the provider that owns each document.
- **Callers (ADR-0021 §3).** pos-order, pos-invoice and pos-accounting call computation and the tax-types read directly with the
  service authority; there is no gateway route.

### Registration shapes, evidence rules and plausibility (CAP:550 S32b)

Every value is a **placeholder held for expert advice** (OI-4). Code names no country, regime or tax type; a new country is
configuration only.

```yaml
pos.tax:
  registration:
    formats:                    # a list with an explicit regime code (a map key would lose its "_")
      - { regime: R_1, shape: "XX#####" }   # '#' = one digit, A-Z literal; at least one letter
  plausibility:
    tolerance-minor-units: 5
  countries:
    XX:
      supplier-registration-regime: R_1     # optional; a country without one answers wellFormed = null
      evidence-rules:                       # in the profile's currency
        - { rule: SUPPLIER_REGISTRATION_NUMBER, from-amount: 100.00, applies-to: [DRAWER_RECEIPT, VENDOR_BILL],
            effective-from: 2026-01-01, effective-to: 2026-12-31 }   # dates optional, inclusive
```

- **Shapes are a security control** (ADR-0072 Decision 1, conditions (a) and (b); Security decision on durion#571). A number
  must equal the shape character for character after normalisation. A regime with no shape refuses every number. A shape is the
  service's shipped configuration, changed only by a reviewed commit: never per tenant, by a tenant, by a tax provider or at
  runtime. pos-tax never logs, stores or returns a number that fails.
- **Normalisation, the exact rule** (S32d's pos-order and S32c re-implement exactly this): `String.trim()` (drop leading and
  trailing characters at or below U+0020); then remove every U+0020 SPACE and U+002D HYPHEN-MINUS, and nothing else; then
  upper-case `a`–`z` only (no other character is case-mapped, so a non-ASCII letter or digit never matches). A number longer
  than 128 characters is never well formed and is not normalised; callers must not add a bean-validation size constraint for
  it, because its binding error echoes the value.
- **Where a shape may come from.** At startup every bound value under `pos.tax.registration` must originate in a classpath file
  named `application*.yml` or `application*.yaml` (at the root or under `config/`) inside the service's own code source: the
  shipped `application.yml`, a shipped profile file such as `application-dev.yml`, and any document of a multi-document file
  qualify. Startup fails, naming the property and the source, for an environment variable, a system property, a command-line
  argument, an external file, a file in another jar, or a property source that cannot list its names (only the `random` source
  and property-source stubs, which hold no value, are exempt).
- **Startup check, shapes** (`RegistrationNumberShapes`). Startup fails, naming the property, when a regime declared in any
  country profile has no shape; a shape is blank, longer than 32, contains a character other than `#` or `A`–`Z`, or contains no
  letter (the only kind that can match bare digits, so a nine-digit SSN/SIN/EIN/ITIN shape cannot be configured); a `regime` code
  is malformed or has two shapes; or a `supplier-registration-regime` is not declared for its country.
- **Startup check, evidence rules** (`TaxEvidenceRules`). Startup fails, naming the property, on an unknown `rule`
  (`SUPPLIER_REGISTRATION_NUMBER`) or `applies-to` value (`DRAWER_RECEIPT`, `VENDOR_BILL`); a `from-amount` that is missing, not
  above zero or finer than the currency's minor unit; an empty `applies-to`; an end before its start; or two rows of one rule
  and document type in effect on the same date.
- **Plausibility** (`TaxPlausibilityService`) also fails startup without a tolerance of zero or more. Its counter
  `pos.tax.plausibility.outcome` is tagged by `outcome` only (`PLAUSIBLE`, `RATE_UNAVAILABLE`, `TAX_AMOUNT_IMPLAUSIBLE`).

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

`pos-tax` is one internal service (ADR-0071): callers reach it at a fixed base URL (`POS_TAX_BASE_URL`, `invoice.tax.base-url`; Eureka registration
disabled), and it must never be added to the API gateway routes or given an SDK package (ADR-0021).

## Development

```bash
./mvnw -pl pos-tax -am spring-boot:run --spring.profiles.active=dev
```
