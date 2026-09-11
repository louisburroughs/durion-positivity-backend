package com.positivity.securityservice.internal.domain;

import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Roles that exist in a tenant but may never be assigned to a user (ADR-0062 §7, WS2b-4).
 *
 * <p>{@code SUPPORT} is the only one today. It is a floor role — provisioning copies it into every
 * tenant — but it is not a role anybody logs in as: a platform operator's impersonation token names
 * it directly, and the mint caps its grants at the read-only ceiling
 * ({@link SupportReadOnlyCeiling}). Nothing in the assignment surface knew that, so a tenant
 * administrator could grant {@code SUPPORT} to an ordinary user through {@code PUT
 * /v1/users/{username}/roles}, {@code assignUserRole}, {@code createRoleAssignment} or the user
 * bulk-ingest, and login would put the whole impersonation read surface on that user's own token —
 * permanently, and with none of the 15-minute expiry, ceiling or two-sided audit trail the token
 * path provides. The seed says "No user is ever assigned it"; this class is what makes that true.
 *
 * <p>The invariant is deliberately on the <em>assignment</em>, not the role: the role must stay
 * present and grantable-to-permissions, or an impersonation token would have nothing to resolve.
 */
public final class ReservedRoles {

    /** The fixed tenant role an impersonation token carries; seeded by {@code R__seed_reference_security.sql}. */
    public static final String SUPPORT = "SUPPORT";

    /**
     * The platform tenant's own administrator role, the sole holder of {@code platform:*}
     * (ADR-0062 §7, {@code R__seed_tenant_template.sql}). Unlike the floor roles and {@link
     * #SUPPORT}, it is seeded without a {@code template_key} on purpose: it must never join the
     * per-tenant role template, because {@code RoleTemplateService.snapshot()} reads every
     * {@code template_key}-marked platform-tenant role and {@code TenantProvisioningService} /
     * {@code RoleTemplateReconciliationService} then copy it, grants included, into every tenant.
     */
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    /** Role names no user-role grant, reconcile or import may hand to a user. */
    public static final Set<String> NOT_USER_ASSIGNABLE = Set.of(SUPPORT);

    /** Role names that may never become part of the per-tenant role template (see {@link #PLATFORM_ADMIN}). */
    private static final Set<String> NOT_TEMPLATE_ELIGIBLE = Set.of(PLATFORM_ADMIN);

    private ReservedRoles() {}

    /** Whether {@code roleName} may be assigned to a user. A null or unknown name is assignable here. */
    public static boolean isUserAssignable(@Nullable String roleName) {
        return roleName == null || !NOT_USER_ASSIGNABLE.contains(roleName);
    }

    /**
     * Whether {@code roleName} may join the per-tenant role template, by name alone (a role's
     * grants are checked separately: see {@code RoleManagementServiceImpl.provisionTemplateRole}).
     * A null name is eligible here — role creation validates blank names on its own.
     */
    public static boolean isTemplateEligible(@Nullable String roleName) {
        return roleName == null || !NOT_TEMPLATE_ELIGIBLE.contains(roleName.toUpperCase(Locale.ROOT));
    }
}
