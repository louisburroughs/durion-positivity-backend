/**
 * Capability ports — the SPI protocol adapters implement (ADR-0051, architecture doc §6).
 *
 * <p>Ports are <em>capability semantics</em>: one narrow interface per business capability,
 * taking a {@code SupplierRef}, a {@code PartyContext} where party-relevant, and canonical
 * request records, returning canonical result records wrapped with {@code ExchangeMetadata}.
 * Codecs ({@link com.positivity.supplier.internal.spi.SupplierAdapterCodec}) are the
 * <em>registration unit</em> of the adapter registry, keyed by
 * {@code (capability, protocolFamily, version)} (ADR-0051 §3).
 *
 * <p>Framework-clean by mandate: no Spring, JPA, or web types in this package (enforced by
 * the module {@code ArchitectureTest}). Adapters may depend on this package and on
 * {@code internal.domain.model}, never the reverse (ADR-0051 §2).
 */
package com.positivity.supplier.internal.spi;
