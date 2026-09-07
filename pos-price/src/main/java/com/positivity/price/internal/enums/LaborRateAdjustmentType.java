package com.positivity.price.internal.enums;

/**
 * How a labor-matrix step changes the running rate (#1575 Tier 0, T0-3).
 *
 * <p>{@link #PERCENT} compounds on the rate as it stands when the step is reached, so a matrix
 * of percentage steps is order-dependent — which is why {@code sequence} is part of the stored
 * row rather than a display concern.
 */
public enum LaborRateAdjustmentType {

    /** A percentage of the running rate, e.g. 15.0 meaning +15%. Negative values discount. */
    PERCENT,

    /** A flat amount added to the running rate in the rate's own currency. */
    FIXED
}
