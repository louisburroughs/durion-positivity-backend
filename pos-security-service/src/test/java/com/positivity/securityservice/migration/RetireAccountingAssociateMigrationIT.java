package com.positivity.securityservice.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * CAP:550 S3 (#2504) criteria 4 and 5: {@code V10__retire_accounting_associate.sql} against a real
 * Postgres and the real migration chain.
 *
 * <p>A database at the version before the retire migration is reproduced by running the versioned
 * migrations alone (copied to a temporary location, so the repeatable seeds — which already create
 * and grant {@code ACCOUNTING_CLERK} — do not run first) up to V9. Two tenants are then given the
 * state an existing database holds: alpha with the bulk-loaded {@code ACCOUNTING_ASSOCIATE}, a user
 * assigned to it and its {@code accounting:ap:pay} grant; a second tenant that holds both the associate
 * and a clerk, with one user on each and one user on both. Migrating to latest runs V10 and then the
 * seeds, after which the test asserts what the story promises.
 *
 * <p>Flyway runs as a non-superuser owner ({@code migrator}: LOGIN, NOSUPERUSER, NOBYPASSRLS), the
 * way the alpha owner is laid out (ADR-0062 §3): every scoped table is FORCE ROW LEVEL SECURITY, so
 * the seeds see only the tenant they bind — which is what lets the second tenant hold its own
 * {@code ACCOUNTING_CLERK} without the alpha-bound seeds resolving it by name. The container
 * superuser writes the legacy state and reads the assertions, bypassing RLS, so every query there
 * names its tenant.
 *
 * <p>No Spring context: the whole proof is SQL. Requires Docker.
 */
@Testcontainers
@DisplayName("V10 retires ACCOUNTING_ASSOCIATE (Postgres)")
class RetireAccountingAssociateMigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");
    private static final String PLACEHOLDER_HASH = "not-a-real-hash-pg-test-only";
    private static final String MIGRATOR = "migrator";
    private static final String MIGRATOR_PASSWORD = "migrator-test-only";

    private static final UUID ALPHA = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("01900000-0000-7000-8000-00000000c550");
    private static final UUID CLERK_ONLY_TENANT = UUID.fromString("01900000-0000-7000-8000-00000000c551");

    private static final UUID ALPHA_ASSOCIATE = UUID.fromString("01990550-0000-7000-8000-000000000a01");
    private static final UUID OTHER_ASSOCIATE = UUID.fromString("01990550-0000-7000-8000-000000000b01");
    private static final UUID OTHER_CLERK = UUID.fromString("01990550-0000-7000-8000-000000000b02");
    private static final UUID STANDALONE_CLERK = UUID.fromString("01990550-0000-7000-8000-000000000c01");

    @TempDir
    static Path versionedOnly;

    private static Connection connection;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateToTheVersionBeforeV10ThenSeedTheLegacyStateThenMigrateToLatest()
            throws IOException, SQLException {
        // 0. The non-superuser owner that runs Flyway and is subject to row-level security.
        JdbcTemplate superuser = new JdbcTemplate(ownerDataSource());
        superuser.execute("CREATE ROLE " + MIGRATOR + " LOGIN PASSWORD '" + MIGRATOR_PASSWORD
                + "' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE");
        superuser.execute("GRANT CONNECT ON DATABASE " + POSTGRES.getDatabaseName() + " TO " + MIGRATOR);
        superuser.execute("GRANT ALL ON SCHEMA public TO " + MIGRATOR);

        // 1. Versioned migrations only, up to V9: the repeatable seeds would otherwise create and
        //    grant ACCOUNTING_CLERK before the legacy state exists.
        try (var files = Files.list(MIGRATIONS)) {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("V"))
                    .toList()) {
                Files.copy(file, versionedOnly.resolve(file.getFileName()));
            }
        }
        Flyway.configure()
                .dataSource(migratorDataSource())
                .locations("filesystem:" + versionedOnly)
                .placeholders(Map.of("seed_admin_password_hash", PLACEHOLDER_HASH))
                .target("9")
                .load()
                .migrate();

        connection = ownerDataSource().getConnection();
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM roles WHERE name IN ('ACCOUNTING_CLERK', 'ACCOUNTING_ASSOCIATE')",
                        Integer.class))
                .as("neither role exists before the legacy state is seeded")
                .isZero();

        // 2. The legacy state. The permission rows the repeatable seeds would otherwise register
        //    (same names and bits, so the seeds' ON CONFLICT clauses keep them); then alpha: the
        //    bulk-loaded associate, its ap:pay grant and a user.
        permission("accounting:ap:pay", 4);
        permission("accounting:ap:view", 3);
        permission("accounting:coa:view", 5);
        jdbc.execute("INSERT INTO ext_tenant (tenant_id, slug, display_name, status, aggregate_version, updated_at)"
                + " VALUES ('" + OTHER + "', 'other', 'Other', 'ACTIVE', 1, now()),"
                + " ('" + CLERK_ONLY_TENANT + "', 'clerk-only', 'Clerk only', 'ACTIVE', 1, now())");
        bind(ALPHA);
        role(ALPHA_ASSOCIATE, "ACCOUNTING_ASSOCIATE", "Canonical persona: Accounting Associate");
        grant(ALPHA_ASSOCIATE, "accounting:ap:pay");
        grant(ALPHA_ASSOCIATE, "accounting:ap:view");
        user("olivia.chen", ALPHA_ASSOCIATE);

        // The other tenant: both roles, one user on each, one user on both.
        bind(OTHER);
        role(OTHER_ASSOCIATE, "ACCOUNTING_ASSOCIATE", "Canonical persona: Accounting Associate");
        role(OTHER_CLERK, "ACCOUNTING_CLERK", "hand-made clerk");
        grant(OTHER_ASSOCIATE, "accounting:ap:pay");
        grant(OTHER_ASSOCIATE, "accounting:ap:view");
        grant(OTHER_CLERK, "accounting:ap:pay");
        grant(OTHER_CLERK, "accounting:coa:view");
        user("associate.only", OTHER_ASSOCIATE);
        user("clerk.only", OTHER_CLERK);
        user("holds.both", OTHER_ASSOCIATE);
        user("holds.both", OTHER_CLERK);
        // Effective-dated history: a clerk assignment that ended, or was revoked, is not "holding the
        // clerk" — only an active associate assignment keeps these two users in accounting.
        user("expired.clerk", OTHER_ASSOCIATE);
        assignment("expired.clerk", OTHER_CLERK, "now() - interval '400 days'", "now() - interval '30 days'", null);
        user("revoked.clerk", OTHER_ASSOCIATE);
        assignment("revoked.clerk", OTHER_CLERK, "now() - interval '400 days'", null, "now() - interval '30 days'");

        // A tenant that only ever had a clerk (hand-made before S3), holding ap:pay: BR-1 still applies.
        bind(CLERK_ONLY_TENANT);
        role(STANDALONE_CLERK, "ACCOUNTING_CLERK", "hand-made clerk, no associate");
        grant(STANDALONE_CLERK, "accounting:ap:pay");
        grant(STANDALONE_CLERK, "accounting:coa:view");
        user("standalone.clerk", STANDALONE_CLERK);

        // 3. Deploy: V10, then the repeatable seeds.
        Flyway.configure()
                .dataSource(migratorDataSource())
                .locations("classpath:db/migration")
                .placeholders(Map.of("seed_admin_password_hash", PLACEHOLDER_HASH))
                .load()
                .migrate();
    }

    @AfterAll
    static void close() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    @DisplayName("criterion 4: alpha's associate is renamed in place — same id, user still assigned, "
            + "ap:pay gone, the new grants present, template_key set")
    void alphaAssociateIsRenamedInPlace() {
        bind(ALPHA);
        assertThat(jdbc.queryForList(
                        "SELECT name FROM roles WHERE tenant_id = ? AND name IN ('ACCOUNTING_ASSOCIATE', 'ACCOUNTING_CLERK')",
                        String.class,
                        ALPHA))
                .containsExactly("ACCOUNTING_CLERK");
        Map<String, Object> clerk = jdbc.queryForMap(
                "SELECT id, description, persona_title, mcp_persona_rank, template_key, last_modified_by FROM roles"
                        + " WHERE tenant_id = ? AND name = 'ACCOUNTING_CLERK'",
                ALPHA);
        assertThat(clerk.get("id")).as("the renamed role keeps its id").isEqualTo(ALPHA_ASSOCIATE);
        assertThat((String) clerk.get("description"))
                .startsWith("Accounting clerk:")
                .contains("never pays bills");
        assertThat(clerk.get("persona_title")).isEqualTo("accounting clerk");
        assertThat(((Number) clerk.get("mcp_persona_rank")).intValue()).isEqualTo(50);
        assertThat(clerk.get("template_key"))
                .as("the seeds mark the renamed role as a template role")
                .isEqualTo("ACCOUNTING_CLERK");
        assertThat(clerk.get("last_modified_by")).isEqualTo("retire-accounting-associate");

        List<String> grants = grantsOf(ALPHA, ALPHA_ASSOCIATE);
        assertThat(grants)
                .doesNotContain("accounting:ap:pay")
                .contains(
                        "accounting:ap:view",
                        "accounting:payment:apply",
                        "accounting:reconciliation:adjust",
                        "mcp:chat:execute");
        assertThat(jdbc.queryForList(
                        "SELECT r.name FROM role_assignments ra JOIN roles r ON r.id = ra.role_id"
                                + " JOIN users u ON u.id = ra.user_id WHERE u.tenant_id = ? AND u.username = 'olivia.chen'",
                        String.class,
                        ALPHA))
                .as("the user is still assigned, now to the clerk")
                .containsExactly("ACCOUNTING_CLERK");
    }

    @Test
    @DisplayName("criterion 5: a tenant holding both roles — every associate assignment moves to the clerk, "
            + "a user holding both keeps one, the associate and its grants are gone, ap:pay is revoked")
    void otherTenantMergesTheAssociateIntoTheClerk() {
        bind(OTHER);
        assertThat(jdbc.queryForList(
                        "SELECT name FROM roles WHERE tenant_id = ? AND name IN ('ACCOUNTING_ASSOCIATE', 'ACCOUNTING_CLERK')",
                        String.class,
                        OTHER))
                .containsExactly("ACCOUNTING_CLERK");
        assertThat(jdbc.queryForObject(
                        "SELECT id FROM roles WHERE tenant_id = ? AND name = 'ACCOUNTING_CLERK'", UUID.class, OTHER))
                .as("the tenant's own clerk survives; the associate row is the one deleted")
                .isEqualTo(OTHER_CLERK);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM role_permissions WHERE tenant_id = ? AND role_id = ?",
                        Integer.class,
                        OTHER,
                        OTHER_ASSOCIATE))
                .as("the associate's grants are gone")
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM role_assignments WHERE tenant_id = ? AND role_id = ?",
                        Integer.class,
                        OTHER,
                        OTHER_ASSOCIATE))
                .as("no assignment points at the deleted role")
                .isZero();

        // The seeds are alpha-bound, so this tenant's clerk keeps exactly its own grants minus ap:pay.
        assertThat(grantsOf(OTHER, OTHER_CLERK)).containsExactly("accounting:coa:view");

        for (String username : List.of("associate.only", "clerk.only", "holds.both")) {
            assertThat(assignmentsOf(OTHER, username, false))
                    .as("assignments of %s", username)
                    .containsExactly("ACCOUNTING_CLERK");
        }
        for (String username : List.of("expired.clerk", "revoked.clerk")) {
            assertThat(assignmentsOf(OTHER, username, true))
                    .as(
                            "%s held the clerk only in the past, so the active associate assignment moves to the clerk",
                            username)
                    .containsExactly("ACCOUNTING_CLERK");
            assertThat(assignmentsOf(OTHER, username, false))
                    .as("the ended or revoked history row of %s is kept", username)
                    .containsExactly("ACCOUNTING_CLERK", "ACCOUNTING_CLERK");
        }
    }

    @Test
    @DisplayName("BR-1: a tenant holding only a clerk, no associate, still has the clerk's ap:pay revoked")
    void clerkOnlyTenantHasApPayRevoked() {
        bind(CLERK_ONLY_TENANT);
        assertThat(grantsOf(CLERK_ONLY_TENANT, STANDALONE_CLERK)).containsExactly("accounting:coa:view");
        assertThat(assignmentsOf(CLERK_ONLY_TENANT, "standalone.clerk", true)).containsExactly("ACCOUNTING_CLERK");
    }

    @Test
    @DisplayName("criterion 4: no tenant holds ACCOUNTING_ASSOCIATE, and no clerk holds ap:pay (BR-1)")
    void noTenantHoldsTheAssociateAndNoClerkPays() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM roles WHERE name = 'ACCOUNTING_ASSOCIATE'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM role_permissions rp JOIN roles r ON r.id = rp.role_id"
                                + " JOIN permissions p ON p.id = rp.permission_id"
                                + " WHERE r.name = 'ACCOUNTING_CLERK' AND p.name = 'accounting:ap:pay'",
                        Integer.class))
                .as("BR-1 across every tenant, including the platform template copy")
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '10' AND success", Integer.class))
                .isEqualTo(1);
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** The container superuser: bypasses row-level security, so every query through it names its tenant. */
    private static DataSource ownerDataSource() {
        return dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** The schema owner that runs Flyway: no superuser, no BYPASSRLS, like the alpha owner. */
    private static DataSource migratorDataSource() {
        return dataSource(MIGRATOR, MIGRATOR_PASSWORD);
    }

    private static DataSource dataSource(String user, String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(user);
        dataSource.setPassword(password);
        return dataSource;
    }

    /** Session-wide binding on the one shared connection: the scoped tables default tenant_id from it. */
    private static void bind(UUID tenant) {
        jdbc.execute("SELECT set_config('app.current_tenant', '" + tenant + "', false)");
    }

    private static void permission(String name, int bitIndex) {
        String[] parts = name.split(":");
        jdbc.update(
                "INSERT INTO permissions (id, name, description, domain, resource, action, registered_at,"
                        + " registered_by_service, version, bit_index)"
                        + " VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, now(), 'it', '1.0', ?)",
                name,
                name,
                parts[0],
                parts[1],
                parts[2],
                bitIndex);
    }

    private static void role(UUID id, String name, String description) {
        jdbc.update(
                "INSERT INTO roles (id, name, description, created_at, created_by) VALUES (?, ?, ?, now(), 'it')",
                id,
                name,
                description);
    }

    private static void grant(UUID roleId, String permission) {
        jdbc.update(
                "INSERT INTO role_permissions (role_id, permission_id) SELECT ?, id FROM permissions WHERE name = ?",
                roleId,
                permission);
    }

    private static void user(String username, UUID roleId) {
        assignment(username, roleId, "now()", null, null);
    }

    /** An assignment with explicit effective window and revocation (SQL expressions or null). */
    private static void assignment(String username, UUID roleId, String start, String end, String revokedAt) {
        jdbc.update(
                "INSERT INTO users (id, username, password, enabled) VALUES (gen_random_uuid(), ?, 'x', true)"
                        + " ON CONFLICT (tenant_id, username) DO NOTHING",
                username);
        jdbc.update(
                "INSERT INTO role_assignments (id, user_id, role_id, effective_start_date, effective_end_date,"
                        + " revoked_at, created_at, created_by)"
                        + " SELECT gen_random_uuid(), u.id, ?, " + start + ", " + (end == null ? "NULL" : end) + ", "
                        + (revokedAt == null ? "NULL" : revokedAt) + ", now(), 'it' FROM users u"
                        + " WHERE u.username = ? AND u.tenant_id = app_current_tenant()",
                roleId,
                username);
    }

    /** Role names a user is assigned to in a tenant; {@code activeOnly} applies the effective window and revocation. */
    private static List<String> assignmentsOf(UUID tenant, String username, boolean activeOnly) {
        return jdbc.queryForList(
                "SELECT r.name FROM role_assignments ra JOIN roles r ON r.id = ra.role_id"
                        + " JOIN users u ON u.id = ra.user_id WHERE u.tenant_id = ? AND u.username = ?"
                        + (activeOnly
                                ? " AND ra.revoked_at IS NULL AND ra.effective_start_date <= now()"
                                        + " AND (ra.effective_end_date IS NULL OR ra.effective_end_date > now())"
                                : "")
                        + " ORDER BY r.name",
                String.class,
                tenant,
                username);
    }

    private static List<String> grantsOf(UUID tenant, UUID roleId) {
        return jdbc.queryForList(
                "SELECT p.name FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id"
                        + " WHERE rp.tenant_id = ? AND rp.role_id = ? ORDER BY p.name",
                String.class,
                tenant,
                roleId);
    }
}
