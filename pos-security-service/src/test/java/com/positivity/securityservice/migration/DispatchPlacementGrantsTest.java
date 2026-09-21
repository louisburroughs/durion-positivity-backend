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
 * Who may place a workorder on a bay or a mobile unit, and who may not (#2138, #2059).
 *
 * <p>#2138 was a manager refused {@code PUT /v1/workorders/{id}/position} on alpha while a
 * technician was correctly refused the same endpoint, which left nobody able to start a workorder:
 * a start needs both a technician and a position (#2010/#2011). The endpoint's authority is
 * {@code workorder:position:assign}, minted by #2059 to separate everyday dispatch from
 * {@code workorder:operationalContext:override}, the manager exception path that also rewrites a
 * workorder's mechanics and location.
 *
 * <p>Nothing pinned the grant to a role. The controller test proves the endpoint enforces the code,
 * and {@link RoleBaselineDriftTest} proves the two grant sources agree with each other, but neither
 * answers "can the shop manager actually place a job", which is the question the issue asked. Both
 * sources are read here because the two halves of the role set live in different places: Flyway's
 * {@code R__seed_role_permissions.sql} carries the floor roles, and
 * {@code scripts/fixtures/seed/alpha/security/role-permissions.csv} carries every role that moved to
 * bulk load (#1613 D8) — LOCATION_MANAGER, the role the refused manager holds, exists only there.
 *
 * <p>The load-bearing assertion is the {@code shop:bay:assign} one: it states the invariant rather
 * than a list, so a role added later with half of dispatch fails here instead of on alpha. Deciding
 * where a job goes on the schedule and placing the job there are one action, and a role that can do
 * the first and not the second cannot finish what it started.
 */
@DisplayName("workorder placement grants (#2138)")
class DispatchPlacementGrantsTest {

    private static final String PLACEMENT = "workorder:position:assign";
    private static final String BAY_ASSIGN = "shop:bay:assign";
    private static final String CONTEXT_OVERRIDE = "workorder:operationalContext:override";

    private static final Path FIXTURES = Path.of("..", "scripts", "fixtures", "seed", "alpha", "security");
    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    /** Permission names may be camelCase ({@code workorder:operationalContext:override}). */
    private static final Pattern GRANT_PAIR =
            Pattern.compile("\\(\\s*'([A-Z_]+)'\\s*,\\s*'([A-Za-z0-9:_\\-]+)'\\s*\\)");

    /** Role → grants, from both sources merged: a role may be created by either one. */
    private static Map<String, Set<String>> grants;

    /** The bulk-load baseline alone, for the roles it is the only source of. */
    private static Map<String, Set<String>> csv;

    /** The Flyway seed alone, for the floor roles it provisions on every deploy. */
    private static Map<String, Set<String>> sql;

    @BeforeAll
    static void readBothGrantSources() throws IOException {
        csv = csvBaselineGrants();
        sql = sqlSeedGrants();
        grants = new TreeMap<>();
        csv.forEach((role, permissions) ->
                grants.computeIfAbsent(role, key -> new TreeSet<>()).addAll(permissions));
        sql.forEach((role, permissions) ->
                grants.computeIfAbsent(role, key -> new TreeSet<>()).addAll(permissions));

        // Both parses are regex over files whose formatting can legitimately change, and every
        // assertion below is set membership — which passes vacuously over an empty map.
        assertThat(grants).as("no grants parsed out of either source").isNotEmpty();
        assertThat(grants.keySet()).contains("ADMIN", "DISPATCHER", "LOCATION_MANAGER", "SHOP_MANAGER", "TECHNICIAN");
    }

    @Test
    @DisplayName("every role that may assign a bay may also place a workorder on one")
    void bayAssignImpliesPlacement() {
        Set<String> bayAssigners = rolesHolding(BAY_ASSIGN);

        assertThat(bayAssigners)
                .as("no role holds %s — the parse is broken", BAY_ASSIGN)
                .isNotEmpty();
        assertThat(bayAssigners)
                .allSatisfy(role -> assertThat(grants.get(role))
                        .as("%s may assign a bay on the schedule but not place a workorder on one (#2138)", role)
                        .contains(PLACEMENT));
    }

    @Test
    @DisplayName("a manager may place a workorder on a bay or a mobile unit")
    void managerRolesHoldPlacement() {
        // LOCATION_MANAGER is the role the manager refused in #2138 holds; SHOP_MANAGER is the
        // Flyway floor's equivalent, and DISPATCHER is the role #2059 minted the code for.
        assertThat(grants.get("LOCATION_MANAGER")).contains(PLACEMENT);
        assertThat(grants.get("SHOP_MANAGER")).contains(PLACEMENT);
        assertThat(grants.get("DISPATCHER")).contains(PLACEMENT);
    }

    @Test
    @DisplayName("each source grants placement to the roles it is responsible for provisioning")
    void bothSourcesGrantPlacement() {
        // Asserted per source, not on the union: a tenant's rows come from one of the two, so a
        // grant present only in the other still leaves a manager refused — which is the shape #2138
        // arrived in. The Flyway seed provisions its floor roles on every deploy; the baseline CSV is
        // the only source for LOCATION_MANAGER and the one a role load applies.
        assertThat(sql.get("SHOP_MANAGER")).as("Flyway floor: SHOP_MANAGER").contains(PLACEMENT);
        assertThat(sql.get("DISPATCHER")).as("Flyway floor: DISPATCHER").contains(PLACEMENT);
        assertThat(csv.get("LOCATION_MANAGER"))
                .as("bulk-load baseline: LOCATION_MANAGER")
                .contains(PLACEMENT);
        assertThat(csv.get("SHOP_MANAGER"))
                .as("bulk-load baseline: SHOP_MANAGER")
                .contains(PLACEMENT);
        assertThat(csv.get("DISPATCHER")).as("bulk-load baseline: DISPATCHER").contains(PLACEMENT);
    }

    @Test
    @DisplayName("a technician may not place a workorder, and may not override its context")
    void technicianHoldsNeitherPlacementNorOverride() {
        // The other half of #2138: the suite's technician-refused assertion passed, and must keep
        // passing. Where a job is worked is dispatch's decision, not the technician's.
        assertThat(grants.get("TECHNICIAN")).doesNotContain(PLACEMENT).doesNotContain(CONTEXT_OVERRIDE);
        assertThat(grants.get("SERVICE_ADVISOR")).doesNotContain(PLACEMENT).doesNotContain(CONTEXT_OVERRIDE);
    }

    @Test
    @DisplayName("the override grant stays on the manager exception path, not on everyday dispatch")
    void dispatcherDoesNotHoldTheOverrideGrant() {
        // #2059's decision: overrideOperationalContext also rewrites a workorder's mechanics and
        // location, so handing it to every dispatcher was the wrong way to unblock the board.
        assertThat(grants.get("DISPATCHER")).doesNotContain(CONTEXT_OVERRIDE);
        assertThat(rolesHolding(CONTEXT_OVERRIDE))
                .as("the override grant is a manager and admin authority")
                .isSubsetOf(Set.of("ADMIN", "GENERAL_MANAGER", "LOCATION_MANAGER", "MANAGER", "SHOP_MANAGER"));
    }

    private static Set<String> rolesHolding(String permission) {
        Set<String> roles = new TreeSet<>();
        grants.forEach((role, permissions) -> {
            if (permissions.contains(permission)) {
                roles.add(role);
            }
        });
        return roles;
    }

    /** {@code roleName,permissions} with the grants semicolon-separated; no embedded commas. */
    private static Map<String, Set<String>> csvBaselineGrants() throws IOException {
        Map<String, Set<String>> baseline = new LinkedHashMap<>();
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
