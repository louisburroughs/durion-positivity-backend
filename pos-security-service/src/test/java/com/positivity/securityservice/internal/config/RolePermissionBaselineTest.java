package com.positivity.securityservice.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.enums.PermissionCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the {@code role_permissions} baseline seeded by
 * {@code R__seed_role_permissions.sql}.
 *
 * <p>Authorities are resolved from the database now that the compiled role-to-authority
 * switch in {@code RoleAuthorityServiceImpl} is gone, which makes this SQL file the thing
 * that decides what every canonical role can do. Two properties matter and neither is
 * visible from the Java side any more:
 *
 * <ol>
 *   <li><b>No capability was lost in the migration.</b> {@code role-authority-legacy-baseline.tsv}
 *       is the exact expansion the deleted switch produced, captured before deletion. The seed
 *       must reproduce it row for row.</li>
 *   <li><b>No capability was silently widened.</b> In particular SYSTEM_ADMINISTRATOR is an
 *       admin/security role, not a superuser.</li>
 * </ol>
 *
 * <p>This is a pure parse of the migration, so it runs in {@code test} without a database.
 * {@code RolePermissionSeedIT} covers what only a real Postgres can prove (the SQL applies,
 * re-applies without duplicating, and rejects unknown names).
 */
@DisplayName("role_permissions baseline seed")
class RolePermissionBaselineTest {

    private static final String SEED = "db/migration/R__seed_role_permissions.sql";
    private static final String LEGACY_BASELINE = "role-authority-legacy-baseline.tsv";

    /** {@code ('name', 'domain', 'resource', 'action', 42),} — a permission definition row. */
    private static final Pattern PERMISSION_ROW =
            Pattern.compile("^\\s*\\('([^']+)', '([^']*)', '([^']*)', '([^']+)', (\\d+)\\),?$");

    /**
     * {@code ('ROLE_NAME', 'domain:resource:action'),} — a role grant row.
     *
     * <p>The permission half must be colon-bearing and space-free so this cannot also match the
     * {@code ('ROLE_NAME', 'description')} rows in the seed's role-creation block, whose second
     * value is prose.
     */
    private static final Pattern GRANT_ROW =
            Pattern.compile("^\\s*\\('([A-Z][A-Z_]+)', '([a-zA-Z][a-zA-Z0-9_.-]*(?::[a-zA-Z0-9_.-]+)+)'\\),?$");

    /** Directory holding the Flyway migrations, relative to the module root. */
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /**
     * Roles the retired hardcoded switch expanded that no migration and no runtime initializer
     * ever creates. Both {@code user_roles} and {@code role_assignments} are foreign-keyed to
     * {@code roles(id)}, so no user could hold one — they were unreachable branches, and their
     * grants are deliberately not carried into the baseline.
     */
    private static final Set<String> UNREACHABLE_LEGACY_ROLES =
            Set.of("ACCOUNTANT", "AP_CLERK", "CONTROLLER", "CSR", "FLEET_MANAGER", "GL_ANALYST");

    /**
     * Conversational entrypoints every role receives. Holding these grants reach to the
     * assistant, not to data: the assistant still enforces domain permissions per request.
     */
    private static final Set<String> ASSISTANT_BASELINE =
            Set.of("mcp:chat:execute", "mcp:chat:stream", "nlti:request:submit", "nlti:request:read");

    /** MCP administration that both ADMIN and SYSTEM_ADMINISTRATOR must hold. */
    private static final Set<String> MCP_ADMINISTRATION = Set.of(
            "mcp:system_prompt:view",
            "mcp:system_prompt:create",
            "mcp:system_prompt:update",
            "mcp:system_prompt:delete",
            "mcp:llm_api:view",
            "mcp:llm_api:create",
            "mcp:llm_api:update",
            "mcp:llm_api:delete",
            "mcp:tool:manage",
            "mcp:document:ingest");

    /**
     * Everything on the MCP administration surface, which no role outside ADMIN and
     * SYSTEM_ADMINISTRATOR may hold. Wider than {@link #MCP_ADMINISTRATION} because
     * {@code mcp:tool:view} is restricted too but is currently held by ADMIN alone, so it is
     * not something both admin roles are required to have.
     */
    private static final Set<String> MCP_ADMINISTRATION_SURFACE = Stream.concat(
                    MCP_ADMINISTRATION.stream(), Stream.of("mcp:tool:view"))
            .collect(Collectors.toUnmodifiableSet());

    private static Map<String, Set<String>> seededGrants;
    private static Set<String> definedPermissions;
    private static List<String> rawGrantRows;

    @BeforeAll
    static void parseSeed() throws IOException {
        seededGrants = new TreeMap<>();
        definedPermissions = new LinkedHashSet<>();
        rawGrantRows = new ArrayList<>();

        for (String line : readResource(SEED).lines().toList()) {
            Matcher permission = PERMISSION_ROW.matcher(line);
            if (permission.matches()) {
                definedPermissions.add(permission.group(1));
                continue;
            }
            Matcher grant = GRANT_ROW.matcher(line);
            if (grant.matches()) {
                rawGrantRows.add(grant.group(1) + "\t" + grant.group(2));
                seededGrants
                        .computeIfAbsent(grant.group(1), role -> new TreeSet<>())
                        .add(grant.group(2));
            }
        }

        assertThat(seededGrants).as("no grant rows parsed out of %s", SEED).isNotEmpty();
    }

    @Test
    @DisplayName("still grants everything the retired hardcoded expansion gave every legacy role")
    void seed_losesNoLegacyCapability() throws IOException {
        Map<String, Set<String>> legacy = new TreeMap<>();
        for (String line : readResource(LEGACY_BASELINE).lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", 2);
            legacy.computeIfAbsent(parts[0], role -> new TreeSet<>()).add(parts[1]);
        }

        assertThat(legacy).as("legacy baseline fixture is empty").isNotEmpty();

        // Containment, not equality: the baseline deliberately exceeds the legacy expansion now
        // (every role gains the assistant entrypoints). The fixture is the no-regression floor —
        // a capability the switch used to grant must never silently disappear.
        // Per role rather than in bulk, so a failure names the role that drifted.
        legacy.forEach((role, expected) -> assertThat(seededGrants.get(role))
                .as("grants for legacy role %s", role)
                .containsAll(expected));
    }

    @Test
    @DisplayName("SYSTEM_ADMINISTRATOR receives only admin and security capability, never a domain superset")
    void systemAdministrator_isSecurityScopedNotSuperuser() {
        Set<String> granted = seededGrants.get("SYSTEM_ADMINISTRATOR");

        assertThat(granted)
                .as("SYSTEM_ADMINISTRATOR must carry the security admin surface")
                .isNotEmpty();
        assertThat(granted)
                .as("SYSTEM_ADMINISTRATOR may hold security:*, MCP administration and the assistant "
                        + "entrypoints only — never domain authority")
                .allMatch(permission -> permission.startsWith("security:")
                        || permission.startsWith("mcp:")
                        || permission.startsWith("nlti:"));
        assertThat(granted).containsAll(MCP_ADMINISTRATION);

        // ADMIN is the all-domain role; SYSTEM_ADMINISTRATOR must stay strictly narrower.
        assertThat(granted)
                .as("SYSTEM_ADMINISTRATOR must not inherit ADMIN's domain authority")
                .isSubsetOf(seededGrants.get("ADMIN"))
                .hasSizeLessThan(seededGrants.get("ADMIN").size());
    }

    @Test
    @DisplayName("DISPATCHER receives scheduling and dispatch capability and nothing else")
    void dispatcher_isScopedToSchedulingAndDispatch() {
        assertThat(seededGrants.get("DISPATCHER"))
                .containsExactlyInAnyOrder(
                        "shop:location:view",
                        "shop:bay:view",
                        "shop:bay:assign",
                        "shop:schedule:view",
                        "shop:schedule:edit",
                        "shop:technician:view",
                        "appointments:view",
                        "appointments:create",
                        "appointments:reschedule",
                        "appointments:cancel",
                        "workorder:workorder:view",
                        "workorder:workorder:assign-technician",
                        "people:availability:view",
                        // the assistant entrypoints every role carries
                        "mcp:chat:execute",
                        "mcp:chat:stream",
                        "nlti:request:submit",
                        "nlti:request:read");
    }

    @Test
    @DisplayName("every granted permission is defined in the seed, so no grant is dropped by the FK join")
    void everyGrantedPermissionIsDefinedInTheSeed() {
        Set<String> granted =
                seededGrants.values().stream().flatMap(Set::stream).collect(Collectors.toCollection(TreeSet::new));

        assertThat(granted).isSubsetOf(definedPermissions);
    }

    @Test
    @DisplayName("every granted permission is in the PermissionCode catalog, so it can reach a JWT")
    void everyGrantedPermissionHasABitIndex() {
        Set<String> uncatalogued = seededGrants.values().stream()
                .flatMap(Set::stream)
                .filter(permission -> PermissionCode.fromCode(permission).isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(uncatalogued)
                .as("granted permissions with no bit index would be silently dropped from perm_bits")
                .isEmpty();
    }

    @Test
    @DisplayName("permission definitions carry the bit index the PermissionCode catalog assigns")
    void permissionDefinitionsMatchTheCatalogBitIndexes() throws IOException {
        Map<String, Integer> mismatches = new LinkedHashMap<>();
        for (String line : readResource(SEED).lines().toList()) {
            Matcher row = PERMISSION_ROW.matcher(line);
            if (!row.matches()) {
                continue;
            }
            int seeded = Integer.parseInt(row.group(5));
            PermissionCode.fromCode(row.group(1))
                    .filter(code -> code.bitIndex() != seeded)
                    .ifPresent(code -> mismatches.put(row.group(1), seeded));
        }

        assertThat(mismatches)
                .as("seeded bit indexes that disagree with PermissionCode")
                .isEmpty();
    }

    @Test
    @DisplayName("every role receives the assistant entrypoints")
    void everyRoleReceivesTheAssistantBaseline() {
        // Per role, so a failure names the role that is missing them rather than just failing.
        seededGrants.forEach((role, granted) ->
                assertThat(granted).as("assistant baseline for %s", role).containsAll(ASSISTANT_BASELINE));
    }

    @Test
    @DisplayName("MCP administration is reserved for ADMIN and SYSTEM_ADMINISTRATOR")
    void mcpAdministrationIsReservedToAdminRoles() {
        assertThat(seededGrants.get("ADMIN")).containsAll(MCP_ADMINISTRATION);
        assertThat(seededGrants.get("SYSTEM_ADMINISTRATOR")).containsAll(MCP_ADMINISTRATION);

        // Scan the whole restricted surface, not just what both admin roles must hold:
        // mcp:tool:view is equally restricted, and checking only MCP_ADMINISTRATION would let it
        // leak to any role undetected.
        Set<String> holders = seededGrants.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(MCP_ADMINISTRATION_SURFACE::contains))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(holders)
                .as("MCP administration must not leak to operational or customer-facing roles")
                .containsExactly("ADMIN", "SYSTEM_ADMINISTRATOR");
    }

    @Test
    @DisplayName("SYSTEM_ADMINISTRATOR holds tool visibility and the NLTI audit ledger")
    void systemAdministratorHoldsToolViewAndNltiAudit() {
        assertThat(seededGrants.get("SYSTEM_ADMINISTRATOR")).contains("mcp:tool:view", "nlti:audit:read");
    }

    @Test
    @DisplayName("the NLTI audit ledger is reserved for ADMIN and SYSTEM_ADMINISTRATOR")
    void nltiAuditReadIsReservedToAdminRoles() {
        Set<String> holders = seededGrants.entrySet().stream()
                .filter(entry -> entry.getValue().contains("nlti:audit:read"))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(holders)
                .as("the NLTI audit ledger exposes other principals' request history")
                .containsExactly("ADMIN", "SYSTEM_ADMINISTRATOR");
    }

    @Test
    @DisplayName("ADMIN holds both tool permissions")
    void adminHoldsToolViewAndManage() {
        assertThat(seededGrants.get("ADMIN")).contains("mcp:tool:view", "mcp:tool:manage");
    }

    @Test
    @DisplayName("contains no duplicate role/permission rows")
    void seed_containsNoDuplicateRows() {
        Set<String> unique = new LinkedHashSet<>(rawGrantRows);

        assertThat(rawGrantRows).hasSameSizeAs(unique);
    }

    @Test
    @DisplayName("every role granted to exists by the time the seed runs")
    void everyGrantedRoleIsCreatableAtMigrationTime() throws IOException {
        Set<String> creatable = new TreeSet<>();

        // Roles created by the other migrations, plus the ones this seed creates itself.
        //
        // Each statement is bounded at its terminating semicolon rather than by a character
        // window. Under-reading would fail a correct migration whose VALUES list is long;
        // over-reading is worse, because running past the statement into this file's own grant
        // block would harvest the role names out of ('ADMIN', 'crm:party:view') rows and the
        // guard would vacuously pass. "INSERT INTO roles" does not prefix-match
        // "INSERT INTO role_permissions", so the grant block is not picked up as a source.
        Pattern quotedRoleName = Pattern.compile("'([A-Z][A-Z_]{2,})'");
        try (var files = Files.list(MIGRATIONS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".sql")).toList()) {
                String body = Files.readString(file);
                int from = body.indexOf("INSERT INTO roles");
                while (from >= 0) {
                    int end = body.indexOf(';', from);
                    Matcher name = quotedRoleName.matcher(body.substring(from, end < 0 ? body.length() : end));
                    while (name.find()) {
                        creatable.add(name.group(1));
                    }
                    from = body.indexOf("INSERT INTO roles", from + 1);
                }
            }
        }

        assertThat(creatable).as("no role-creating migration found").isNotEmpty();
        assertThat(seededGrants.keySet())
                .as("granting to a role no migration creates aborts startup: the JOIN resolves "
                        + "nothing and the seed's own assertion raises. RoleInitializer runs "
                        + "@PostConstruct, i.e. after Flyway, so roles it creates do not count "
                        + "unless this seed creates them too.")
                .isSubsetOf(creatable);
    }

    @Test
    @DisplayName("does not grant to the legacy roles nothing can ever assign")
    void doesNotGrantToUnreachableLegacyRoles() {
        assertThat(seededGrants.keySet()).doesNotContainAnyElementsOf(UNREACHABLE_LEGACY_ROLES);
    }

    private static String readResource(String name) throws IOException {
        try (InputStream in = RolePermissionBaselineTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in)
                    .as("resource %s not found on the test classpath", name)
                    .isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
