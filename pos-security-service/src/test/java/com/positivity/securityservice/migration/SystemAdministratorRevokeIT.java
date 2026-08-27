package com.positivity.securityservice.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
 * Covers {@code V31__revoke_system_administrator_out_of_band_grants.sql} (#1512).
 *
 * <p>{@code RolePermissionBaselineTest} proves the migration's keep list still matches the seed;
 * that is a static parse and cannot show the statement does anything. This class reproduces the
 * alpha defect against a real Postgres — grant SYSTEM_ADMINISTRATOR every row in the
 * {@code permissions} table, which is exactly what happened between 2026-08-24 and 2026-08-25 —
 * and re-applies V31 over it.
 *
 * <p>Three properties matter, and the migration is only worth having if all three hold: it
 * removes the extra grants, it leaves the seeded baseline intact, and it touches no other role.
 * The last one is not incidental. V25–V28 deleted by {@code permission_id} with no role filter
 * and silently stripped 48 grants off a role none of them mentions; that is the collateral damage
 * that made this investigation legible in the first place, and repeating it here would be a worse
 * bug than the one being fixed.
 *
 * <p>Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("pg")
@Testcontainers
@DisplayName("SYSTEM_ADMINISTRATOR out-of-band grant revoke (Postgres)")
class SystemAdministratorRevokeIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String SEED = "db/migration/R__seed_role_permissions.sql";
    private static final String REVOKE = "db/migration/V31__revoke_system_administrator_out_of_band_grants.sql";

    private static String seedSql;
    private static String revokeSql;

    @Autowired
    private DataSource dataSource;

    @DynamicPropertySource
    static void seedPlaceholders(DynamicPropertyRegistry registry) {
        registry.add("SECURITY_SEED_ADMIN_PASSWORD_HASH", () -> "not-a-real-hash-pg-test-only");
    }

    @BeforeAll
    static void loadSql() throws IOException {
        seedSql = readClasspath(SEED);
        revokeSql = readClasspath(REVOKE);
    }

    @AfterEach
    void restoreBaseline() {
        // Every test corrupts role_permissions on purpose; hand the next one a clean baseline.
        jdbc().execute(seedSql);
    }

    @Test
    @DisplayName("the full migration chain leaves SYSTEM_ADMINISTRATOR on its seeded baseline")
    void migrationChainLeavesTheSeededBaselineIntact() {
        // V31 runs before the repeatable seed on a fresh database, so it must find nothing to do
        // and delete nothing. If the keep list were wrong in the over-deleting direction this
        // would still pass — the seed re-inserts afterwards — which is precisely why the
        // corruption tests below re-apply V31 *after* the seed.
        assertThat(grantedTo("SYSTEM_ADMINISTRATOR"))
                .as("the security/admin surface, not a domain superset")
                .contains("security:role:assign", "security:user:view", "mcp:tool:manage")
                .doesNotContain("accounting:je:post", "workorder:workorder:edit", "catalog:product:create");
    }

    @Test
    @DisplayName("revokes every grant the seed does not make, and keeps every grant it does")
    void revokesTheOutOfBandGrantsAndKeepsTheBaseline() {
        Set<String> baseline = new TreeSet<>(grantedTo("SYSTEM_ADMINISTRATOR"));
        assertThat(baseline).as("baseline did not apply").isNotEmpty();

        int catalogSize = grantEverythingToSystemAdministrator();
        assertThat(grantedTo("SYSTEM_ADMINISTRATOR"))
                .as("the alpha shape: every registered permission held by one role")
                .hasSize(catalogSize);
        assertThat(catalogSize)
                .as("the corruption has to be observable, not a no-op")
                .isGreaterThan(baseline.size());

        jdbc().execute(revokeSql);

        assertThat(new TreeSet<>(grantedTo("SYSTEM_ADMINISTRATOR")))
                .as("after V31, exactly the grants R__seed_role_permissions.sql makes")
                .isEqualTo(baseline);
    }

    @Test
    @DisplayName("revokes from SYSTEM_ADMINISTRATOR alone, leaving every other role untouched")
    void leavesEveryOtherRoleUntouched() {
        Map<String, Integer> before = grantCountsByRole();
        grantEverythingToSystemAdministrator();

        jdbc().execute(revokeSql);

        Map<String, Integer> after = grantCountsByRole();
        before.remove("SYSTEM_ADMINISTRATOR");
        after.remove("SYSTEM_ADMINISTRATOR");
        assertThat(after)
                .as("a role-agnostic DELETE here would repeat the V25-V28 collateral damage")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("re-applying the revoke changes nothing")
    void revokeIsIdempotent() {
        grantEverythingToSystemAdministrator();
        jdbc().execute(revokeSql);
        int afterFirst = totalGrants();

        jdbc().execute(revokeSql);
        jdbc().execute(revokeSql);

        assertThat(totalGrants())
                .as("the second pass finds nothing outside the keep list")
                .isEqualTo(afterFirst);
    }

    /**
     * Reproduces the alpha grant: every row then in {@code permissions}, handed to one role.
     *
     * @return the size of the permission catalog, which is what the role now holds
     */
    private int grantEverythingToSystemAdministrator() {
        jdbc().update("INSERT INTO role_permissions (role_id, permission_id) "
                + "SELECT r.id, p.id FROM roles r CROSS JOIN permissions p "
                + "WHERE r.name = 'SYSTEM_ADMINISTRATOR' "
                + "ON CONFLICT DO NOTHING");
        Integer catalogSize = jdbc().queryForObject("SELECT count(*) FROM permissions", Integer.class);
        return catalogSize == null ? 0 : catalogSize;
    }

    private Map<String, Integer> grantCountsByRole() {
        return jdbc().query(
                        "SELECT r.name, count(rp.permission_id) AS held FROM roles r "
                                + "LEFT JOIN role_permissions rp ON rp.role_id = r.id "
                                + "GROUP BY r.name",
                        rs -> {
                            Map<String, Integer> counts = new TreeMap<>();
                            while (rs.next()) {
                                counts.put(rs.getString("name"), rs.getInt("held"));
                            }
                            return counts;
                        });
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

    private int totalGrants() {
        Integer count = jdbc().queryForObject("SELECT count(*) FROM role_permissions", Integer.class);
        return count == null ? 0 : count;
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private static String readClasspath(String name) throws IOException {
        try (InputStream in = SystemAdministratorRevokeIT.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("%s not on the classpath", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
