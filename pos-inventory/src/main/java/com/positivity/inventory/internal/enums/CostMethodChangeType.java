package com.positivity.inventory.internal.enums;

/**
 * What kind of change one {@code cost_method_change_log} row records (#1535).
 *
 * <p>V26 built the log around a single event — "the method configured at this
 * scope changed from A to B" — which left retiring an override, the decisive
 * step of the SKU_CATEGORY cut-over, with no audit representation at all. This
 * enum is what makes deactivation and its reversal recordable.
 */
public enum CostMethodChangeType {

    /**
     * A method was configured or changed at this scope: the V26 event. {@code
     * fromMethod} is null on first configuration, {@code toMethod} is always
     * present.
     */
    METHOD_SET,

    /**
     * The configuration row was deactivated and stops participating in
     * resolution. {@code toMethod} is null — a retired override resolves to
     * nothing, it does not resolve to something else — and {@code fromMethod}
     * carries the method that was in force when it was retired.
     */
    DEACTIVATED,

    /**
     * A previously deactivated row was brought back at its existing method, so
     * {@code fromMethod} equals {@code toMethod}. Without this value the return
     * trip was invisible: the upsert only logs when the method changed, and on
     * reactivation it has not.
     */
    REACTIVATED
}
