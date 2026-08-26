package com.positivity.securityservice.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.dto.RolePermissionsRequest;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.service.RoleManagementService;
import com.positivity.securityservice.service.RolePermissionService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Covers the {@code role_permissions} provenance columns added by V30 (#1512).
 *
 * <p>Only observable against a real Postgres: the default test profile runs on H2 with Flyway
 * disabled, so neither the migration nor the {@code granted_at} column default exists there.
 *
 * <p>The properties worth pinning are the ones that made the alpha investigation expensive.
 * A grant written outside the admin API must still be dated, or an out-of-band grant leaves no
 * trace at all; and it must be distinguishable from an attributed one, or "nobody knows who did
 * this" reads the same as "the API did it".
 *
 * <p>Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("pg")
@Testcontainers
@DisplayName("role_permissions provenance columns (Postgres)")
class RolePermissionAuditColumnsIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String PROVENANCE_QUERY =
            "SELECT granted_at, granted_by FROM role_permissions WHERE role_id = ? AND permission_id = ?";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RolePermissionService rolePermissionService;

    @Autowired
    private RoleManagementService roleManagementService;

    @Autowired
    private PermissionRepository permissionRepository;

    private UUID roleId;

    @DynamicPropertySource
    static void seedPlaceholders(DynamicPropertyRegistry registry) {
        registry.add("SECURITY_SEED_ADMIN_PASSWORD_HASH", () -> "not-a-real-hash-pg-test-only");
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        if (roleId != null) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.update("DELETE FROM role_permissions WHERE role_id = ?", roleId);
            jdbc.update("DELETE FROM roles WHERE id = ?", roleId);
            roleId = null;
        }
    }

    @Test
    @DisplayName("both columns exist and are nullable, and granted_at defaults to now()")
    void columnsExistWithExpectedShape() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Map<String, Object> grantedAt =
                jdbc.queryForMap("SELECT data_type, is_nullable, column_default FROM information_schema.columns"
                        + " WHERE table_name = 'role_permissions' AND column_name = 'granted_at'");
        assertThat(grantedAt.get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(grantedAt.get("is_nullable")).isEqualTo("YES");
        assertThat((String) grantedAt.get("column_default")).containsIgnoringCase("now()");

        Map<String, Object> grantedBy =
                jdbc.queryForMap("SELECT data_type, is_nullable, column_default FROM information_schema.columns"
                        + " WHERE table_name = 'role_permissions' AND column_name = 'granted_by'");
        assertThat(grantedBy.get("data_type")).isEqualTo("character varying");
        assertThat(grantedBy.get("is_nullable")).isEqualTo("YES");
        assertThat(grantedBy.get("column_default"))
                .as("a default would attribute unattributed writes")
                .isNull();
    }

    @Test
    @DisplayName("a grant written outside the admin API is dated but not attributed")
    void rawInsertIsDatedAndUnattributed() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        roleId = insertRole(jdbc, "IT_PROVENANCE_RAW");
        UUID permissionId = anyPermissionId(jdbc);

        // Exactly the shape of an out-of-band grant: the two key columns and nothing else.
        jdbc.update("INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)", roleId, permissionId);

        Map<String, Object> row = jdbc.queryForMap(PROVENANCE_QUERY, roleId, permissionId);
        assertThat(row.get("granted_at"))
                .as("the column default must date a write that names no actor")
                .isNotNull();
        assertThat(row.get("granted_by"))
                .as("nothing may attribute a grant the API did not make")
                .isNull();
    }

    @Test
    @DisplayName("a grant through the admin API records the authenticated actor")
    void adminApiGrantRecordsActor() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        roleId = insertRole(jdbc, "IT_PROVENANCE_API");
        String permissionName = anyPermissionName(jdbc);

        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        "grant.auditor", "n/a", AuthorityUtils.createAuthorityList("security:role:edit")));

        Instant before = Instant.now();
        rolePermissionService.grantPermission(roleId, permissionName);

        UUID permissionId =
                permissionRepository.findByName(permissionName).orElseThrow().getId();
        Map<String, Object> row = jdbc.queryForMap(PROVENANCE_QUERY, roleId, permissionId);

        assertThat(row.get("granted_by")).isEqualTo("grant.auditor");
        assertThat(((java.sql.Timestamp) row.get("granted_at")).toInstant()).isAfterOrEqualTo(before.minusSeconds(5));
    }

    @Test
    @DisplayName("re-granting an existing permission keeps the original grantor")
    void regrantDoesNotOverwriteOriginalGrantor() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        roleId = insertRole(jdbc, "IT_PROVENANCE_REGRANT");
        String permissionName = anyPermissionName(jdbc);
        UUID permissionId =
                permissionRepository.findByName(permissionName).orElseThrow().getId();

        authenticateAs("first.grantor");
        rolePermissionService.grantPermission(roleId, permissionName);

        authenticateAs("second.grantor");
        rolePermissionService.grantPermission(roleId, permissionName);

        Map<String, Object> row = jdbc.queryForMap(PROVENANCE_QUERY, roleId, permissionId);
        assertThat(row.get("granted_by"))
                .as("the first grant is the one that changed the role's authority")
                .isEqualTo("first.grantor");
    }

    @Test
    @DisplayName("a bulk permission update stamps only the permissions it adds")
    void bulkUpdateStampsOnlyNewGrants() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        roleId = insertRole(jdbc, "IT_PROVENANCE_BULK");
        List<String> names = twoPermissionNames(jdbc);
        String held = names.get(0);
        String added = names.get(1);

        authenticateAs("first.grantor");
        rolePermissionService.grantPermission(roleId, held);

        // The bulk endpoint re-submits the full list, so the already-held permission appears in
        // the request. Re-asserting it is not re-granting it.
        authenticateAs("bulk.editor");
        RolePermissionsRequest request = new RolePermissionsRequest();
        request.setRoleId(roleId);
        request.setPermissionNames(Set.of(held, added));
        roleManagementService.updateRolePermissions(request);

        assertThat(grantorOf(jdbc, held))
                .as("a permission the role already held keeps its original grantor")
                .isEqualTo("first.grantor");
        assertThat(grantorOf(jdbc, added))
                .as("the permission this call actually added is attributed to its caller")
                .isEqualTo("bulk.editor");
    }

    private String grantorOf(JdbcTemplate jdbc, String permissionName) {
        UUID permissionId =
                permissionRepository.findByName(permissionName).orElseThrow().getId();
        return (String) jdbc.queryForMap(PROVENANCE_QUERY, roleId, permissionId).get("granted_by");
    }

    private List<String> twoPermissionNames(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT name FROM permissions ORDER BY name LIMIT 2", String.class);
    }

    private void authenticateAs(String username) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        username, "n/a", AuthorityUtils.createAuthorityList("security:role:edit")));
    }

    private UUID insertRole(JdbcTemplate jdbc, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO roles (id, name, description, created_at, created_by) VALUES (?, ?, ?, NOW(), ?)",
                id,
                name,
                "provenance integration test",
                "RolePermissionAuditColumnsIT");
        return id;
    }

    private UUID anyPermissionId(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT id FROM permissions ORDER BY name LIMIT 1", UUID.class);
    }

    private String anyPermissionName(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT name FROM permissions ORDER BY name LIMIT 1", String.class);
    }
}
