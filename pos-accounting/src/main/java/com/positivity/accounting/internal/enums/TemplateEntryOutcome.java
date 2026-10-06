package com.positivity.accounting.internal.enums;

/**
 * What happened to one template entry in one tenant (#2526).
 *
 * <p>{@link #CREATED}, {@link #ADOPTED} and {@link #REFRESHED} are settled: the entry is never
 * applied again. {@link #CONFLICT} and {@link #WITHHELD} are open: the applier looks at the entry
 * again on every run, and it settles once the tenant has resolved the clash.
 */
public enum TemplateEntryOutcome {
    /** The tenant lacked the row; the applier created it. */
    CREATED,
    /** The tenant already held a matching row; nothing was written to it. */
    ADOPTED,
    /** A statement line the tenant had not touched took the template's new presentation. */
    REFRESHED,
    /** The tenant holds an account under the template's code that is not the template's account. */
    CONFLICT,
    /** The entry refers to an account that is in conflict or missing, so it was not created. */
    WITHHELD;

    /** True when the entry needs a person: it is neither created nor adopted. */
    public boolean needsAttention() {
        return this == CONFLICT || this == WITHHELD;
    }
}
