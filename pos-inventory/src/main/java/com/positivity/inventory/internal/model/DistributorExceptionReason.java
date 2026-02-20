package com.positivity.inventory.internal.model;

/**
 * Reason codes for distributor feed normalization exceptions.
 *
 * Issue: CAP-170 (#47)
 */
public enum DistributorExceptionReason {
    SKU_UNMAPPED,
    LEAD_TIME_UNPARSABLE,
    REGION_UNMAPPED,
    REGION_AMBIGUOUS,
    MALFORMED_DATA
}
