package com.positivity.securityservice.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.securityservice.internal.dto.RoleTemplateReconcileResponse;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.exception.TemplateRoleImmutableException;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.service.RoleManagementService;
import com.positivity.securityservice.internal.service.RoleTemplateEntry;
import com.positivity.securityservice.internal.service.RoleTemplateReconciliationService;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the WS2b provisioning path on the real schema (ADR-0062 §6 and §7): the platform tenant
 * holds the role template and PLATFORM_ADMIN after Flyway, and applying the template under a new
 * tenant's binding creates that tenant's roles, grants, administrator and first assignment while
 * touching nothing in alpha. {@code TENANT_A} is the alpha default tenant, {@code TENANT_B} the
 * tenant provisioned here.
 */
class TenantProvisioningIT extends PostgresTenancyTestBase {

    /**
     * The six floor roles plus SUPPORT, the read-only role an impersonation token carries (WS2b-4),
     * plus the two accounting-workspace roles of CAP:550 S3 (#2504, AW4): ACCOUNTING_CLERK and
     * GENERAL_MANAGER. Sorted by name, as {@code RoleTemplateService.snapshot} returns them.
     */
    private static final List<String> FLOOR = List.of(
            "ACCOUNTING_CLERK",
            "ADMIN",
            "CONTROLLER",
            "DISPATCHER",
            "GENERAL_MANAGER",
            "SELF_SERVICE_CUSTOMER",
            "SHOP_MANAGER",
            "SUPPORT",
            "SYSTEM_ADMINISTRATOR");

    /**
     * Grants {@code R__seed_tenant_template.sql} gives PLATFORM_ADMIN: the eleven {@code platform:*}
     * families of ADR-0062 §7 (including {@code platform:tenant:impersonate}, WS2b-4), plus the four
     * the platform operator needs to load the role template through pos-bulk-loader (plan WS8). The
     * seed's own section-4 guard counts the same number; {@code PlatformOperatorGrantsTest} in
     * pos-bulk-loader pins which four the loader's endpoints require.
     */
    private static final int PLATFORM_ADMIN_GRANTS = 15;

    @Autowired
    private RoleTemplateService roleTemplateService;

    @Autowired
    private TenantProvisioningService provisioningService;

    @Autowired
    private RoleManagementService roleManagementService;

    @Autowired
    private RoleTemplateReconciliationService reconciliationService;

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
                        + "provision,reactivate,read,suspend,update}, bulkImport:{status:read,upload:execute},"
                        + " security:role:{create,edit}")
                .isEqualTo(PLATFORM_ADMIN_GRANTS);
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

    /**
     * CAP:550 S3 criteria 1, 2, 6 and 8 (#2504): the template carries ACCOUNTING_CLERK and
     * GENERAL_MANAGER with the phase-1 grants, a provisioned tenant receives them with the same
     * grants, no clerk holds {@code accounting:ap:pay} (BR-1), and both refuse delete (BR-2).
     */
    @Test
    void theAccountingRolesAreTemplateRolesWithThePhaseOneGrantsAndRefuseDelete() {
        List<RoleTemplateEntry> template = asTenant(PlatformTenant.ID, roleTemplateService::snapshot);
        Map<String, RoleTemplateEntry> byName =
                template.stream().collect(java.util.stream.Collectors.toMap(RoleTemplateEntry::name, e -> e));

        RoleTemplateEntry clerk = byName.get("ACCOUNTING_CLERK");
        assertThat(clerk).isNotNull();
        assertThat(clerk.permissionNames())
                .contains("accounting:payment:apply", "accounting:reconciliation:adjust", "accounting:ap:view")
                .doesNotContain("accounting:ap:pay", "accounting:reconciliation:approve");
        assertThat(clerk.mcpPersonaEligible()).isTrue();
        assertThat(clerk.personaTitle()).isEqualTo("accounting clerk");
        assertThat(byName.get("GENERAL_MANAGER").permissionNames())
                .contains("accounting:payment:apply", "accounting:ap:pay", "accounting:ap:view");
        assertThat(byName.get("CONTROLLER").permissionNames())
                .contains("accounting:payment:apply", "accounting:ap:pay", "accounting:reconciliation:approve");

        // Alpha (the template's source) and the platform copy carry the same clerk grants.
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        List<String> alphaClerk = owner.queryForList("""
                SELECT p.name FROM role_permissions rp
                  JOIN roles r ON r.id = rp.role_id JOIN permissions p ON p.id = rp.permission_id
                 WHERE r.tenant_id = ? AND r.name = 'ACCOUNTING_CLERK' ORDER BY p.name
                """, String.class, TENANT_A);
        assertThat(alphaClerk).containsExactlyInAnyOrderElementsOf(clerk.permissionNames());
        assertThat(owner.queryForList(
                        "SELECT template_key FROM roles WHERE tenant_id = ? AND name IN ('ACCOUNTING_CLERK', 'GENERAL_MANAGER')",
                        String.class,
                        TENANT_A))
                .containsExactlyInAnyOrder("ACCOUNTING_CLERK", "GENERAL_MANAGER");

        UUID tenant = UUID.randomUUID();
        String email = "owner-" + UUID.randomUUID() + "@acme.example";
        asTenant(tenant, () -> provisioningService.provision(tenant, email, template));
        asTenant(tenant, () -> {
            for (String name : List.of("ACCOUNTING_CLERK", "GENERAL_MANAGER")) {
                Role role = roles.findByName(name).orElseThrow();
                assertThat(role.getTemplateKey()).isEqualTo(name);
                assertThat(role.getPermissions())
                        .hasSize(byName.get(name).permissionNames().size());
                assertThatThrownBy(() -> roleManagementService.deleteRole(role.getId()))
                        .as("%s is a template role: 409 ROLE_TEMPLATE_IMMUTABLE", name)
                        .isInstanceOf(TemplateRoleImmutableException.class);
            }
        });
    }

    /**
     * CAP:550 S3 criterion 7 (#2504): a tenant provisioned before this story holds the seven older
     * template roles and a CONTROLLER without {@code accounting:payment:apply}; one
     * {@code reconcile-template} run gives it both accounting roles and CONTROLLER's new grant, and
     * a second run changes nothing. This is the post-deployment step the operator runs for every
     * existing tenant other than alpha.
     */
    @Test
    void reconcilingAnExistingTenantAddsTheAccountingRolesAndControllersNewGrant() {
        UUID tenant = UUID.randomUUID();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        owner.update(
                "INSERT INTO ext_tenant (tenant_id, slug, display_name, status, aggregate_version, updated_at) "
                        + "VALUES (?, ?, 'Pre-S3 tenant', 'ACTIVE', 1, now())",
                tenant,
                "pre-s3-" + tenant.toString().substring(0, 8));

        // The template as it was before S3: no accounting roles, CONTROLLER without payment:apply.
        List<RoleTemplateEntry> template = asTenant(PlatformTenant.ID, roleTemplateService::snapshot);
        List<RoleTemplateEntry> before = template.stream()
                .filter(e -> !e.name().equals("ACCOUNTING_CLERK") && !e.name().equals("GENERAL_MANAGER"))
                .map(e -> e.name().equals("CONTROLLER")
                        ? new RoleTemplateEntry(
                                e.templateKey(),
                                e.name(),
                                e.description(),
                                e.personaTitle(),
                                e.personaFocus(),
                                e.personaTone(),
                                e.mcpPersonaRank(),
                                e.mcpPersonaEligible(),
                                e.locationScope(),
                                e.locationHierarchy(),
                                e.permissionNames().stream()
                                        .filter(p -> !p.equals("accounting:payment:apply"))
                                        .collect(java.util.stream.Collectors.toSet()))
                        : e)
                .toList();
        String email = "owner-" + UUID.randomUUID() + "@acme.example";
        asTenant(tenant, () -> provisioningService.provision(tenant, email, before));

        RoleTemplateReconcileResponse first =
                asTenant(PlatformTenant.ID, () -> reconciliationService.reconcile(tenant));
        RoleTemplateReconcileResponse again =
                asTenant(PlatformTenant.ID, () -> reconciliationService.reconcile(tenant));

        assertThat(first.rolesCreated()).containsExactlyInAnyOrder("ACCOUNTING_CLERK", "GENERAL_MANAGER");
        assertThat(first.grantsAdded())
                .containsExactly(
                        new RoleTemplateReconcileResponse.GrantAdded("CONTROLLER", "accounting:payment:apply"));
        assertThat(first.templateKeysAssigned()).isEmpty();
        assertThat(again.rolesCreated()).isEmpty();
        assertThat(again.grantsAdded()).isEmpty();
        assertThat(again.templateKeysAssigned()).isEmpty();

        asTenant(tenant, () -> {
            assertThat(roles.findByTemplateKeyIsNotNullOrderByNameAsc())
                    .extracting(Role::getName)
                    .containsExactlyElementsOf(FLOOR);
            assertThat(roles.findByName("ACCOUNTING_CLERK").orElseThrow().getPermissions())
                    .extracting(p -> p.getName())
                    .contains("accounting:payment:apply", "accounting:reconciliation:adjust")
                    .doesNotContain("accounting:ap:pay");
        });
    }

    /**
     * Role names are one name per tenant whatever their casing (ADR-0062 §6, plan WS8). The
     * database says so — {@code roles_tenant_lower_name_key} — and provisioning agrees, so a
     * tenant that already carries {@code admin} is not given a second ADMIN it could never store.
     */
    @Test
    void aDifferentlyCasedRoleIsTheSameRoleToTheSchemaAndToProvisioning() {
        UUID tenant = UUID.randomUUID();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        owner.update(
                "INSERT INTO roles (tenant_id, id, name, description, created_at, created_by) "
                        + "VALUES (?, ?, 'admin', 'hand-made, lower case', NOW(), 'it')",
                tenant,
                UUID.randomUUID());

        assertThatThrownBy(() -> owner.update(
                        "INSERT INTO roles (tenant_id, id, name, description, created_at, created_by) "
                                + "VALUES (?, ?, 'ADMIN', 'the same role, shouted', NOW(), 'it')",
                        tenant,
                        UUID.randomUUID()))
                .as("UNIQUE (tenant_id, lower(name)) refuses the second casing")
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM roles WHERE tenant_id = ? AND name = 'ADMIN'", Integer.class, TENANT_A))
                .as("the same name in another tenant is untouched: the index leads with tenant_id")
                .isEqualTo(1);

        List<RoleTemplateEntry> template = asTenant(PlatformTenant.ID, roleTemplateService::snapshot);
        String email = "owner-" + UUID.randomUUID() + "@acme.example";
        TenantProvisioningService.Outcome outcome =
                asTenant(tenant, () -> provisioningService.provision(tenant, email, template));

        assertThat(outcome.rolesCreated())
                .as("ADMIN was already there under another casing, so only the other five are created")
                .isEqualTo(FLOOR.size() - 1);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM roles WHERE tenant_id = ? AND lower(name) = 'admin'",
                        Integer.class,
                        tenant))
                .isEqualTo(1);
    }
}
