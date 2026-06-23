package com.positivity.accounting.internal.enums;

/**
 * Fixed/Variable classification of a Labor &amp; Overhead cost line (CAP-316).
 *
 * <p>{@code FIXED_IF_LOW_VOLUME} corresponds to the source form's {@code *} annotation
 * ("Fixed if volume change is minimal").
 */
public enum CostType {
    FIXED,
    VARIABLE,
    FIXED_IF_LOW_VOLUME
}
