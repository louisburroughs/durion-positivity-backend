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
