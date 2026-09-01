package com.positivity.catalog.internal.enums;

/**
 * What kind of published time a labor standard row carries (#1569, sourcing plan §2 rule 7).
 *
 * <p>Warranty time and retail time differ for the same operation, and both can be stored
 * simultaneously; resolution picks by policy, storage keeps all.
 */
public enum LaborTimeType {
    /** Aggregator flat-rate (book) time for customer-pay work. */
    RETAIL_FLAT_RATE,
    /** OEM warranty-manual allowance; controls warranty claims. */
    OEM_WARRANTY,
    /** Parts-manufacturer installation time (e.g. tire operations). */
    MANUFACTURER_INSTALL,
    /** Durion-owned standard: hand-authored or promoted from shop actuals. */
    DURION_STANDARD
}
