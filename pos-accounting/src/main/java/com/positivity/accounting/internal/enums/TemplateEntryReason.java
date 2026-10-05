package com.positivity.accounting.internal.enums;

/** Why a template entry is in {@code CONFLICT} or {@code WITHHELD} (#2526). */
public enum TemplateEntryReason {
    /** The tenant's account under this code has another name or another type. */
    ACCOUNT_DIFFERS,
    /** The tenant's account under this code matches but is not active. */
    ACCOUNT_INACTIVE,
    /** The entry refers to an account code the tenant does not hold. */
    ACCOUNT_MISSING,
    /** The entry refers to an account whose own template entry is in conflict. */
    DEPENDS_ON_CONFLICT
}
