package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.PosSecurityServiceApplication;
import com.positivity.securityservice.internal.dto.RolePermissionsRequest;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Pins who a role-permission grant is attributed to (#1512).
 *
 * <p>Deliberately a surefire test on H2 rather than only a Postgres IT. The failsafe ITs run on
 * main but are skipped on pull requests, and the bug this class exists to prevent — a bulk update
 * silently resetting the provenance of permissions the role already held — reached main precisely
 * because nothing exercised it earlier. The columns are present here because
 * {@code src/test/resources/schema.sql} mirrors V30 into the generated H2 schema.
 *
 * <p>{@code RolePermissionAuditColumnsIT} still covers what only a real Postgres can show: the
 * migration's own DDL, column types and the {@code granted_at} default.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = PosSecurityServiceApplication.class,
        properties = {"security.jwt.secret=test-jwt-secret-key-01234567890123456789"})
@ActiveProfiles("test")
@DisplayName("role-permission grant provenance")
class RolePermissionProvenanceTest {

    private static final String PROVENANCE_QUERY =
            "SELECT granted_by FROM role_permissions WHERE role_id = ? AND permission_id = ?";

    @MockitoBean
    private TokenRevocationManager tokenRevocationManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RolePermissionService rolePermissionService;

    @Autowired
    private RoleManagementService roleManagementService;

    @Autowired
    private PermissionRepository permissionRepository;

    private JdbcTemplate jdbc;
    private UUID roleId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        roleId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO roles (id, name, description, created_at, created_by) VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?)",
                roleId,
                "TEST_PROVENANCE_" + roleId.toString().substring(0, 8),
                "provenance test",
                "RolePermissionProvenanceTest");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM role_permissions WHERE role_id = ?", roleId);
        jdbc.update("DELETE FROM roles WHERE id = ?", roleId);
    }

    @Test
    @DisplayName("a grant through the admin API records the authenticated actor")
    void grantRecordsActor() {
        authenticateAs("grant.auditor");
        String permission = registerPermission("provenance:single:grant");

        rolePermissionService.grantPermission(roleId, permission);

        assertThat(grantorOf(permission)).isEqualTo("grant.auditor");
    }

    @Test
    @DisplayName("re-granting an existing permission keeps the original grantor")
    void regrantKeepsOriginalGrantor() {
        String permission = registerPermission("provenance:regrant:grant");

        authenticateAs("first.grantor");
        rolePermissionService.grantPermission(roleId, permission);

        authenticateAs("second.grantor");
        rolePermissionService.grantPermission(roleId, permission);

        assertThat(grantorOf(permission))
                .as("the first grant is the one that changed the role's authority")
                .isEqualTo("first.grantor");
    }

    @Test
    @DisplayName("a bulk update stamps only the permissions it adds")
    void bulkUpdateStampsOnlyNewGrants() {
        String held = registerPermission("provenance:bulk:held");
        String added = registerPermission("provenance:bulk:added");

        authenticateAs("first.grantor");
        rolePermissionService.grantPermission(roleId, held);

        // The bulk endpoint re-submits the full list, so the already-held permission is in the
        // request. Re-asserting it is not re-granting it — and must not reset its provenance.
        authenticateAs("bulk.editor");
        RolePermissionsRequest request = new RolePermissionsRequest();
        request.setRoleId(roleId);
        request.setPermissionNames(Set.of(held, added));
        roleManagementService.updateRolePermissions(request);

        assertThat(grantorOf(held))
                .as("a permission the role already held keeps its original grantor")
                .isEqualTo("first.grantor");
        assertThat(grantorOf(added))
                .as("the permission this call actually added is attributed to its caller")
                .isEqualTo("bulk.editor");
    }

    @Test
    @DisplayName("a grant written outside the admin API is left unattributed")
    void rawInsertIsUnattributed() {
        String permission = registerPermission("provenance:raw:insert");
        UUID permissionId = permissionIdOf(permission);

        // Exactly the shape of an out-of-band grant: the two key columns and nothing else.
        jdbc.update("INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)", roleId, permissionId);

        assertThat(grantorOf(permission))
                .as("nothing may attribute a grant the API did not make")
                .isNull();
    }

    private String registerPermission(String name) {
        return permissionRepository.findByName(name).map(Permission::getName).orElseGet(() -> {
            Permission permission = new Permission();
            permission.setName(name);
            permission.setDescription("provenance test");
            permission.setRegisteredByService("pos-security-service");
            permission.setDeprecated(false);
            permission.parsePermissionName();
            return permissionRepository.save(permission).getName();
        });
    }

    private UUID permissionIdOf(String name) {
        return permissionRepository.findByName(name).orElseThrow().getId();
    }

    private String grantorOf(String permissionName) {
        List<String> rows = jdbc.queryForList(PROVENANCE_QUERY, String.class, roleId, permissionIdOf(permissionName));
        assertThat(rows)
                .as("expected exactly one grant row for %s", permissionName)
                .hasSize(1);
        return rows.get(0);
    }

    private void authenticateAs(String username) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        username, "n/a", AuthorityUtils.createAuthorityList("security:role:edit")));
    }
}
