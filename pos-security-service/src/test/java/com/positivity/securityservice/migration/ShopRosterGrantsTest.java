package com.positivity.securityservice.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who may read a location's technician roster alongside the schedule they run.
 *
 * <p>On alpha the shop capacity calendar ({@code /app/shopmgmt/schedule}) opened for {@code admin.alpha}
 * and then got a bare {@code 403} on {@code GET /v1/shop-manager/{locationId}/technicians}: the page is
 * gated on {@code shop:schedule:view}, the roster endpoint enforces {@code shop:technician:view}, and
 * ADMIN held the first and not the second. The calendar degrades rather than failing, so the symptom
 * was an empty roster and capacity counted with no technicians. LOCATION_MANAGER, which edits the
 * same schedule and assigns bays on it, was in the same state.
 *
 * <p>The load-bearing assertion states the invariant rather than a list: a role that builds the
 * schedule (edits it, assigns bays on it, or watches the shop dashboard) must be able to see who is
 * on it, so a role added later with half of scheduling fails here instead of on alpha. Both grant
 * sources are read, as in {@link DispatchPlacementGrantsTest}: the Flyway seed provisions the floor
 * roles on every deploy, and the bulk-load baseline CSV is the only source for LOCATION_MANAGER.
 */
@DisplayName("technician roster grants for schedulers")
class ShopRosterGrantsTest {

    private static final String ROSTER = "shop:technician:view";
    private static final Set<String> SCHEDULING =
            Set.of("shop:schedule:edit", "shop:bay:assign", "shop:dashboard:view");

    private static final Path FIXTURES = Path.of("..", "scripts", "fixtures", "seed", "alpha", "security");
    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    private static final Pattern GRANT_PAIR =
            Pattern.compile("\\(\\s*'([A-Z_]+)'\\s*,\\s*'([A-Za-z0-9:_\\-]+)'\\s*\\)");

    private static Map<String, Set<String>> csv;
    private static Map<String, Set<String>> sql;

    @BeforeAll
    static void readBothGrantSources() throws IOException {
        csv = csvBaselineGrants();
        sql = sqlSeedGrants();
        // Every assertion below is set membership, which passes vacuously over an empty parse.
        assertThat(csv).as("no grants parsed from the baseline CSV").containsKeys("ADMIN", "LOCATION_MANAGER");
        assertThat(sql).as("no grants parsed from the Flyway seed").containsKeys("ADMIN", "SHOP_MANAGER");
    }

    @Test
    @DisplayName("every role that builds the schedule may read the technician roster, in each source")
    void schedulersHoldTheRoster() {
        // Asserted per source, not on the union: a tenant's rows come from one of the two.
        for (Map.Entry<String, Map<String, Set<String>>> source :
                Map.of("baseline CSV", csv, "Flyway seed", sql).entrySet()) {
            Set<String> schedulers = rolesHoldingAny(source.getValue(), SCHEDULING);
            assertThat(schedulers)
                    .as("%s: no role holds any of %s — the parse is broken", source.getKey(), SCHEDULING)
                    .isNotEmpty();
            assertThat(schedulers)
                    .allSatisfy(role -> assertThat(source.getValue().get(role))
                            .as("%s: %s builds the schedule but cannot read who is on it", source.getKey(), role)
                            .contains(ROSTER));
        }
    }

    @Test
    @DisplayName("ADMIN and LOCATION_MANAGER may read the roster")
    void adminAndLocationManagerHoldTheRoster() {
        assertThat(sql.get("ADMIN")).as("Flyway floor: ADMIN").contains(ROSTER);
        assertThat(csv.get("ADMIN")).as("bulk-load baseline: ADMIN").contains(ROSTER);
        assertThat(csv.get("LOCATION_MANAGER"))
                .as("bulk-load baseline: LOCATION_MANAGER")
                .contains(ROSTER);
    }

    private static Set<String> rolesHoldingAny(Map<String, Set<String>> grants, Set<String> permissions) {
        Set<String> roles = new TreeSet<>();
        grants.forEach((role, held) -> {
            if (held.stream().anyMatch(permissions::contains)) {
                roles.add(role);
            }
        });
        return roles;
    }

    /** {@code roleName,permissions} with the grants semicolon-separated; no embedded commas. */
    private static Map<String, Set<String>> csvBaselineGrants() throws IOException {
        Map<String, Set<String>> baseline = new TreeMap<>();
        List<String> lines = Files.readAllLines(FIXTURES.resolve("role-permissions.csv"), StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf(',');
            Set<String> permissions = new LinkedHashSet<>();
            for (String permission : line.substring(separator + 1).split(";")) {
                if (!permission.isBlank()) {
                    permissions.add(permission.trim());
                }
            }
            baseline.put(line.substring(0, separator).trim(), permissions);
        }
        return baseline;
    }

    private static Map<String, Set<String>> sqlSeedGrants() throws IOException {
        Map<String, Set<String>> seeded = new LinkedHashMap<>();
        Matcher matcher = GRANT_PAIR.matcher(
                Files.readString(MIGRATIONS.resolve("R__seed_role_permissions.sql"), StandardCharsets.UTF_8));
        while (matcher.find()) {
            seeded.computeIfAbsent(matcher.group(1), key -> new LinkedHashSet<>())
                    .add(matcher.group(2));
        }
        return seeded;
    }
}
