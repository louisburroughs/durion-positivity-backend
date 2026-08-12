/**
 * Typed supplier-domain exceptions, mirroring the pos-warranty convention: each carries a
 * machine-readable {@code code} for the controller-layer {@code ApiError} mapping
 * (ADR-0017) arriving with the CAP-317 admin controllers. Class choice encodes the HTTP
 * material: not-found (404), validation (400), conflict (409); configuration failures
 * ({@code SupplierConfigurationException}) are consumed by supplier orchestration (slice 3)
 * as typed pre-network outcomes, never NPE/500 material.
 */
package com.positivity.supplier.internal.exception;
