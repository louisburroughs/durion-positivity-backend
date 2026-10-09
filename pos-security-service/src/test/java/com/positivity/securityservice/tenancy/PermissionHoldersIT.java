package com.positivity.securityservice.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.dto.PermissionHolderRole;
import com.positivity.securityservice.internal.dto.PermissionHolders;
import com.positivity.securityservice.internal.dto.PermissionHoldersResponse;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.enums.LocationScope;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.service.PermissionHolderService;
import com.positivity.securityservice.internal.service.RoleManagementService;
import com.positivity.securityservice.internal.service.RoleTemplateEntry;
import com.positivity.securityservice.internal.service.RoleTemplateService;
import com.positivity.securityservice.internal.service.TenantProvisioningService;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * #2669 on the real schema with row-level security forced (ADR-0062): the permission-holders read
 * reports each tenant's configured grants — template answer, tenant changes, isolation, the empty
 * answer and a fresh read after a revoke (acceptance criteria 1, 2, 3, 6 and 11).
 *
 * <p>Each test provisions its own two tenants from the platform role template, so {@code T1} and
 * {@code T2} start identical and no test sees another's grant changes. Grants are changed through
 * {@link RoleManagementService}, the path the role-permission admin API takes. The caller holds
 * {@code security:role:view}; callers are built from authorities, never from role names.
 */
@DisplayName("Permission holders on Postgres with RLS forced (#2669)")
class PermissionHoldersIT extends PostgresTenancyTestBase {

    private static final String APPROVE = "accounting:ap:approve";
    private static final String OVER_LIMIT = "accounting:ap:approve_over_limit";
    private static final String REJECT = "accounting:ap:reject";
    private static final String PAY = "accounting:ap:pay";
    private static final String POLICY = "accounting:ap_approval_policy:manage";
    private static final List<String> AP_CODES = List.of(APPROVE, OVER_LIMIT, REJECT, PAY, POLICY);
    private static final Set<String> ROLE_VIEWER = Set.of("security:role:view");

    @Autowired
    private PermissionHolderService permissionHolderService;

    @Autowired
    private RoleTemplateService roleTemplateService;

    @Autowired
    private TenantProvisioningService provisioningService;

    @Autowired
    private RoleManagementService roleManagementService;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private PermissionRepository permissions;

    private UUID t1;
    private UUID t2;

    @BeforeEach
    void provisionTwoTenantsFromTheTemplate() {
        List<RoleTemplateEntry> template = asTenant(PlatformTenant.ID, roleTemplateService::snapshot);
        t1 = UUID.randomUUID();
        t2 = UUID.randomUUID();
        for (UUID tenant : List.of(t1, t2)) {
            String email = "owner-" + UUID.randomUUID() + "@acme.example";
            asTenant(tenant, () -> provisioningService.provision(tenant, email, template));
        }
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private PermissionHoldersResponse answerFor(UUID tenant, List<String> codes) {
        return asTenant(tenant, () -> permissionHolderService.listPermissionHolders(codes, ROLE_VIEWER));
    }

    private static List<PermissionHolderRole> rolesOf(PermissionHoldersResponse response, String code) {
        return response.permissions().stream()
                .filter(entry -> entry.permission().equals(code))
                .findFirst()
                .map(PermissionHolders::roles)
                .orElseThrow(() -> new AssertionError("no entry for " + code));
    }

    private UUID roleId(UUID tenant, String name) {
        return asTenant(tenant, () -> roles.findByName(name).orElseThrow().getId());
    }

    private void revoke(UUID tenant, String roleName, String code) {
        UUID id = roleId(tenant, roleName);
        asTenant(tenant, () -> roleManagementService.revokePermissionFromRole(id, code));
    }

    /** A tenant-authored role, as the admin API would leave it, then granted {@code codes}. */
    private void customRole(UUID tenant, String name, LocationScope scope, String... codes) {
        UUID id = asTenant(tenant, () -> {
            Role role = new Role();
            role.setName(name);
            role.setDescription("tenant-authored, #2669 IT");
            role.setLocationScope(scope);
            role.setCreatedAt(Instant.now());
            role.setCreatedBy("permission-holders-it");
            return roles.saveAndFlush(role).getId();
        });
        for (String code : codes) {
            asTenant(tenant, () -> roleManagementService.assignPermissionToRole(id, code));
        }
    }

    /** The truth AC 1 is measured against: the tenant's own role_permissions rows, read as the owner. */
    private static Map<String, List<PermissionHolderRole>> grantsInDatabase(UUID tenant, List<String> codes) {
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        Map<String, List<PermissionHolderRole>> byCode = new LinkedHashMap<>();
        codes.forEach(code -> byCode.put(code, new ArrayList<>()));
        for (String code : codes) {
            RowCallbackHandler collect = rs -> byCode.get(code)
                    .add(new PermissionHolderRole(
                            rs.getString(1), rs.getString(2), LocationScope.valueOf(rs.getString(3))));
            owner.query("""
                    SELECT r.name, r.template_key, r.location_scope
                      FROM role_permissions rp
                      JOIN roles r ON r.id = rp.role_id
                      JOIN permissions p ON p.id = rp.permission_id
                     WHERE r.tenant_id = ? AND p.name = ?
                    """, collect, tenant, code);
        }
        byCode.values()
                .forEach(list ->
                        list.sort(Comparator.comparing(PermissionHolderRole::name, String.CASE_INSENSITIVE_ORDER)));
        return byCode;
    }

    @Test
    @DisplayName("the tables this read relies on have RLS forced, and the pool is not their owner")
    void rowLevelSecurityIsForced() {
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        assertThat(owner.queryForList(
                        "SELECT relname FROM pg_class WHERE relname IN ('roles', 'role_permissions')"
                                + " AND relrowsecurity AND relforcerowsecurity",
                        String.class))
                .containsExactlyInAnyOrder("roles", "role_permissions");
    }

    @Test
    @DisplayName("AC 1: a provisioned tenant's answer is exactly its template roles' grants, sorted by name")
    void theTemplateAnswer() {
        PermissionHoldersResponse answer = answerFor(t1, AP_CODES);

        assertThat(answer.permissions())
                .extracting(PermissionHolders::permission)
                .containsExactlyElementsOf(AP_CODES);
        Map<String, List<PermissionHolderRole>> truth = grantsInDatabase(t1, AP_CODES);
        for (String code : AP_CODES) {
            assertThat(rolesOf(answer, code)).as("holders of %s", code).containsExactlyElementsOf(truth.get(code));
            assertThat(rolesOf(answer, code))
                    .as("template roles of %s carry their own name as templateKey", code)
                    .allSatisfy(role -> assertThat(role.templateKey()).isEqualTo(role.name()));
        }
        assertThat(rolesOf(answer, APPROVE))
                .extracting(PermissionHolderRole::name)
                .contains("ADMIN", "CONTROLLER", "GENERAL_MANAGER");
        assertThat(rolesOf(answer, PAY)).extracting(PermissionHolderRole::name).contains("CONTROLLER");
    }

    @Test
    @DisplayName("AC 2: a tenant's revoke and custom grant are reported; the other tenant's answer is unchanged")
    void aTenantChangeIsReported() {
        PermissionHoldersResponse t1Before = answerFor(t1, AP_CODES);

        revoke(t2, "CONTROLLER", PAY);
        customRole(t2, "Night Supervisor", LocationScope.LOCATION, OVER_LIMIT);

        PermissionHoldersResponse t2Answer = answerFor(t2, AP_CODES);
        assertThat(rolesOf(t2Answer, PAY))
                .extracting(PermissionHolderRole::name)
                .doesNotContain("CONTROLLER");
        assertThat(rolesOf(t2Answer, OVER_LIMIT))
                .contains(new PermissionHolderRole("Night Supervisor", null, LocationScope.LOCATION));
        assertThat(answerFor(t1, AP_CODES)).isEqualTo(t1Before);
    }

    @Test
    @DisplayName("AC 3: a custom role of T1 never appears in T2's answer")
    void tenantIsolation() {
        customRole(t1, "T1-only", LocationScope.ALL, APPROVE);

        assertThat(rolesOf(answerFor(t1, List.of(APPROVE)), APPROVE))
                .extracting(PermissionHolderRole::name)
                .contains("T1-only");
        assertThat(rolesOf(answerFor(t2, List.of(APPROVE)), APPROVE))
                .extracting(PermissionHolderRole::name)
                .doesNotContain("T1-only");
    }

    @Test
    @DisplayName("AC 6: a registered code no role holds is answered with an empty roles list")
    void nobodyHoldsIt() {
        for (PermissionHolderRole holder : rolesOf(answerFor(t2, List.of(OVER_LIMIT)), OVER_LIMIT)) {
            revoke(t2, holder.name(), OVER_LIMIT);
        }
        assertThat(asTenant(t2, () -> permissions.findByName(OVER_LIMIT)))
                .as("still registered")
                .get()
                .extracting(Permission::getName)
                .isEqualTo(OVER_LIMIT);

        PermissionHoldersResponse answer = answerFor(t2, List.of(OVER_LIMIT, APPROVE));
        assertThat(rolesOf(answer, OVER_LIMIT)).isEmpty();
        assertThat(rolesOf(answer, APPROVE)).isNotEmpty();
    }

    @Test
    @DisplayName("AC 11: a grant revoked between two calls is gone from the second (no cache)")
    void aFreshRead() {
        assertThat(rolesOf(answerFor(t1, List.of(APPROVE)), APPROVE))
                .extracting(PermissionHolderRole::name)
                .contains("CONTROLLER");

        revoke(t1, "CONTROLLER", APPROVE);

        assertThat(rolesOf(answerFor(t1, List.of(APPROVE)), APPROVE))
                .extracting(PermissionHolderRole::name)
                .doesNotContain("CONTROLLER");
    }
}
