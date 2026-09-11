package com.positivity.securityservice.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #1613, P4: the reconciliation guard that was missing.
 *
 * <p>The defect this issue fixes was silent. Seven roles existed in this service, carried real
 * permission grants, and resolved to the generic {@code ROLE_USER} persona in pos-mcp-server — and
 * nothing failed, because nothing tied the two together. A role added to SQL without a corresponding
 * Java edit simply disappeared from the assistant's view.
 *
 * <p>This asserts every role the seed migrations create is accounted for: either it is given persona
 * metadata, or it is explicitly marked ineligible for persona resolution. Silence is no longer an
 * option — adding a role to SQL now forces a decision about what the assistant does with it, and
 * that decision is what this test checks was made.
 *
 * <p>It reads the seed scripts and the baseline file rather than a live schema deliberately: the
 * guard has to run on every build, not only where a database is available, because the failure it
 * prevents is one that costs nothing to introduce.
 */
@DisplayName("Role persona reconciliation (#1613 P4)")
class RolePersonaReconciliationTest {

    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    /** The bulk-load baseline — canonical for every role outside the bootstrap floor (#1613 D8). */
    private static final Path BASELINE_ROLES =
            Path.of("..", "scripts", "fixtures", "seed", "alpha", "security", "roles.csv");

    private static final Pattern ROLE_DELETE = Pattern.compile(
            "DELETE\\s+FROM\\s+roles\\s+WHERE\\s+name\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED_NAME = Pattern.compile("'([A-Z_]+)'");

    /**
     * Since the flattened history (2026-09-09) the persona decisions live on the seed rows
     * themselves — {@code persona_title} set, or {@code mcp_persona_eligible = false} — rather than
     * in the retired V35 backfill; {@link RoleSeedSql} reads them by column.
     */
    @Test
    @DisplayName("every provisioned role either gets a persona or is explicitly excluded from resolution")
    void everySeededRoleIsAccountedFor() throws IOException {
        // Both provisioning paths, not just SQL. This PR made the baseline file the canonical source
        // for every role outside the bootstrap floor, so a guard that reads only db/migration would
        // police six roles and wave the other eleven through — including any role added later.
        Set<String> provisioned = new TreeSet<>(seededRoleNames());
        provisioned.addAll(baselineRoleNames());

        // Vacuity guard: every assertion below is trivially true on an empty set, and these sets come
        // out of parsing files whose formatting can change. Assert the extraction found something
        // before trusting what it says.
        assertThat(provisioned)
                .as("no roles parsed out of the migrations or the baseline file")
                .isNotEmpty();

        Set<String> accountedFor = new TreeSet<>(personaBackfilledRoles());
        accountedFor.addAll(ineligibleRoles());
        accountedFor.addAll(baselinePersonaRoles());

        // The failure message names the roles, because "a role is missing a persona" is only
        // actionable if you know which one.
        assertThat(provisioned)
                .as("roles with neither persona metadata nor an explicit ineligible flag")
                .allSatisfy(role -> assertThat(accountedFor).contains(role));
    }

    @Test
    @DisplayName("the roles excluded from resolution are exactly the ones decided on in this issue")
    void exclusionsAreTheOnesDecided() throws IOException {
        // Decision 2: CUSTOMER and SELF_SERVICE_CUSTOMER have no MCP access in the near term. Pinning
        // the set means a future exclusion is a deliberate edit here rather than a quiet addition —
        // an excluded role's users silently land on the generic persona, which is exactly the outcome
        // this issue set out to stop happening by accident. SUPPORT (ADR-0062 §7, WS2b-4) joins them:
        // it is the read-only role an operator impersonation token carries, holds no assistant
        // entrypoint, and is assigned to no user.
        assertThat(ineligibleRoles()).containsExactlyInAnyOrder("CUSTOMER", "SELF_SERVICE_CUSTOMER", "SUPPORT");
    }

    @Test
    @DisplayName("no role is both given a persona and excluded from using it")
    void noRoleIsBothPersonaAndExcluded() throws IOException {
        Set<String> both = new TreeSet<>(personaBackfilledRoles());
        both.retainAll(ineligibleRoles());

        assertThat(both).isEmpty();
    }

    @Test
    @DisplayName("the seven roles that had no persona before this issue now have one")
    void thePreviouslyMissingRolesAreCovered() throws IOException {
        // These are the roles the issue opened on: real permission grants, no persona, every one of
        // their users resolving to the generic fallback.
        assertThat(personaBackfilledRoles())
                .contains(
                        "CONTROLLER",
                        "GENERAL_MANAGER",
                        "INVENTORY_CONTROLLER",
                        "INVENTORY_LEAD",
                        "INVENTORY_MANAGER",
                        "MANAGER",
                        "SHOP_MANAGER");
    }

    /** Role names listed in the bulk-load baseline file. */
    private static Set<String> baselineRoleNames() throws IOException {
        return baselineRows().stream()
                .map(row -> row[0])
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Baseline roles that carry a persona decision: either a curated slot, or an explicit
     * {@code mcpPersonaEligible=false}.
     */
    private static Set<String> baselinePersonaRoles() throws IOException {
        Set<String> decided = new LinkedHashSet<>();
        for (String[] row : baselineRows()) {
            boolean hasSlot = row.length > 4 && !(row[2].isBlank() && row[3].isBlank() && row[4].isBlank());
            boolean explicitlyIneligible = row.length > 6 && "false".equalsIgnoreCase(row[6].trim());
            if (hasSlot || explicitlyIneligible) {
                decided.add(row[0]);
            }
        }
        return decided;
    }

    /** Minimal CSV split: these fixtures quote only embedded commas. */
    private static List<String[]> baselineRows() throws IOException {
        List<String> lines = Files.readAllLines(BASELINE_ROLES, StandardCharsets.UTF_8);
        List<String[]> rows = new java.util.ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            List<String> values = new java.util.ArrayList<>();
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

    /** Roles given a persona: a {@code persona_title} on the seed row, or a curated slot in the baseline file. */
    private static Set<String> personaBackfilledRoles() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, String> row : RoleSeedSql.rows(MIGRATIONS)) {
            String title = row.get("persona_title");
            if (title != null && !title.isBlank()) {
                names.add(row.get("name"));
            }
        }
        for (String[] row : baselineRows()) {
            boolean hasSlot = row.length > 4 && !(row[2].isBlank() && row[3].isBlank() && row[4].isBlank());
            if (hasSlot) {
                names.add(row[0]);
            }
        }
        return names;
    }

    /** Roles explicitly excluded from persona resolution, on the seed row or in the baseline file. */
    private static Set<String> ineligibleRoles() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, String> row : RoleSeedSql.rows(MIGRATIONS)) {
            if ("false".equalsIgnoreCase(row.get("mcp_persona_eligible"))) {
                names.add(row.get("name"));
            }
        }
        for (String[] row : baselineRows()) {
            if (row.length > 6 && "false".equalsIgnoreCase(row[6].trim())) {
                names.add(row[0]);
            }
        }
        return names;
    }

    private static Set<String> seededRoleNames() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, String> row : RoleSeedSql.rows(MIGRATIONS)) {
            names.add(row.get("name"));
        }
        names.removeAll(droppedRoleNames());
        return names;
    }

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
}
