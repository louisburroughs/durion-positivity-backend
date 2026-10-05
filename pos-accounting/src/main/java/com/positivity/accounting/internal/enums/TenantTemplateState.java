package com.positivity.accounting.internal.enums;

/** Where a tenant stands against the accounting template (#2526). */
public enum TenantTemplateState {
    /** The template has never been applied to this tenant. */
    NOT_PROVISIONED,
    /** Everything the template holds for this tenant is created or adopted. */
    UP_TO_DATE,
    /** The template is newer than the last apply; the next start, or the next reconcile, brings it in. */
    PENDING,
    /** At least one entry is in conflict or withheld and waits for the tenant to resolve it. */
    NEEDS_ATTENTION
}
