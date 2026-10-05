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
 * Who may approve a scrap write-off (#2472).
 *
 * <p>#2472 was a location manager refused {@code POST /v1/inventory/scraps/{scrapId}/approve} on
 * alpha. The accelerated run raises a scrap write-off about every ten virtual days; the inventory
 * clerk creates it and the manager approves it, and all 29 approvals in the 2026-10-04 run were
 * refused with a bare {@code FORBIDDEN}. It is the scrap twin of #2149 (cycle count write-off
 * approval): LOCATION_MANAGER had never held {@code inventory:scrap:approve} in
 * {@code role-permissions.csv}, so re-seeding alpha would not have fixed it.
 *
 * <p>Read both grant sources for the same reason {@link CycleCountApprovalGrantsTest} does: Flyway's
 * {@code R__seed_role_permissions.sql} carries the floor roles, the bulk-load baseline CSV carries
 * every role that moved to bulk load (#1613 D8), and LOCATION_MANAGER exists only in the latter. The
 * per-source assertion is the one that matters — a tenant's rows come from one source, so a grant
 * present only in the other still leaves a manager refused.
 *
 * <p>The load-bearing assertion is {@link #creatingAScrapImpliesSomeoneCanApproveIt()}: an
 * above-threshold scrap waits in {@code PENDING_APPROVAL} until a {@code inventory:scrap:approve}
 * holder posts or rejects it, so a role given the creating half without anyone holding the approving
 * half fails here instead of on alpha.
 */
@DisplayName("scrap approval grants (#2472)")
class ScrapApprovalGrantsTest {

    private static final String SCRAP_APPROVE = "inventory:scrap:approve";
    private static final String SCRAP_CREATE = "inventory:scrap:create";
    private static final String SCRAP_VIEW = "inventory:scrap:view";
    private static final String ADJUSTMENT_OVERRIDE = "inventory:adjustment:override";

    private static final Path FIXTURES = Path.of("..", "scripts", "fixtures", "seed", "alpha", "security");
    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    private static final Pattern GRANT_PAIR =
            Pattern.compile("\\(\\s*'([A-Z_]+)'\\s*,\\s*'([A-Za-z0-9:_\\-]+)'\\s*\\)");

    /** Role → grants, from both sources merged. */
    private static Map<String, Set<String>> grants;

    /** The bulk-load baseline alone, the only source for LOCATION_MANAGER. */
    private static Map<String, Set<String>> csv;

    @BeforeAll
    static void readBothGrantSources() throws IOException {
        csv = csvBaselineGrants();
        Map<String, Set<String>> sql = sqlSeedGrants();
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
    @DisplayName("the location manager may approve a scrap write-off, and may not raise one")
    void locationManagerMayApproveAScrapButNotCreateOne() {
        // The direct #2472 pin. LOCATION_MANAGER is the role diana.rowe holds
        // (scripts/fixtures/seed/alpha/security/users.csv).
        assertThat(grants.get("LOCATION_MANAGER")).contains(SCRAP_APPROVE);

        // Both halves of the separation, pinned on the role this issue widened: handing the
        // approver `create` would let one person raise a write-off and post it.
        assertThat(grants.get("LOCATION_MANAGER")).doesNotContain(SCRAP_CREATE);
    }

    @Test
    @DisplayName("the approval grant alone is enough: no separate view grant rides along")
    void approvalNeedsNoSeparateViewGrant() {
        // getScrap and listScraps accept inventory:scrap:view or inventory:scrap:approve, so the
        // approver reaches the scrap it approves without :view. Granting :view as well would hide a
        // regression that narrowed those reads to hasAuthority(view) — ScrapControllerTest in
        // pos-inventory pins the reads against an approve-only caller.
        assertThat(grants.get("LOCATION_MANAGER")).doesNotContain(SCRAP_VIEW);
    }

    @Test
    @DisplayName("the bulk-load baseline is the source that carries it")
    void theBaselineCsvGrantsApproval() {
        // Asserted on the CSV alone, not the union: LOCATION_MANAGER is bulk-loaded (#1613 D8) and
        // the Flyway seed never provisions it, so a grant that landed only in SQL would still leave
        // the manager refused on a tenant.
        assertThat(csv.get("LOCATION_MANAGER"))
                .as("bulk-load baseline: LOCATION_MANAGER")
                .contains(SCRAP_APPROVE);
    }

    @Test
    @DisplayName("a role that may raise a scrap does not strand it in PENDING_APPROVAL")
    void creatingAScrapImpliesSomeoneCanApproveIt() {
        Set<String> creators = rolesHolding(SCRAP_CREATE);
        assertThat(creators)
                .as("no role holds %s — the parse is broken", SCRAP_CREATE)
                .isNotEmpty();
        assertThat(rolesHolding(SCRAP_APPROVE).stream()
                        .filter(role -> !role.equals("ADMIN"))
                        .toList())
                .as("a scrap can be raised but only ADMIN can ever post it (#2472)")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the clerk who raises a scrap may not approve their own write-off")
    void theCreatingRoleDoesNotApprove() {
        // INVENTORY_LEAD is the parts-clerk persona (gloria.mendez) that raises the scrap in the
        // accelerated suite. Granting it approval would collapse the separation the flow is built on.
        assertThat(grants.get("INVENTORY_LEAD")).contains(SCRAP_CREATE).doesNotContain(SCRAP_APPROVE);
    }

    @Test
    @DisplayName("scrap approval stays with inventory and location management")
    void approvalIsNotHandedToTheFloor() {
        assertThat(rolesHolding(SCRAP_APPROVE))
                .as("posting a write-off to the ledger is a manager and controller authority")
                .isSubsetOf(Set.of("ADMIN", "INVENTORY_CONTROLLER", "INVENTORY_MANAGER", "LOCATION_MANAGER"));
        assertThat(grants.get("TECHNICIAN")).doesNotContain(SCRAP_APPROVE);
        assertThat(grants.get("SERVICE_ADVISOR")).doesNotContain(SCRAP_APPROVE);
    }

    @Test
    @DisplayName("the negative-stock override is not carried along with approval")
    void approvalDoesNotImplyTheNegativeStockOverride() {
        // approveScrap honours negativeStockOverride only for an inventory:adjustment:override holder
        // (ScrapServiceImpl.NEGATIVE_STOCK_OVERRIDE_PERMISSION). #2472 needed the posting, not the
        // policy waiver.
        assertThat(grants.get("LOCATION_MANAGER")).doesNotContain(ADJUSTMENT_OVERRIDE);
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
