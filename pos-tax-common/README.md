# pos-tax-common

Shared DTO and validation library for consuming the `pos-tax` tax calculation API. Provides the request/response contract, jurisdiction types, and ISO validation annotations used by any service that calls `TaxCalculationService`. This is a library dependency, not a deployable service.

## Responsibilities

- Define `TaxCalculationRequest` and `TaxCalculationResponse` DTOs for tax API calls
- Provide `TaxLineItem` and `TaxJurisdiction` request/response types
- Expose `TaxReferenceType` and `TaxJurisdictionType` enumerations
- Provide `@IsoCountryCode`, `@IsoCurrencyCode`, and `@ValidSubdivisionForCountry` Bean Validation annotations with corresponding validators

## Key Classes

- `TaxCalculationRequest` — input: line items, postal code, state, city, country
- `TaxCalculationResponse` — output: subtotal, total tax, effective rate, per-jurisdiction breakdown, per-line breakdown
- `TaxCalculationResponse.LineItemTax.jurisdictions[]` — additive per-line jurisdiction rows, each a `JurisdictionTax {jurisdictionType (TaxJurisdictionType), code, rate, amount}`; the rows sum to the line's `taxAmount`. Never `null` (defaults to an empty list); existing `LineItemTax` fields are unchanged
- `TaxLineItem` — individual line item within a tax calculation request
- `TaxTypeCodes` — the shape of a tax-type code (CAP:550 S32a): 1–32 upper-case letters, digits or underscores. There is **no
  tax-type enum**: the vocabulary is configuration only, declared per country in pos-tax (`pos.tax.countries.<country>.tax-types`),
  so a new country needs no code change (platform owner direction, multi-national readiness). Readers copy a well-formed code
  as received and never infer one; `wellFormedOrNull` reads a malformed value as `null` (untyped)
- `JurisdictionTax.taxType` / `inputTaxRecoverable` and `TaxRateComponent.taxType` / `inputTaxRecoverable` — nullable; set only
  for a country with a tax-type profile in pos-tax, null for every other country (the US)
- `TaxTypesResponse` — the `GET /v1/tax/tax-types` answer: a country's configured tax types, regimes and currency
- `TaxProviderTransactionStatus.ESTIMATED` — a pos-tax log row recording which plug-in priced a committable document; commit
  and void never return it
- `IsoCountryCode` / `IsoCountryCodeValidator` — validates ISO 3166-1 alpha-2 country codes
- `IsoCurrencyCode` / `IsoCurrencyCodeValidator` — validates ISO 4217 currency codes

## Usage

Add to the consuming module's `pom.xml`:

```xml
<dependency>
    <groupId>com.positivity</groupId>
    <artifactId>pos-tax-common</artifactId>
</dependency>
```

Inject `TaxCalculationService` from `pos-tax` (when used as a library dependency) or call the `pos-tax` REST endpoint using these DTOs.

## Dependencies

No internal `pos-*` module dependencies. Depends on Lombok, JSpecify, and Jakarta Validation.

This module is a library dependency — there is no runnable service to start.
