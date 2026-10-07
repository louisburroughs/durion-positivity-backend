package com.positivity.accounting.internal.enums;

/** What one petty-expense category command changed (#2511): one history row each. */
public enum PettyExpenseCategoryChangeType {
    /** The category and its key and GL mapping were created (by the API or the tenant template). */
    CREATE,
    /** The label or the examples changed; the code never does. */
    RELABEL,
    /** The category was deactivated; its mapping is kept. */
    DEACTIVATE,
    /** The category's account changed from an effective date. */
    REMAP
}
