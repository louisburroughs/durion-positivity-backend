/**
 * Contract DTO records and enums for the pos-supplier service interfaces (ADR-0026), following
 * the pos-invoice {@code service.model} convention.
 *
 * <p>These are the cross-package shapes of the module's public API: immutable records and enums
 * with no dependency on {@code com.positivity.supplier.internal..} — internal canonical-model
 * types never leak through the contract surface; implementations map between the two.
 *
 * <p>Secret handling per ADR-0050 §4: request records carry secret <em>references</em>
 * ({@code env:} / secret-store keys) as plain strings; response (view) records exclude
 * credential reference values entirely (write-only fields).
 */
package com.positivity.supplier.service.model;
