package com.positivity.securityservice.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.dto.RoleBulkIngestRecord;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #1613, D8 constraint 4: the drift check that replaces Flyway's environment guarantee.
 *
 * <p>Under Flyway every environment provably received the same roles. Once provisioning moves to
 * bulk load, the role set becomes a per-environment operator action — the same failure class as the
 * alpha {@code .env} and never-registered-permissions defects. The mitigation is a baseline file
 * versioned in this repo, and this test is what keeps that file honest.
 *
 * <p>While both the SQL seed and the baseline files exist, this asserts they agree, so the two
 * cannot silently diverge. When the seed is later reduced to the bootstrap floor, the same
 * assertions become the check that the baseline file is complete — nothing about them has to change.
 *
 * <p>{@code ADMIN} and {@code SYSTEM_ADMINISTRATOR} are excluded: they are the bootstrap floor
 * (D8 constraint 1). Creating a role through the API needs {@code security:role:create}, which needs
 * a role that holds it, which needs an admin user — so those two, and the seed admin account, can
 * never come from a load that requires them to already exist.
 */
@DisplayName("Role baseline drift (#1613 D8)")
class RoleBaselineDriftTest {

    /**
     * The intended bootstrap floor (D8 constraint 1): creating a role through the API needs
     * {@code security:role:create}, which needs a role that holds it, which needs an admin user.
     * These two, and the seed admin account, can never come from a load that requires them.
     */
    private static final Set<String> BOOTSTRAP_FLOOR = Set.of("ADMIN", "SYSTEM_ADMINISTRATOR");

    /**
     * Roles a fresh database still gets from Flyway despite the move, because they are created by
     * <em>versioned</em> migrations that are already applied everywhere. Editing those would change
     * their checksum and fail validation, so they stay — and the baseline file lists them too, which
     * is harmless because provisioning is idempotent.
     */
    private static final Set<String> VERSIONED_RESIDUE =
            Set.of("DISPATCHER", "SHOP_MANAGER", "SELF_SERVICE_CUSTOMER", "CONTROLLER");

    /**
     * Every role the platform is expected to have. Pinned here because after the move no single
     * source holds the whole set: Flyway owns the floor, the baseline file owns the rest. Adding a
     * role means editing this list, which is the forcing function — it is exactly the silent
     * addition this issue exists to prevent.
     */
    private static final Set<String> EXPECTED_ROLES = Set.of(
            "ACCOUNTING_ASSOCIATE",
            "ACCOUNT_MANAGER",
            "ADMIN",
            "CONTROLLER",
            "CUSTOMER",
            "DISPATCHER",
            "GENERAL_MANAGER",
            "INVENTORY_CONTROLLER",
            "INVENTORY_LEAD",
            "INVENTORY_MANAGER",
            "LOCATION_MANAGER",
            "MANAGER",
            "SELF_SERVICE_CUSTOMER",
            "SERVICE_ADVISOR",
            "SHOP_MANAGER",
            "SYSTEM_ADMINISTRATOR",
            "TECHNICIAN");

    private static final Path FIXTURES = Path.of("..", "scripts", "fixtures", "seed", "alpha", "security");
    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    /**
     * The whole {@code INSERT INTO roles ... ;} statement, because the seeds use both single-row and
     * multi-row VALUES lists — V3 inserts four roles in one statement, R__ one per statement.
     * Role names are then picked out by {@link #QUOTED_NAME}: they are the only upper-case quoted
     * tokens in these statements, descriptions being sentence case and actors lower case.
     */
    private static final Pattern ROLE_INSERT =
            Pattern.compile("INSERT\\s+INTO\\s+roles\\b(.*?);", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    // Permission names may be camelCase (people:timeException:view), so the character class must
    // not be lower-case only — a narrower one silently drops those grants from the comparison and
    // the check passes while the baseline is incomplete.
    private static final Pattern GRANT_PAIR =
            Pattern.compile("\\(\\s*'([A-Z_]+)'\\s*,\\s*'([A-Za-z0-9:_\\-]+)'\\s*\\)");
    private static final Pattern ROLE_DELETE = Pattern.compile(
            "DELETE\\s+FROM\\s+roles\\s+WHERE\\s+name\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED_NAME = Pattern.compile("'([A-Z_]+)'");

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    @DisplayName("every role the SQL seed creates, outside the bootstrap floor, is in the baseline file")
    void baselineCoversEverySeededRole() throws IOException {
        Set<String> seeded = new TreeSet<>(seededRoleNames());
        seeded.removeAll(BOOTSTRAP_FLOOR);

        Set<String> baseline = new TreeSet<>(readCsv(FIXTURES.resolve("roles.csv")).stream()
                .map(row -> row.get("name"))
                .toList());

        // A role in SQL and not in the file is exactly the drift this mitigation exists to catch:
        // it would vanish from any environment provisioned by load rather than by migration.
        assertThat(baseline).containsAll(seeded);
    }

    @Test
    @DisplayName("Flyway and the baseline file together cover the whole role set, with nothing invented")
    void floorAndBaselineCoverEveryExpectedRole() throws IOException {
        Set<String> covered = new TreeSet<>(seededRoleNames());
        covered.addAll(readCsv(FIXTURES.resolve("roles.csv")).stream()
                .map(row -> row.get("name"))
                .toList());

        // The union check is what proves nothing was lost when roles moved out of SQL: after the
        // move neither source holds the whole set on its own.
        assertThat(covered).containsExactlyInAnyOrderElementsOf(EXPECTED_ROLES);
    }

    @Test
    @DisplayName("the baseline file does not re-provision the bootstrap floor")
    void baselineExcludesTheBootstrapFloor() throws IOException {
        Set<String> baseline = readCsv(FIXTURES.resolve("roles.csv")).stream()
                .map(row -> row.get("name"))
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));

        // ADMIN and SYSTEM_ADMINISTRATOR are Flyway's, including their persona metadata. Listing
        // them here too would mean two sources for one role, and the loader — which treats an
        // existing role as success — would silently ignore whichever lost the race.
        assertThat(baseline).doesNotContainAnyElementsOf(BOOTSTRAP_FLOOR);
    }

    @Test
    @DisplayName("the baseline carries every grant Flyway still applies, so re-running it changes nothing")
    void baselineGrantsSupersetOfSeed() throws IOException {
        Map<String, Set<String>> seeded = seededGrants();
        Map<String, Set<String>> baseline = baselineGrants();

        // Flyway now grants only to the roles it still creates; the baseline holds all of them. A
        // superset rather than an equality: the baseline additionally carries the grants for every
        // role that moved out of SQL, which is the whole point of the move.
        // Compared per role, because "SHOP_MANAGER lost 12 grants" is actionable and a total is not.
        assertThat(baseline.keySet()).containsAll(seeded.keySet());
        for (Map.Entry<String, Set<String>> entry : seeded.entrySet()) {
            assertThat(baseline.get(entry.getKey()))
                    .as("grants for %s", entry.getKey())
                    .containsAll(entry.getValue());
        }
    }

    @Test
    @DisplayName("the baseline carries grants for every expected role")
    void baselineGrantsCoverEveryExpectedRole() throws IOException {
        // A role provisioned with no grants can sign in and do nothing, which reads as a broken
        // environment rather than a deliberately empty one.
        assertThat(baselineGrants().keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_ROLES);
    }

    @Test
    @DisplayName("Flyway grants only to the roles Flyway still creates")
    void seedGrantsOnlyToRolesItCreates() throws IOException {
        // The grant seed ends in a guard that raises if it names a role that does not exist, so a
        // grant left behind for a moved role is not a silent no-op — it fails the migration.
        Set<String> allowed = new TreeSet<>(BOOTSTRAP_FLOOR);
        allowed.addAll(VERSIONED_RESIDUE);

        assertThat(seededGrants().keySet()).isSubsetOf(allowed);
        assertThat(seededRoleNames()).isSubsetOf(allowed);
    }

    private static Map<String, Set<String>> baselineGrants() throws IOException {
        Map<String, Set<String>> baseline = new LinkedHashMap<>();
        for (Map<String, String> row : readCsv(FIXTURES.resolve("role-permissions.csv"))) {
            baseline.put(row.get("roleName"), splitPermissions(row.get("permissions")));
        }
        return baseline;
    }

    @Test
    @DisplayName("every persona slot in the baseline file passes the same validation the API applies")
    void baselinePersonasPassValidation() throws IOException {
        // The bulk-load file is a reviewed path, but reviewed is not a reason to relax D9 control 1.
        // A slot the loader would accept and the API would reject is a contradiction that only
        // surfaces when somebody later edits that role through the endpoint.
        for (Map<String, String> row : readCsv(FIXTURES.resolve("roles.csv"))) {
            RoleBulkIngestRecord record = new RoleBulkIngestRecord(
                    row.get("name"),
                    blankToNull(row.get("description")),
                    blankToNull(row.get("personaTitle")),
                    blankToNull(row.get("personaFocus")),
                    blankToNull(row.get("personaTone")),
                    null,
                    null);
            Set<ConstraintViolation<RoleBulkIngestRecord>> violations = validator.validate(record);
            assertThat(violations)
                    .as("persona slots for %s: %s", row.get("name"), messages(violations))
                    .isEmpty();
        }
    }

    private static String messages(Set<ConstraintViolation<RoleBulkIngestRecord>> violations) {
        return violations.stream().map(ConstraintViolation::getMessage).toList().toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Set<String> seededRoleNames() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        try (var files = Files.list(MIGRATIONS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                Matcher statement = ROLE_INSERT.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (statement.find()) {
                    Matcher name = QUOTED_NAME.matcher(statement.group(1));
                    while (name.find()) {
                        names.add(name.group(1));
                    }
                }
            }
        }
        // V23 drops candidate roles that were never ratified; a name only ever inserted by a
        // migration that a later one deletes is not part of the baseline.
        names.removeAll(droppedRoleNames());
        return names;
    }

    /**
     * Names removed by {@code DELETE FROM roles}, read from that statement alone.
     *
     * <p>Scoped to the statement rather than the whole file on purpose: V23's comment explains which
     * candidate roles were <em>ratified</em> and names them, so a file-wide scan reads SHOP_MANAGER
     * and DISPATCHER out of the prose and concludes they were dropped.
     */
    private static Set<String> droppedRoleNames() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        try (var files = Files.list(MIGRATIONS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                Matcher statement = ROLE_DELETE.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (statement.find()) {
                    Matcher name = QUOTED_NAME.matcher(statement.group(1));
                    while (name.find()) {
                        names.add(name.group(1));
                    }
                }
            }
        }
        return names;
    }

    private static Map<String, Set<String>> seededGrants() throws IOException {
        Map<String, Set<String>> grants = new LinkedHashMap<>();
        Matcher matcher = GRANT_PAIR.matcher(
                Files.readString(MIGRATIONS.resolve("R__seed_role_permissions.sql"), StandardCharsets.UTF_8));
        while (matcher.find()) {
            grants.computeIfAbsent(matcher.group(1), key -> new LinkedHashSet<>())
                    .add(matcher.group(2));
        }
        return grants;
    }

    private static Set<String> splitPermissions(String value) {
        Set<String> permissions = new LinkedHashSet<>();
        for (String permission : value.split(";")) {
            if (!permission.isBlank()) {
                permissions.add(permission.trim());
            }
        }
        return permissions;
    }

    /** Minimal RFC-4180 reader: enough for these fixtures, which quote only embedded commas. */
    private static List<Map<String, String>> readCsv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<String> headers = splitCsvLine(lines.getFirst());
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            List<String> values = splitCsvLine(line);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                row.put(headers.get(i), i < values.size() ? values.get(i) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<String> splitCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }
}
