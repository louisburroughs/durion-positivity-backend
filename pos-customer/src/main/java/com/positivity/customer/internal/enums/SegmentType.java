package com.positivity.customer.internal.enums;

/**
 * How a segment's membership is determined (Story #1137).
 *
 * <p>{@link #STATIC} is an explicit party list — reproducible, but frozen at the moment it
 * was built. {@link #DYNAMIC} evaluates a stored predicate at resolve time, so membership
 * tracks the data. A campaign that must reach exactly the parties a marketer reviewed wants
 * STATIC; a standing "fleet accounts on credit hold" audience wants DYNAMIC.
 */
public enum SegmentType {
    STATIC,
    DYNAMIC
}
