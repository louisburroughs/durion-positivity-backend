package com.positivity.securityservice.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.securityservice.internal.service.AuthorizationService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * <p>Since #1613 (D8) the migration chain is no longer the whole picture: it provisions a
 * bootstrap floor of six roles and the other eleven arrive through the bulk load. {@link
 * #loadBaseline()} applies that second half here, so the grant assertions below describe a real
 * environment instead of half of one. The capability assertions are deliberately kept rather than
 * narrowed to the floor — resolving a technician's effective permissions through four joins is
 * behaviour no parse-level test can reach, which is the whole reason this IT exists.
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

    /**
     * Re-applied after the baseline role load, because it links seeded users to roles by name and
     * Flyway ran it while eleven of those roles did not yet exist. See {@link #loadBaseline()}.
     */
    private static final String OPERATIONAL = "db/migration/R__seed_security_operational_data.sql";

    /** The bulk-load baseline: canonical for every role outside the bootstrap floor (#1613 D8). */
    private static final Path BASELINE_ROLES =
            Path.of("..", "scripts", "fixtures", "seed", "alpha", "security", "roles.csv");

    private static final Path BASELINE_GRANTS =
            Path.of("..", "scripts", "fixtures", "seed", "alpha", "security", "role-permissions.csv");

    private static String seedSql;

    private static String operationalSql;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AuthorizationService authorizationService;

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
        seedSql = classpathSql(SEED);
        operationalSql = classpathSql(OPERATIONAL);
    }

    private static String classpathSql(String resource) throws IOException {
        try (InputStream in = RolePermissionSeedIT.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("%s not on the classpath", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @BeforeEach
    void provision() {
        loadBaseline();
    }

    @AfterEach
    void restoreBaseline() {
        // Restores the migration half only; @BeforeEach re-applies the bulk-load half before the
        // next test, so doing it here as well would just pay for 1078 grant inserts twice per test.
        jdbc().execute(seedSql);
    }

    /**
     * Provisions the roles and grants that #1613 (D8) moved out of Flyway and into the bulk load,
     * so this container matches a real environment rather than only its bootstrap floor.
     *
     * <p>Without this the IT sees six roles. {@code R__seed_reference_security.sql} creates
     * {@code ADMIN} and {@code SYSTEM_ADMINISTRATOR}; {@code V3}, {@code V8} and {@code V24} add
     * {@code DISPATCHER}, {@code SHOP_MANAGER}, {@code SELF_SERVICE_CUSTOMER} and
     * {@code CONTROLLER}. The other eleven -- {@code TECHNICIAN}, {@code ACCOUNT_MANAGER}, the
     * three inventory roles and the rest -- are provisioned by the {@code SECURITY_ROLE} and
     * {@code SECURITY_ROLE_PERMISSION} loaders from the baseline files this method reads. Asserting
     * their grants is still the right thing for this IT to do; going through Flyway alone is not.
     *
     * <p>Ids are generated rather than fixed: nothing here asserts on a role id, and the real
     * loaders mint UUIDv7 through the service layer (ADR-0013), which is not reproducible from SQL.
     */
    private void loadBaseline() {
        for (String[] row : csvRows(BASELINE_ROLES)) {
            jdbc().update(
                            "INSERT INTO roles (id, name, description, created_at, created_by) "
                                    + "VALUES (gen_random_uuid(), ?, ?, NOW(), 'baseline-load-it') "
                                    + "ON CONFLICT (name) DO NOTHING",
                            row[0],
                            row.length > 1 ? row[1] : row[0]);
        }

        for (String[] row : csvRows(BASELINE_GRANTS)) {
            if (row.length < 2) {
                continue;
            }
            for (String code : row[1].split(";")) {
                if (code.isBlank()) {
                    continue;
                }
                // Skips silently when either side is unresolvable, unlike the seed migration's
                // loud guard: an unknown code here means the baseline outran the permission
                // catalog, which is RolePermissionBaselineTest's job to catch, not this one's.
                jdbc().update(
                                "INSERT INTO role_permissions (role_id, permission_id) "
                                        + "SELECT r.id, p.id FROM roles r, permissions p "
                                        + "WHERE r.name = ? AND p.name = ? "
                                        + "ON CONFLICT DO NOTHING",
                                row[0],
                                code.trim());
            }
        }

        // Flyway ran this while eleven of the roles above did not exist, so its user_roles insert
        // resolved only for the floor. Re-running it now links the rest -- the same ordering the
        // real pipeline gets by loading users after roles.
        jdbc().execute(operationalSql);
    }

    /** Minimal CSV split: these fixtures quote only embedded commas. */
    private static List<String[]> csvRows(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("baseline fixture not readable: " + file, exception);
        }
        assertThat(lines).as("%s is empty", file).isNotEmpty();

        List<String[]> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            List<String> values = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    quoted = !quoted;
                } else if (c == ',' && !quoted) {
                    values.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
            values.add(current.toString());
            rows.add(values.toArray(String[]::new));
        }
        return rows;
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
                .as("customer-facing roles receive the assistant entrypoints and nothing else")
                .containsExactlyInAnyOrder(
                        "mcp:chat:execute", "mcp:chat:stream", "nlti:request:submit", "nlti:request:read");
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
        // DISPATCHER, not TECHNICIAN: the guard can only fire on a role the seed still grants to,
        // and #1613 (D8) moved TECHNICIAN to the bulk-load baseline. Renaming a role the reduced
        // seed no longer mentions proves nothing -- the seed would simply succeed.
        jdbc().update("UPDATE roles SET name = 'DISPATCHER_RENAMED' WHERE name = 'DISPATCHER'");
        try {
            assertThatThrownBy(() -> jdbc().execute(seedSql))
                    .hasMessageContaining("unknown roles")
                    .hasMessageContaining("DISPATCHER");
        } finally {
            // Restore before @AfterEach re-applies the baseline, or every later test aborts too.
            jdbc().update("UPDATE roles SET name = 'DISPATCHER' WHERE name = 'DISPATCHER_RENAMED'");
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

        assertThat(effectivePermissionsOf("gloria.mendez"))
                .as("gloria.mendez is an INVENTORY_LEAD: receiving and PO entry (#1439), no PO approval")
                .contains("inventory:receiving:create", "inventory:goods_receipt:create", "order:purchase_order:create")
                .doesNotContain("order:purchase_order:approve", "inventory:goods_receipt:override");
    }

    @Test
    @DisplayName("manager-approval elevation: a manager person resolves invoice:finalize:override, "
            + "a service advisor does not")
    void managerApprovalElevation_resolvesFinalizeOverrideThroughPersonDecision() {
        // The grant half: exactly the manager roles agreed on #1374 hold the permission.
        for (String role :
                List.of("ACCOUNT_MANAGER", "ADMIN", "GENERAL_MANAGER", "LOCATION_MANAGER", "MANAGER", "SHOP_MANAGER")) {
            assertThat(grantedTo(role)).as("grants for %s", role).contains("invoice:finalize:override");
        }
        assertThat(grantedTo("SERVICE_ADVISOR"))
                .as("the permission caps what a service advisor can finalize; the advisor must not hold it")
                .doesNotContain("invoice:finalize:override");

        // The resolution half, end to end: pos-invoice's employee-number approval flow calls
        // GET /v1/users/authorization/person-decision, which lands on
        // AuthorizationService.authorizePerson and resolves
        // users.person_id -> user_roles -> roles -> role_permissions -> permissions.
        // Run that real code path against the seeded baseline for a manager and a
        // non-manager, so the flow cannot silently regress to "no role holds it" (#1374).
        UUID managerPersonId = UUID.fromString("01990010-0000-7000-8000-000000000001");
        UUID advisorPersonId = UUID.fromString("01990010-0000-7000-8000-000000000002");
        insertUserWithRole("it.elevation.manager", managerPersonId, "LOCATION_MANAGER");
        insertUserWithRole("it.elevation.advisor", advisorPersonId, "SERVICE_ADVISOR");
        try {
            assertThat(authorizationService.authorizePerson(managerPersonId, "invoice:finalize:override"))
                    .isEqualTo(AuthorizationService.Decision.ALLOW);
            assertThat(authorizationService.authorizePerson(advisorPersonId, "invoice:finalize:override"))
                    .isEqualTo(AuthorizationService.Decision.DENY);
        } finally {
            jdbc().update("DELETE FROM user_roles WHERE user_id IN "
                    + "(SELECT id FROM users WHERE username IN ('it.elevation.manager', 'it.elevation.advisor'))");
            jdbc().update("DELETE FROM users WHERE username IN ('it.elevation.manager', 'it.elevation.advisor')");
        }
    }

    @Test
    @DisplayName("a user holding only an ungranted role resolves no permissions")
    void userWithOnlyUngrantedRole_failsClosed() {
        // Every seeded role now carries the assistant entrypoints, so fail-closed has to be
        // proven against a role with genuinely no grants rather than against a seeded one.
        jdbc().update("INSERT INTO roles (id, name, description, created_at, created_by) "
                + "VALUES (gen_random_uuid(), 'IT_UNGRANTED_ROLE', 'no grants', NOW(), 'test')");
        jdbc().update(
                        "INSERT INTO users (id, username, password, enabled) "
                                + "VALUES (gen_random_uuid(), ?, 'x', true)",
                        "ungranted.user");
        jdbc().update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT u.id, r.id FROM users u, roles r "
                + "WHERE u.username = 'ungranted.user' AND r.name = 'IT_UNGRANTED_ROLE'");

        assertThat(effectivePermissionsOf("ungranted.user")).isEmpty();
    }

    private void insertUserWithRole(String username, UUID personId, String roleName) {
        jdbc().update(
                        "INSERT INTO users (id, username, password, enabled, person_id) "
                                + "VALUES (gen_random_uuid(), ?, 'x', true, ?)",
                        username,
                        personId);
        jdbc().update(
                        "INSERT INTO user_roles (user_id, role_id) "
                                + "SELECT u.id, r.id FROM users u, roles r WHERE u.username = ? AND r.name = ?",
                        username,
                        roleName);
    }

    @Test
    @DisplayName("V23 removes the unratified candidate roles V3 created")
    void unratifiedCandidateRolesAreGoneAfterMigration() {
        // V3 seeds SECURITY_ADMIN and READ_ONLY_SCHEDULER as "Candidate Roles v0"; #1373
        // decided they were never ratified and V23 deletes them. V3 is left untouched
        // (it is applied everywhere, so editing it would break its checksum), which makes
        // the delete only observable after the full migration chain runs — exactly what
        // this container gives us. The unit-level baseline test cannot see it: V3 still
        // lists both names, so they still look creatable from a static parse.
        assertThat(jdbc().queryForList(
                                "SELECT name FROM roles WHERE name IN ('SECURITY_ADMIN', 'READ_ONLY_SCHEDULER')",
                                String.class))
                .as("V3-seeded candidate roles must not survive V23")
                .isEmpty();

        // The siblings from the same V3 batch were ratified and must remain.
        assertThat(jdbc().queryForList(
                                "SELECT name FROM roles WHERE name IN ('DISPATCHER', 'SHOP_MANAGER')", String.class))
                .as("the ratified half of the V3 candidate batch")
                .containsExactlyInAnyOrder("DISPATCHER", "SHOP_MANAGER");
    }

    @Test
    @DisplayName("the inventory adjustment roles resolve the capability their javadoc documents")
    void inventoryAdjustmentRolesResolveTheirCapability() {
        // #1373: before this, only ADMIN held any inventory:adjustment:* permission, so the
        // three roles that exist specifically to model adjustment approval could neither
        // create nor approve one.
        assertThat(grantedTo("INVENTORY_LEAD"))
                .as("INVENTORY_LEAD raises adjustment requests but must not approve them")
                .contains("inventory:adjustment:create", "inventory:adjustment:view")
                .doesNotContain("inventory:adjustment:approve", "inventory:adjustment:override");

        for (String approver : List.of("INVENTORY_MANAGER", "INVENTORY_CONTROLLER")) {
            assertThat(grantedTo(approver))
                    .as("grants for %s", approver)
                    .contains(
                            "inventory:adjustment:create", "inventory:adjustment:approve", "inventory:adjustment:view");
        }

        // The negative-stock escape hatch ScrapServiceImpl enforces: global approvers only.
        assertThat(grantedTo("INVENTORY_CONTROLLER")).contains("inventory:adjustment:override");
        assertThat(grantedTo("INVENTORY_MANAGER"))
                .as("a location-scoped approver must not drive on-hand below zero")
                .doesNotContain("inventory:adjustment:override");
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
