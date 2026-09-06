package com.positivity.catalog.internal.enums;

/**
 * Who attached a product to a tread design (#1645), held on {@code product.tread_design_source}.
 *
 * <p>The distinction has exactly one job, and it is the reason the column exists: an automatic pass
 * may revise its own earlier guess, and must never revise a person's decision.
 */
public enum TreadDesignSource {

    /** The matcher attached it; a later automatic pass may re-point or clear it. */
    AUTO,

    /** A reviewer attached it through the resolve endpoint. Matching leaves it alone, permanently. */
    MANUAL
}
