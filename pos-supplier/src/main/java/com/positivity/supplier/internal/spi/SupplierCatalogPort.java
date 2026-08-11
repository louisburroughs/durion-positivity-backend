package com.positivity.supplier.internal.spi;

/**
 * Capability port: EDIWheel marketing catalog (MKCAT — tread designs, suppliers, images,
 * change subscription) ({@code MARKETING_CATALOG}; EDIWheel C1.2 JSON, ediwheel.net).
 *
 * <p>Governing ADRs: ADR-0049 §1, ADR-0051 §3 — the port and registry slot are reserved up
 * front so the {@code MARKETING_CATALOG} capability is a registry key from day one.
 * Operations ({@code fetchMarketingCatalog}, {@code subscribeChanges} — architecture doc
 * §6.1) are defined when the marketing-catalog capability is implemented; no canonical
 * marketing-catalog model exists in the CAP-317 foundation slice.
 */
public interface SupplierCatalogPort {}
