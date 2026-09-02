/**
 * Public contract surface of the pos-catalog module (ADR-0026 D1–D5).
 *
 * <p>This package contains <em>interfaces only</em>; implementations live under
 * {@code com.positivity.catalog.internal..} and are invisible to other modules. Contract DTO
 * records live in {@link com.positivity.catalog.service.model}. Nothing in this package may
 * reference {@code com.positivity.catalog.internal..} types (ADR-0026 D4, enforced by the
 * module {@code ArchitectureTest}).
 *
 * <p>Per ADR-0026 D2 this package holds only the module's granted contract surface. The single
 * grant is {@link com.positivity.catalog.service.ServiceLaborTimeService} — vehicle-specific
 * labor-time resolution at quote time (ADR-0044 amendment 2026-09-02, ADR-0058 §5): the
 * platform's second scoped synchronous cross-module read, granted file-scoped to
 * pos-workorder's {@code CatalogLaborTimeClientImpl} and enforced in the platform
 * {@code DomainWallsTest}. Everything else other modules need from pos-catalog rides events.
 */
package com.positivity.catalog.service;
