package com.positivity.order.internal.entity;

/**
 * The movement types the drawer policy governs (CAP:550 S16, #2512; SPEC-accounting-workspace §4.6
 * "Drawer limits", AW19). Only petty expenses and vendor cash on delivery are configurable; a bank drop
 * is always allowed with no limit, and a float change is always allowed and always needs a manager.
 */
public enum SessionPolicyType {
    PETTY_EXPENSE(true),
    VENDOR_COD(true),
    BANK_DROP(false),
    FLOAT_CHANGE(false);

    private final boolean configurable;

    SessionPolicyType(boolean configurable) {
        this.configurable = configurable;
    }

    /** Whether the tenant's policy may switch this type and set its cashier limit. */
    public boolean configurable() {
        return configurable;
    }
}
