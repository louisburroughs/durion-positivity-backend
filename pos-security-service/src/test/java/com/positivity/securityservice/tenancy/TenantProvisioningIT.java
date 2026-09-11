package com.positivity.securityservice.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.exception.TemplateRoleImmutableException;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.service.RoleManagementService;
import com.positivity.securityservice.internal.service.RoleTemplateEntry;
import com.positivity.securityservice.internal.service.RoleTemplateService;
import com.positivity.securityservice.internal.service.TenantProvisioningService;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the WS2b provisioning path on the real schema (ADR-0062 §6 and §7): the platform tenant
 * holds the role template and PLATFORM_ADMIN after Flyway, and applying the template under a new
 * tenant's binding creates that tenant's roles, grants, administrator and first assignment while
 * touching nothing in alpha. {@code TENANT_A} is the alpha default tenant, {@code TENANT_B} the
 * tenant provisioned here.
 */
class TenantProvisioningIT extends PostgresTenancyTestBase {

    /** The six floor roles plus SUPPORT, the read-only role an impersonation token carries (WS2b-4). */
    private static final List<String> FLOOR = List.of(
            "ADMIN",
            "CONTROLLER",
            "DISPATCHER",
            "SELF_SERVICE_CUSTOMER",
            "SHOP_MANAGER",
            "SUPPORT",
            "SYSTEM_ADMINISTRATOR");

    @Autowired
    private RoleTemplateService roleTemplateService;

    @Autowired
    private TenantProvisioningService provisioningService;

    @Autowired
    private RoleManagementService roleManagementService;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private UserRepository users;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void thePlatformTenantHoldsTheTemplateAndItsOperator() {
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());

        List<RoleTemplateEntry> template = asTenant(PlatformTenant.ID, roleTemplateService::snapshot);
        assertThat(template).extracting(RoleTemplateEntry::name).containsExactlyElementsOf(FLOOR);
        assertThat(template)
                .allSatisfy(entry -> assertThat(entry.permissionNames())
                        .as("template role %s carries its grants", entry.name())
                        .isNotEmpty());
        assertThat(template.stream()
                        .filter(e -> e.name().equals("ADMIN"))
                        .findFirst()
                        .orElseThrow()
                        .permissionNames())
                .as("platform:* left alpha's ADMIN")
                .noneMatch(p -> p.startsWith("platform:"));

        assertThat(owner.queryForObject("""
                        SELECT count(*) FROM role_permissions rp JOIN roles r ON r.id = rp.role_id
                         WHERE r.tenant_id = ? AND r.name = 'PLATFORM_ADMIN'
                        """, Integer.class, PlatformTenant.ID))
                .as("platform:account:{create,read,update}, platform:tenant:{create,decommission,impersonate,"
                        + "provision,reactivate,read,suspend,update}")
                .isEqualTo(11);
        assertThat(owner.queryForObject("""
                        SELECT count(*) FROM role_assignments ra
                          JOIN users u ON u.id = ra.user_id JOIN roles r ON r.id = ra.role_id
                         WHERE u.tenant_id = ? AND u.username = 'admin.platform' AND r.name = 'PLATFORM_ADMIN'
                        """, Integer.class, PlatformTenant.ID)).isEqualTo(1);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM roles WHERE tenant_id = ? AND template_key IS NOT NULL",
                        Integer.class,
                        TENANT_A))
                .as("alpha's floor roles are template roles too")
                .isEqualTo(FLOOR.size());
    }

    @Test
    void provisioningANewTenantCopiesTheTemplateAndCreatesTheAdministrator() {
        String email = "owner-" + UUID.randomUUID() + "@acme.example";
        List<RoleTemplateEntry> template = asTenant(PlatformTenant.ID, roleTemplateService::snapshot);
        Map<String, Integer> templateGrantCounts = template.stream()
                .collect(java.util.stream.Collectors.toMap(
                        RoleTemplateEntry::name, e -> e.permissionNames().size()));

        TenantProvisioningService.Outcome first =
                asTenant(TENANT_B, () -> provisioningService.provision(TENANT_B, email, template));
        TenantProvisioningService.Outcome again =
                asTenant(TENANT_B, () -> provisioningService.provision(TENANT_B, email, template));

        assertThat(first.administratorCreated()).isTrue();
        assertThat(first.rolesCreated()).isEqualTo(FLOOR.size());
        assertThat(again).isEqualTo(new TenantProvisioningService.Outcome(0, false));

        asTenant(TENANT_B, () -> {
            List<Role> copied = roles.findByTemplateKeyIsNotNullOrderByNameAsc();
            assertThat(copied).extracting(Role::getName).containsExactlyElementsOf(FLOOR);
            for (Role role : copied) {
                assertThat(role.getTenantId()).isEqualTo(TENANT_B);
                assertThat(role.getTemplateKey()).isEqualTo(role.getName());
                assertThat(role.getPermissions())
                        .as("grants of %s", role.getName())
                        .hasSize(templateGrantCounts.get(role.getName()));
            }
            assertThat(users.findByUsername(email)).isPresent();
            assertThat(roleManagementService.getEffectiveRoleAssignments(
                            users.findByUsername(email).orElseThrow().getId()))
                    .extracting(a -> a.getRoleCode())
                    .containsExactly("ADMIN");
            Role admin = roles.findByName("ADMIN").orElseThrow();
            assertThatThrownBy(() -> roleManagementService.deleteRole(admin.getId()))
                    .isInstanceOf(TemplateRoleImmutableException.class);
        });

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        asTenant(
                TENANT_A,
                () -> assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM users WHERE username = ?", Integer.class, email))
                        .as("alpha did not receive the administrator")
                        .isZero());
    }
}
