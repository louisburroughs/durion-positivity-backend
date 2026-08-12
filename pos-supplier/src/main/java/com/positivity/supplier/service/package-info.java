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
 * <p>Cross-module integration with pos-supplier is event-only per ADR-0044/ADR-0049 §3, with a
 * single approved synchronous exception:
 * {@link com.positivity.supplier.service.SupplierStockService} (ADR-0049 §4). All other
 * interfaces here are consumed by the module's own controllers (frontend-facing via the gateway,
 * ADR-0011/0014) — they are not licenses for synchronous cross-module calls.
 */
package com.positivity.supplier.service;
