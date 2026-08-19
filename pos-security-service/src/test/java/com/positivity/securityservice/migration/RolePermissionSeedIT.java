package com.positivity.securityservice.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises {@code R__seed_role_permissions.sql} against a real Postgres, which is the only
 * place its behaviour is observable: the default test profile runs on H2 with Flyway disabled.
 *
 * <p>Covers the three properties that are SQL-level rather than Java-level — the baseline
 * applies, re-applying it does not duplicate rows, and an unresolvable role or permission name
 * aborts loudly instead of silently under-granting authority — plus the end-to-end resolution
 * of a seeded operational user's effective permissions through
 * {@code user_roles -> roles -> role_permissions -> permissions}.
 *
 * <p>Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("pg")
@Testcontainers
@DisplayName("role_permissions seed (Postgres)")
class RolePermissionSeedIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String SEED = "db/migration/R__seed_role_permissions.sql";

    private static String seedSql;

    @Autowired
    private DataSource dataSource;

    /**
     * The seed migration for {@code admin.alpha} interpolates this placeholder. It is a
     * throwaway literal for a container that is destroyed with the test class; nothing
     * authenticates as that user here.
     */
    @DynamicPropertySource
    static void seedPlaceholders(DynamicPropertyRegistry registry) {
        registry.add("SECURITY_SEED_ADMIN_PASSWORD_HASH", () -> "not-a-real-hash-pg-test-only");
    }

    @BeforeAll
    static void loadSeed() throws IOException {
        try (InputStream in = RolePermissionSeedIT.class.getClassLoader().getResourceAsStream(SEED)) {
            assertThat(in).as("%s not on the classpath", SEED).isNotNull();
            seedSql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @AfterEach
    void restoreBaseline() {
        // Every test below must leave the seeded baseline intact for the next one.
        jdbc().execute(seedSql);
    }

    @Test
    @DisplayName("applies the baseline: canonical roles receive their grants")
    void seed_appliesBaselineGrants() {
        assertThat(grantedTo("SYSTEM_ADMINISTRATOR"))
                .as("SYSTEM_ADMINISTRATOR is the security/admin role, not a superuser")
                .contains("security:role:assign", "security:user:view", "security:audit:export")
                .doesNotContain("accounting:je:post", "workorder:workorder:edit", "catalog:product:create");

        assertThat(grantedTo("TECHNICIAN"))
                .contains("workorder:labor:add", "workorder:parts:consume", "workorder:workorder:view")
                .doesNotContain("workorder:workorder:approve", "invoice:finalize");

        assertThat(grantedTo("DISPATCHER"))
                .contains("shop:schedule:edit", "appointments:create", "workorder:workorder:assign-technician")
                .doesNotContain("invoice:manage", "accounting:ap:pay");

        assertThat(grantedTo("ACCOUNTING_ASSOCIATE"))
                .contains("accounting:ap:view", "accounting:ap:pay")
                .doesNotContain("accounting:je:post");

        assertThat(grantedTo("CUSTOMER"))
                .as("roles left ungranted on purpose stay empty")
                .isEmpty();
    }

    @Test
    @DisplayName("re-running the seed does not duplicate rows")
    void seed_isIdempotent() {
        int before = totalGrants();
        assertThat(before).as("baseline did not apply").isPositive();

        jdbc().execute(seedSql);
        jdbc().execute(seedSql);

        assertThat(totalGrants()).isEqualTo(before);
    }

    @Test
    @DisplayName("an unresolvable role name aborts the seed and names the role")
    void seed_unknownRoleName_failsLoudly() {
        jdbc().update("UPDATE roles SET name = 'TECHNICIAN_RENAMED' WHERE name = 'TECHNICIAN'");
        try {
            assertThatThrownBy(() -> jdbc().execute(seedSql))
                    .hasMessageContaining("unknown roles")
                    .hasMessageContaining("TECHNICIAN");
        } finally {
            // Restore before @AfterEach re-applies the baseline, or every later test aborts too.
            jdbc().update("UPDATE roles SET name = 'TECHNICIAN' WHERE name = 'TECHNICIAN_RENAMED'");
        }
    }

    @Test
    @DisplayName("an unresolvable permission name aborts the seed and names the permission")
    void seed_unknownPermissionName_failsLoudly() {
        jdbc().update("DELETE FROM role_permissions WHERE permission_id = "
                + "(SELECT id FROM permissions WHERE name = 'shop:schedule:edit')");
        jdbc().update("UPDATE permissions SET name = 'shop:schedule:edit_renamed' "
                + "WHERE name = 'shop:schedule:edit'");
        try {
            // The permission insert cannot resurrect the name: the renamed row still holds that
            // bit index, so the untargeted ON CONFLICT DO NOTHING skips it and the assertion fires.
            assertThatThrownBy(() -> jdbc().execute(seedSql)).hasMessageContaining("unknown permissions");
        } finally {
            jdbc().update("UPDATE permissions SET name = 'shop:schedule:edit' "
                    + "WHERE name = 'shop:schedule:edit_renamed'");
        }
    }

    @Test
    @DisplayName("a seeded user's effective permissions resolve through user_roles to permissions")
    void seededUser_resolvesEffectivePermissionsThroughRoleGrants() {
        assertThat(effectivePermissionsOf("kyle.brennan"))
                .as("kyle.brennan is a TECHNICIAN")
                .contains("workorder:labor:add", "workorder:parts:consume")
                .doesNotContain("security:role:assign");

        assertThat(effectivePermissionsOf("marcus.webb"))
                .as("marcus.webb is a SYSTEM_ADMINISTRATOR: security surface only")
                .contains("security:role:assign", "security:user_account_state:manage")
                .doesNotContain("accounting:je:post", "catalog:product:delete");

        assertThat(effectivePermissionsOf("olivia.chen"))
                .as("olivia.chen is an ACCOUNTING_ASSOCIATE")
                .contains("accounting:ap:view", "accounting:ap:pay");
    }

    @Test
    @DisplayName("a user holding only an ungranted role resolves no permissions")
    void userWithOnlyUngrantedRole_failsClosed() {
        jdbc().update(
                        "INSERT INTO users (id, username, password, enabled) "
                                + "VALUES (gen_random_uuid(), ?, 'x', true)",
                        "ungranted.user");
        jdbc().update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT u.id, r.id FROM users u, roles r "
                + "WHERE u.username = 'ungranted.user' AND r.name = 'CUSTOMER'");

        assertThat(effectivePermissionsOf("ungranted.user")).isEmpty();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private List<String> grantedTo(String roleName) {
        return jdbc().queryForList(
                        "SELECT p.name FROM roles r "
                                + "JOIN role_permissions rp ON rp.role_id = r.id "
                                + "JOIN permissions p ON p.id = rp.permission_id "
                                + "WHERE r.name = ?",
                        String.class,
                        roleName);
    }

    private List<String> effectivePermissionsOf(String username) {
        return jdbc().queryForList(
                        "SELECT DISTINCT p.name FROM users u "
                                + "JOIN user_roles ur ON ur.user_id = u.id "
                                + "JOIN roles r ON r.id = ur.role_id "
                                + "JOIN role_permissions rp ON rp.role_id = r.id "
                                + "JOIN permissions p ON p.id = rp.permission_id "
                                + "WHERE u.username = ?",
                        String.class,
                        username);
    }

    private int totalGrants() {
        Integer count = jdbc().queryForObject("SELECT count(*) FROM role_permissions", Integer.class);
        return count == null ? 0 : count;
    }
}
