package com.positivity.securityservice.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.enums.LocationHierarchy;
import com.positivity.securityservice.internal.enums.LocationScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the per-role {@code location_scope} / {@code location_hierarchy} seed (ADR-0061 §2 and its
 * 2026-09-07 amendment, #1868), which lives in the repeatable {@code R__seed_role_location_scope.sql}
 * since the migration history was flattened (formerly V37). The values are decisions, not defaults,
 * so a change here must be a deliberate edit to both the seed and this test. The column defaults are
 * pinned against the baseline that creates the columns.
 */
@DisplayName("Role location scope seed (R__seed_role_location_scope, ADR-0061)")
class RoleLocationScopeSeedTest {

    private static final Path MIGRATION =
            Path.of("src", "main", "resources", "db", "migration", "R__seed_role_location_scope.sql");
    private static final Path BASELINE =
            Path.of("src", "main", "resources", "db", "migration", "V1__baseline_security_service.sql");

    private static final Pattern SCOPE_UPDATE = Pattern.compile(
            "UPDATE\\s+roles\\s+SET\\s+location_scope\\s*=\\s*'([A-Z]+)'\\s*,\\s*location_hierarchy\\s*=\\s*'([A-Z]+)'"
                    + "\\s*WHERE\\s+name\\s+IN\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED_NAME = Pattern.compile("'([A-Z_]+)'");
    /** The baseline is pg_dump output: {@code location_scope character varying(16) DEFAULT 'ALL'::character varying NOT NULL}. */
    private static final Pattern SCOPE_DEFAULT = Pattern.compile(
            "location_scope\\s+character varying\\(16\\)\\s+DEFAULT\\s+'([A-Z]+)'", Pattern.CASE_INSENSITIVE);

    private static final Pattern HIERARCHY_DEFAULT = Pattern.compile(
            "location_hierarchy\\s+character varying\\(16\\)\\s+DEFAULT\\s+'([A-Z]+)'", Pattern.CASE_INSENSITIVE);

    /** The seed table from the issue and ADR-0061 §2, verbatim. */
    private static final Map<String, Seed> EXPECTED = Map.ofEntries(
            Map.entry("ADMIN", new Seed(LocationScope.ALL, LocationHierarchy.OTHER)),
            Map.entry("SYSTEM_ADMINISTRATOR", new Seed(LocationScope.ALL, LocationHierarchy.OTHER)),
            Map.entry("CONTROLLER", new Seed(LocationScope.ALL, LocationHierarchy.FINANCIAL)),
            Map.entry("ACCOUNT_MANAGER", new Seed(LocationScope.LOCATION, LocationHierarchy.FINANCIAL)),
            Map.entry("ACCOUNTANT", new Seed(LocationScope.LOCATION, LocationHierarchy.FINANCIAL)),
            Map.entry("GENERAL_MANAGER", new Seed(LocationScope.LOCATION, LocationHierarchy.FINANCIAL)),
            Map.entry("INVENTORY_CONTROLLER", new Seed(LocationScope.ALL, LocationHierarchy.OTHER)),
            Map.entry("INVENTORY_MANAGER", new Seed(LocationScope.LOCATION, LocationHierarchy.OTHER)),
            Map.entry("LOCATION_MANAGER", new Seed(LocationScope.LOCATION, LocationHierarchy.OTHER)),
            Map.entry("SHOP_MANAGER", new Seed(LocationScope.LOCATION, LocationHierarchy.OTHER)),
            Map.entry("MANAGER", new Seed(LocationScope.LOCATION, LocationHierarchy.OTHER)),
            Map.entry("SERVICE_ADVISOR", new Seed(LocationScope.LOCATION, LocationHierarchy.OTHER)),
            Map.entry("TECHNICIAN", new Seed(LocationScope.LOCATION, LocationHierarchy.OTHER)),
            Map.entry("DISPATCHER", new Seed(LocationScope.LOCATION, LocationHierarchy.OTHER)),
            Map.entry("SELF_SERVICE_CUSTOMER", new Seed(LocationScope.ALL, LocationHierarchy.OTHER)));

    private record Seed(LocationScope scope, LocationHierarchy hierarchy) {}

    private static String sql;
    private static String baseline;
    private static Map<String, Seed> seeded;

    @BeforeAll
    static void parse() throws IOException {
        sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        baseline = Files.readString(BASELINE, StandardCharsets.UTF_8);
        seeded = new LinkedHashMap<>();
        Matcher statement = SCOPE_UPDATE.matcher(sql);
        while (statement.find()) {
            Seed seed =
                    new Seed(LocationScope.valueOf(statement.group(1)), LocationHierarchy.valueOf(statement.group(2)));
            Matcher name = QUOTED_NAME.matcher(statement.group(3));
            while (name.find()) {
                Seed previous = seeded.put(name.group(1), seed);
                assertThat(previous)
                        .as("%s is seeded twice in %s", name.group(1), MIGRATION.getFileName())
                        .isNull();
            }
        }
        assertThat(seeded)
                .as("no scope UPDATE parsed out of %s — the extraction is broken", MIGRATION)
                .isNotEmpty();
    }

    @Test
    @DisplayName(
            "every role in the ADR table is seeded with exactly the recorded values, and no other role is narrowed")
    void seedMatchesAdrTableExactly() {
        assertThat(seeded).containsExactlyInAnyOrderEntriesOf(EXPECTED);
    }

    @Test
    @DisplayName("INVENTORY_CONTROLLER is an inventory role: OTHER, never FINANCIAL by a name match on CONTROLLER")
    void inventoryController_isOther() {
        assertThat(seeded.get("INVENTORY_CONTROLLER").hierarchy()).isEqualTo(LocationHierarchy.OTHER);
        assertThat(seeded.get("CONTROLLER").hierarchy()).isEqualTo(LocationHierarchy.FINANCIAL);
    }

    @Test
    @DisplayName("the #1373 pair now differs: INVENTORY_MANAGER is LOCATION, INVENTORY_CONTROLLER is ALL")
    void inventoryManagerAndController_differByReach() {
        Seed manager = seeded.get("INVENTORY_MANAGER");
        Seed controller = seeded.get("INVENTORY_CONTROLLER");

        assertThat(manager.scope()).isEqualTo(LocationScope.LOCATION);
        assertThat(controller.scope()).isEqualTo(LocationScope.ALL);
        assertThat(manager).isNotEqualTo(controller);
    }

    @Test
    @DisplayName("only the four ADR-named roles are FINANCIAL")
    void onlyNamedRolesAreFinancial() {
        assertThat(seeded.entrySet().stream()
                        .filter(e -> e.getValue().hierarchy() == LocationHierarchy.FINANCIAL)
                        .map(Map.Entry::getKey))
                .containsExactlyInAnyOrder("ACCOUNT_MANAGER", "ACCOUNTANT", "CONTROLLER", "GENERAL_MANAGER");
    }

    @Test
    @DisplayName("column defaults are ALL / OTHER so a role created later never widens or narrows by omission")
    void columnDefaultsPreserveTodaysBehaviour() {
        Matcher scope = SCOPE_DEFAULT.matcher(baseline);
        Matcher hierarchy = HIERARCHY_DEFAULT.matcher(baseline);
        assertThat(scope.find()).isTrue();
        assertThat(hierarchy.find()).isTrue();
        assertThat(LocationScope.valueOf(scope.group(1))).isEqualTo(LocationScope.ALL);
        assertThat(LocationHierarchy.valueOf(hierarchy.group(1))).isEqualTo(LocationHierarchy.OTHER);
    }

    @Test
    @DisplayName(
            "the scope seed creates no roles: the drift and persona tests harvest names from role INSERTs, and scope literals are upper case")
    void migrationInsertsNoRoles() {
        assertThat(sql.toUpperCase(java.util.Locale.ROOT)).doesNotContain("INSERT INTO ROLES");
        // Asserted on the harvested names rather than on the literal set (S5841): with the
        // operands the other way round an empty harvest passed zero expected elements and the
        // check held vacuously, which is the one case it exists to catch.
        assertThat(seeded.keySet())
                .isNotEmpty()
                .doesNotContainAnyElementsOf(Set.of("ALL", "LOCATION", "FINANCIAL", "OTHER"));
    }
}
