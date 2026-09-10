package com.positivity.domainevents.tenant;

/**
 * Event types on {@code tenant.events.v1} (ADR-0062 §7, ADR-0044 §3).
 *
 * <p>{@code pos-tenant} owns the tenant registry and publishes every type here except {@link
 * #PROVISIONED}, which {@code pos-security-service} emits on the same topic once it has applied the
 * role template and created the initial administrator for a new tenant. Every payload carries the
 * public projection only ({@code id}, {@code slug}, {@code displayName}, {@code status}); account,
 * contact and billing data never leave {@code pos-tenant}.
 */
public final class TenantEventTypes {

    /** A tenant was registered; status {@code PENDING} until provisioning completes. */
    public static final String CREATED = "tenant.created";

    /** {@code pos-security-service} finished provisioning the tenant's users and roles. */
    public static final String PROVISIONED = "tenant.provisioned";

    /** Display name, cell, or status changed (including {@code PENDING} to {@code ACTIVE}). */
    public static final String UPDATED = "tenant.updated";

    /** The tenant was suspended: logins are refused until reactivation. */
    public static final String SUSPENDED = "tenant.suspended";

    /** A suspended tenant is {@code ACTIVE} again. */
    public static final String REACTIVATED = "tenant.reactivated";

    /** The tenant reached its terminal state. */
    public static final String DECOMMISSIONED = "tenant.decommissioned";

    private TenantEventTypes() {}
}
