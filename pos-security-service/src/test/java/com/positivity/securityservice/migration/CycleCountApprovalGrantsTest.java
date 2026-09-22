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
 * Who may approve the write-off a cycle count produces (#2149).
 *
 * <p>#2149 was a location manager refused {@code POST /v1/inventory/cycleCountAdjustments/{id}/approve}
 * on alpha. The count is the clerk's and the write-off is the manager's, so the refusal stopped the
 * second half of a flow whose first half worked, and ended an accelerated run on virtual day 7.
 *
 * <p>Two things about the diagnosis are worth keeping, because both cost time. The authority is
 * {@code inventory:adjustment:approve}, not the {@code inventory:cycle_count:approve} the issue
 * assumed — the cycle-count family stops at {@code initiate}/{@code view}/{@code complete}, and the
 * adjustment family is what the approval endpoint enforces. And unlike #2138, whose grant was right
 * in the repo and missing only from the deployed tenant, here LOCATION_MANAGER held no adjustment
 * grant in {@code role-permissions.csv} either, so re-seeding alpha would not have fixed it.
 *
 * <p>Read both grant sources for the same reason {@link DispatchPlacementGrantsTest} does: Flyway's
 * {@code R__seed_role_permissions.sql} carries the floor roles, the bulk-load baseline CSV carries
 * every role that moved to bulk load (#1613 D8), and LOCATION_MANAGER exists only in the latter. The
 * per-source assertion is the one that matters — a tenant's rows come from one source, so a grant
 * present only in the other still leaves a manager refused.
 *
 * <p>The load-bearing assertion is {@link #completingACountImpliesSomeoneCanPostIt()}: it states the
 * invariant rather than a list, so a role given the counting half without anyone holding the
 * write-off half fails here instead of on alpha, seven virtual days in.
 */
@DisplayName("cycle count approval grants (#2149)")
class CycleCountApprovalGrantsTest {

    private static final String ADJUSTMENT_APPROVE = "inventory:adjustment:approve";
    private static final String ADJUSTMENT_CREATE = "inventory:adjustment:create";
    private static final String ADJUSTMENT_OVERRIDE = "inventory:adjustment:override";
    private static final String COUNT_COMPLETE = "inventory:cycle_count:complete";

    private static final Path FIXTURES = Path.of("..", "scripts", "fixtures", "seed", "alpha", "security");
    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    private static final Pattern GRANT_PAIR =
            Pattern.compile("\\(\\s*'([A-Z_]+)'\\s*,\\s*'([A-Za-z0-9:_\\-]+)'\\s*\\)");

    /** Role → grants, from both sources merged. */
    private static Map<String, Set<String>> grants;

    /** The bulk-load baseline alone, the only source for LOCATION_MANAGER. */
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

        // Every assertion below is set membership, which passes vacuously over an empty map.
        assertThat(grants).as("no grants parsed out of either source").isNotEmpty();
        assertThat(grants.keySet()).contains("ADMIN", "INVENTORY_LEAD", "INVENTORY_MANAGER", "LOCATION_MANAGER");
    }

    @Test
    @DisplayName("the manager who runs a location's counts may approve the write-off")
    void locationManagerMayApproveAnAdjustment() {
        // The direct #2149 pin. LOCATION_MANAGER is the role diana.rowe holds
        // (scripts/fixtures/seed/alpha/security/users.csv).
        assertThat(grants.get("LOCATION_MANAGER")).contains(ADJUSTMENT_APPROVE);
    }

    @Test
    @DisplayName("the bulk-load baseline is the source that carries it")
    void theBaselineCsvGrantsApproval() {
        // Asserted on the CSV alone, not the union: LOCATION_MANAGER is bulk-loaded (#1613 D8) and
        // the Flyway seed never provisions it, so a grant that landed only in SQL would still leave
        // the manager refused on a tenant — which is the shape #2138 arrived in.
        assertThat(csv.get("LOCATION_MANAGER"))
                .as("bulk-load baseline: LOCATION_MANAGER")
                .contains(ADJUSTMENT_APPROVE);
    }

    @Test
    @DisplayName("a role that may finish a count does not strand the adjustment it produces")
    void completingACountImpliesSomeoneCanPostIt() {
        // A completed count raises an adjustment that only inventory:adjustment:approve can post or
        // reject. Counting and approving are deliberately different people, so a counter is not
        // required to hold approval itself — but some role must, or the count is a dead end.
        Set<String> counters = rolesHolding(COUNT_COMPLETE);
        assertThat(counters)
                .as("no role holds %s — the parse is broken", COUNT_COMPLETE)
                .isNotEmpty();
        assertThat(rolesHolding(ADJUSTMENT_APPROVE))
                .as("a cycle count can be completed but its adjustment can never be posted (#2149)")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the clerk who raises a count may not approve their own write-off")
    void theCountingRoleDoesNotApprove() {
        // INVENTORY_LEAD is the parts-clerk persona (gloria.mendez) that raises the adjustment in the
        // accelerated suite. Granting it approval would collapse the separation the flow is built on.
        assertThat(grants.get("INVENTORY_LEAD")).contains(ADJUSTMENT_CREATE).doesNotContain(ADJUSTMENT_APPROVE);
    }

    @Test
    @DisplayName("approval stays with inventory and location management")
    void approvalIsNotHandedToTheFloor() {
        assertThat(rolesHolding(ADJUSTMENT_APPROVE))
                .as("posting an adjustment to the ledger is a manager and controller authority")
                .isSubsetOf(Set.of("ADMIN", "INVENTORY_CONTROLLER", "INVENTORY_MANAGER", "LOCATION_MANAGER"));
        assertThat(grants.get("TECHNICIAN")).doesNotContain(ADJUSTMENT_APPROVE, ADJUSTMENT_CREATE);
        assertThat(grants.get("SERVICE_ADVISOR")).doesNotContain(ADJUSTMENT_APPROVE, ADJUSTMENT_CREATE);
    }

    @Test
    @DisplayName("the negative-stock override is not carried along with approval")
    void approvalDoesNotImplyTheNegativeStockOverride() {
        // inventory:adjustment:override waives the negative-stock policy on overridable postings
        // (ScrapServiceImpl.NEGATIVE_STOCK_OVERRIDE_PERMISSION). #2149 needed the ledger posting, not
        // the policy waiver, and LOCATION_MANAGER was deliberately not given it.
        assertThat(grants.get("LOCATION_MANAGER")).doesNotContain(ADJUSTMENT_OVERRIDE);
        assertThat(rolesHolding(ADJUSTMENT_OVERRIDE)).isSubsetOf(Set.of("ADMIN", "INVENTORY_CONTROLLER"));
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
