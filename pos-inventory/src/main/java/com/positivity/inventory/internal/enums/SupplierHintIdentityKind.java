package com.positivity.inventory.internal.enums;

/**
 * Which identifier a supplier stock hint is keyed on (CAP-322, #1312).
 *
 * <p>A {@code supplier.stockreport.updated} line carries up to three identifiers and the producer
 * guarantees only that at least one is non-null, so the identifiers alone are not a key. One is
 * elected per line following the producer's own precedence
 * ({@code SupplierStockReportLine.describeIdentity}), and the elected kind is stored alongside the
 * value so "the vendor identified this by EAN" stays a fact rather than an inference about which
 * columns are null.
 *
 * <ul>
 *   <li>{@code EAN} — the vendor stated a GTIN/EAN. The only kind pos-catalog can resolve
 *       deterministically, so the only kind that reaches the resolver.</li>
 *   <li>{@code BUYER_ARTICLE} — our own article code as the vendor holds it. Preferred over the
 *       vendor's code when no EAN is stated: it is the more stable identity from our side.</li>
 *   <li>{@code SUPPLIER_ARTICLE} — the vendor's own article code, an alias in the vendor's
 *       namespace and never an identifier of ours.</li>
 * </ul>
 */
public enum SupplierHintIdentityKind {
    EAN,
    BUYER_ARTICLE,
    SUPPLIER_ARTICLE
}
