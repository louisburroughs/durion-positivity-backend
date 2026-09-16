package com.positivity.shopmanager.internal.enums;

/**
 * DECISION-SHOPMGMT-002's two severities (CAP-326, durion#483). HARD blocks and cannot be
 * overridden; SOFT warns and is overridable by a holder of {@code shop:conflict:override}.
 * Severity is a property of the rule, never of a (rule, operation) pair — spec D10.
 */
public enum ConflictSeverity {
    HARD,
    SOFT;

    public boolean isOverridable() {
        return this == SOFT;
    }
}
