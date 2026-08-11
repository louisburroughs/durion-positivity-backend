/**
 * Vendor-neutral canonical supplier model (ADR-0049 §1, architecture doc §4.1).
 *
 * <p>Framework-clean by mandate: no Spring, JPA, or web types may appear in this package
 * (ADR-0051 §2 — adapters depend on this model, never the reverse; enforced by the module
 * {@code ArchitectureTest}). Vendor-native references ({@code SupplierOrderNumber},
 * {@code DocumentID}) are carried as attributes, never as identifiers (ADR-0013/0027).
 *
 * <p>The canonical DTOs here are deliberately pragmatic/minimal for the CAP-317 foundation
 * slice; they grow only when a <em>business</em> capability grows, never to mirror a wire
 * format (ADR-0051 §4).
 */
package com.positivity.supplier.internal.domain.model;
