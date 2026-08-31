package com.positivity.securityservice.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
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
 * <p>It reads the migrations rather than a live schema deliberately: the guard has to run on every
 * build, not only where a database is available, because the failure it prevents is one that costs
 * nothing to introduce.
 */
@DisplayName("Role persona reconciliation (#1613 P4)")
class RolePersonaReconciliationTest {

    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");
    private static final Path PERSONA_BACKFILL = MIGRATIONS.resolve("V35__backfill_role_persona_metadata.sql");

    private static final Pattern ROLE_INSERT =
            Pattern.compile("INSERT\\s+INTO\\s+roles\\b(.*?);", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ROLE_DELETE = Pattern.compile(
            "DELETE\\s+FROM\\s+roles\\s+WHERE\\s+name\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED_NAME = Pattern.compile("'([A-Z_]+)'");

    /** A persona-setting UPDATE: one role named per statement. */
    private static final Pattern PERSONA_UPDATE = Pattern.compile(
            "UPDATE\\s+roles\\s+SET\\s+persona_title.*?WHERE\\s+name\\s*=\\s*'([A-Z_]+)'",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** The eligibility UPDATE: several roles named in one IN list. */
    private static final Pattern INELIGIBLE_UPDATE = Pattern.compile(
            "UPDATE\\s+roles\\s+SET\\s+mcp_persona_eligible\\s*=\\s*FALSE\\s*WHERE\\s+name\\s+IN\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("every seeded role either gets a persona or is explicitly excluded from resolution")
    void everySeededRoleIsAccountedFor() throws IOException {
        Set<String> seeded = new TreeSet<>(seededRoleNames());

        Set<String> accountedFor = new TreeSet<>(personaBackfilledRoles());
        accountedFor.addAll(ineligibleRoles());

        // The failure message names the roles, because "a role is missing a persona" is only
        // actionable if you know which one.
        assertThat(seeded)
                .as("roles with neither persona metadata nor an explicit ineligible flag")
                .allSatisfy(role -> assertThat(accountedFor).contains(role));
    }

    @Test
    @DisplayName("the roles excluded from resolution are exactly the ones decided on in this issue")
    void exclusionsAreTheOnesDecided() throws IOException {
        // Decision 2: CUSTOMER and SELF_SERVICE_CUSTOMER have no MCP access in the near term. Pinning
        // the set means a future exclusion is a deliberate edit here rather than a quiet addition —
        // an excluded role's users silently land on the generic persona, which is exactly the outcome
        // this issue set out to stop happening by accident.
        assertThat(ineligibleRoles()).containsExactlyInAnyOrder("CUSTOMER", "SELF_SERVICE_CUSTOMER");
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

    private static Set<String> personaBackfilledRoles() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PERSONA_UPDATE.matcher(Files.readString(PERSONA_BACKFILL, StandardCharsets.UTF_8));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static Set<String> ineligibleRoles() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        Matcher statement = INELIGIBLE_UPDATE.matcher(Files.readString(PERSONA_BACKFILL, StandardCharsets.UTF_8));
        while (statement.find()) {
            Matcher name = QUOTED_NAME.matcher(statement.group(1));
            while (name.find()) {
                names.add(name.group(1));
            }
        }
        return names;
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
