package com.positivity.tenant.internal.enums;

/** Lifecycle of the customer account that owns tenants (ADR-0062 §7). */
public enum AccountStatus {
    ACTIVE,
    SUSPENDED,
    CLOSED
}
