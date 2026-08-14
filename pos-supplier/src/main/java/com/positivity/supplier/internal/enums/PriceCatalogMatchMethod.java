package com.positivity.supplier.internal.enums;

/**
 * How a staged PRICAT line was resolved to a catalog product (ADR-0053 §5).
 *
 * <p>The order of the matched constants is the match order itself, and every one of them is an
 * exact match. There is deliberately no fuzzy or heuristic constant: a line that matches nothing
 * is quarantined as {@link #UNMATCHED} rather than attached to a best guess, because a wrong
 * product attachment silently mis-prices a real purchase.
 */
public enum PriceCatalogMatchMethod {
    /** Exact match of the line's EAN against a catalog product code of type EAN. */
    EAN,

    /** Exact match of the line's cross-reference code against a catalog product code of type UPC. */
    UPC_XREFERENCE,

    /** No product carries any identifier the line supplied; the line is quarantined. */
    UNMATCHED
}
