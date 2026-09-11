package com.positivity.securityservice.internal.domain;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.exception.SecurityValidationException;
import com.positivity.tenancy.PlatformTenant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * ADR-0062 §7's sole-holder invariant for {@code platform:*}, exercised directly at the guard
 * (Copilot review of PR #1955, Findings 2-4): {@code security:role:edit} authorizes both
 * {@code assignPermissionToRole} and the role-permission bulk-ingest it backs, and neither refused
 * a {@code platform:*} grant to a non-{@code PLATFORM_ADMIN} role before this guard existed.
 */
class PlatformGrantGuardTest {

    private static final UUID TENANT_ROLE_TENANT = UUID.fromString("01900000-0000-7000-8000-000000000001");

    @Test
    @DisplayName("refuseGrantingPlatformPermission allows a non-platform permission on any role")
    void refuseGrantingPlatformPermission_allowsOrdinaryPermission() {
        Role role = roleNamed("SHOP_MANAGER", TENANT_ROLE_TENANT);
        assertThatNoException()
                .isThrownBy(() -> PlatformGrantGuard.refuseGrantingPlatformPermission(role, "crm:party:view"));
    }

    @Test
    @DisplayName("refuseGrantingPlatformPermission allows platform:* on PLATFORM_ADMIN in the platform tenant")
    void refuseGrantingPlatformPermission_allowsPlatformAdminInPlatformTenant() {
        Role role = roleNamed(ReservedRoles.PLATFORM_ADMIN, PlatformTenant.ID);
        assertThatNoException()
                .isThrownBy(() -> PlatformGrantGuard.refuseGrantingPlatformPermission(role, "platform:tenant:create"));
    }

    @Test
    @DisplayName("refuseGrantingPlatformPermission refuses platform:* on a tenant role, even one named PLATFORM_ADMIN")
    void refuseGrantingPlatformPermission_refusesPlatformAdminNameInATenantTenant() {
        // Same name, wrong tenant: the invariant is "PLATFORM_ADMIN in the platform tenant", not the
        // name alone -- a tenant could otherwise name a role PLATFORM_ADMIN and reach the exception.
        Role role = roleNamed(ReservedRoles.PLATFORM_ADMIN, TENANT_ROLE_TENANT);
        assertThatThrownBy(() -> PlatformGrantGuard.refuseGrantingPlatformPermission(role, "platform:tenant:create"))
                .isInstanceOf(SecurityValidationException.class)
                .hasMessageContaining("platform:tenant:create")
                .hasMessageContaining(ReservedRoles.PLATFORM_ADMIN);
    }

    @Test
    @DisplayName("refuseGrantingPlatformPermission refuses platform:* on any other role")
    void refuseGrantingPlatformPermission_refusesOtherRoles() {
        Role role = roleNamed("SHOP_MANAGER", TENANT_ROLE_TENANT);
        assertThatThrownBy(() -> PlatformGrantGuard.refuseGrantingPlatformPermission(role, "platform:account:read"))
                .isInstanceOf(SecurityValidationException.class)
                .hasMessageContaining("platform:account:read");
    }

    @Test
    @DisplayName("refuseRoleHoldingAPlatformPermission allows a role holding no platform:* grant")
    void refuseRoleHoldingAPlatformPermission_allowsCleanRole() {
        Role role = roleNamed("ADMIN", TENANT_ROLE_TENANT);
        role.getPermissions().add(permissionNamed("crm:party:view"));
        assertThatNoException().isThrownBy(() -> PlatformGrantGuard.refuseRoleHoldingAPlatformPermission(role));
    }

    @Test
    @DisplayName("refuseRoleHoldingAPlatformPermission refuses a role that already holds a platform:* grant")
    void refuseRoleHoldingAPlatformPermission_refusesAContaminatedRole() {
        Role role = roleNamed("ADMIN", TENANT_ROLE_TENANT);
        role.getPermissions().add(permissionNamed("platform:tenant:create"));
        assertThatThrownBy(() -> PlatformGrantGuard.refuseRoleHoldingAPlatformPermission(role))
                .isInstanceOf(SecurityValidationException.class)
                .hasMessageContaining("ADMIN")
                .hasMessageContaining("platform:*");
    }

    private static Role roleNamed(String name, UUID tenantId) {
        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setName(name);
        ReflectionTestUtils.setField(role, "tenantId", tenantId);
        return role;
    }

    private static Permission permissionNamed(String name) {
        Permission permission = new Permission();
        permission.setName(name);
        return permission;
    }
}
