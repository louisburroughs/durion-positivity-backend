/**
 * Public contract surface of the pos-supplier module (ADR-0026, ADR-0049, architecture doc §5).
 *
 * <p>This package contains <em>interfaces only</em>; implementations live under
 * {@code com.positivity.supplier.internal..} and are invisible to other modules. Contract DTO
 * records live in {@link com.positivity.supplier.service.model}, mirroring the pos-invoice
 * {@code service.model} convention. Nothing in this package may reference
 * {@code com.positivity.supplier.internal..} types (enforced by the module
 * {@code ArchitectureTest}).
 *
 * <p>Per ADR-0026 D3 (#1541) this package holds only the module's granted contract surface.
 * Cross-module integration with pos-supplier is event-only per ADR-0044/ADR-0049 §3, with a
 * single approved synchronous exception — and therefore a single granted interface:
 * {@link com.positivity.supplier.service.SupplierStockService} (ADR-0044 amendment 2026-08-10,
 * ADR-0049 §4), with its request/response records in {@code service.model}. Interfaces consumed
 * only by the module's own controllers live beside their implementations under
 * {@code com.positivity.supplier.internal..}.
 */
package com.positivity.supplier.service;
