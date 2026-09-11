package com.positivity.securityservice.internal.domain;

import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.exception.SecurityValidationException;
import com.positivity.tenancy.PlatformTenant;
import org.jspecify.annotations.NonNull;

/**
 * ADR-0062 §7's sole-holder invariant for {@code platform:*} permissions: held only by {@link
 * ReservedRoles#PLATFORM_ADMIN} in the platform tenant, never by a tenant role. Two refusals guard
 * it from the two angles a grant can reach a role:
 *
 * <ul>
 *   <li>{@link #refuseGrantingPlatformPermission} — at grant time, wherever a permission can be
 *       added to a role's grant set: {@code RoleManagementServiceImpl.assignPermissionToRole} (and,
 *       through it, the role-permission bulk ingest) and {@code updateRolePermissions}. Without this
 *       the invariant depended only on the caller holding {@code security:role:edit} being trusted
 *       never to name a {@code platform:*} permission for another role — true of the documented
 *       platform role-template load, not of the endpoint's authority alone (Copilot review, PR
 *       #1955).
 *   <li>{@link #refuseRoleHoldingAPlatformPermission} — at template-marking time, before a role's
 *       {@code template_key} is set: {@code RoleManagementServiceImpl.provisionTemplateRole} and
 *       {@code RoleTemplateReconciliationService}. A template-marked platform-tenant role is copied,
 *       grants included, into every tenant by provisioning and reconciliation, so this defends
 *       against a role that already holds {@code platform:*} through some other path (a fixture, an
 *       import, or a gap since closed) rather than assuming the grant-time guard is the only door.
 * </ul>
 */
public final class PlatformGrantGuard {

    /** One segment of every {@code platform:*} permission (ADR-0062 §7). */
    private static final String PLATFORM_PERMISSION_PREFIX = "platform:";

    private PlatformGrantGuard() {}

    /**
     * Refuses granting {@code permissionName} to {@code role} when it is a {@code platform:*}
     * permission and {@code role} is not {@link ReservedRoles#PLATFORM_ADMIN} in the platform
     * tenant. A non-{@code platform:*} permission, or a grant to the platform tenant's own
     * {@code PLATFORM_ADMIN}, is always allowed.
     */
    public static void refuseGrantingPlatformPermission(@NonNull Role role, @NonNull String permissionName) {
        if (!permissionName.startsWith(PLATFORM_PERMISSION_PREFIX) || isPlatformAdminInPlatformTenant(role)) {
            return;
        }
        throw new SecurityValidationException("Permission " + permissionName + " is a platform:* permission"
                + " (ADR-0062 section 7) and may be granted only to " + ReservedRoles.PLATFORM_ADMIN + " in the"
                + " platform tenant.");
    }

    /**
     * Refuses a role that already holds a {@code platform:*} permission — checked before marking a
     * role as a template role, since {@code RoleTemplateService.snapshot()} copies every marked
     * role's grants into every tenant.
     */
    public static void refuseRoleHoldingAPlatformPermission(@NonNull Role role) {
        boolean holdsPlatformGrant = role.getPermissions().stream()
                .map(Permission::getName)
                .anyMatch(name -> name.startsWith(PLATFORM_PERMISSION_PREFIX));
        if (holdsPlatformGrant) {
            throw new SecurityValidationException("Role " + role.getName() + " holds a platform:* permission"
                    + " (ADR-0062 section 7) and may never join the per-tenant role template: its grants would"
                    + " then be copied into every tenant by provisioning and reconciliation.");
        }
    }

    private static boolean isPlatformAdminInPlatformTenant(Role role) {
        return ReservedRoles.PLATFORM_ADMIN.equalsIgnoreCase(role.getName())
                && PlatformTenant.isPlatform(role.getTenantId());
    }
}
