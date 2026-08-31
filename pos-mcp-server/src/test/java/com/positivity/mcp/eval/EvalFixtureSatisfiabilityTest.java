package com.positivity.mcp.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Issue #1612: an eval fixture must assert something the permission gate can actually produce.
 *
 * <p>{@link EvalFixtureValidationTest} checks that fixtures are structurally valid — fields
 * present, ids unique, counts met. Nothing checked whether a fixture was <em>satisfiable</em>, and
 * thirty of them were not: a positive asserting that {@code ROLE_SERVICE_ADVISOR} holding only
 * {@code AUTHENTICATED} should be offered {@code OrderFacadeTool}, when no group of that tool is
 * satisfied by that code. Those fixtures could never pass, so they capped {@code hit@5} at roughly
 * 0.55 by construction and the metric measured the fixture set rather than the retrieval.
 *
 * <p>Two of them were worse than unreachable: the {@code authenticated-baseline} pair asserted
 * that an authenticated-only caller is offered {@code WorkorderFacadeTool} and
 * {@code AdminFacadeTool}, which was true under V18's union of every method's codes and stopped
 * being true when V40 (#1606) replaced that union with per-method AND-groups.
 *
 * <p>This asserts the fixtures agree with the two files that decide the gate — the facade group
 * migrations and the bulk-load grant baseline — so the next grant or guard change that strands a
 * fixture fails a build instead of quietly lowering a score.
 */
@DisplayName("Eval fixtures are satisfiable against the real gate (#1612)")
class EvalFixtureSatisfiabilityTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path MIGRATIONS = ROOT.resolve("src/main/resources/db/migration");
    private static final Path FIXTURES = ROOT.resolve("src/test/resources/eval/tool-selection/generated.json");
    private static final Path GRANTS = ROOT.resolve("../scripts/fixtures/seed/alpha/security/role-permissions.csv");

    /** Every authenticated caller carries this synthetic code; no role_permissions row grants it. */
    private static final String AUTHENTICATED = "AUTHENTICATED";

    /** The generic fallback identity, not a seeded role — its grants cannot be looked up. */
    private static final String FALLBACK_ROLE = "USER";

    private static final Pattern GROUP_BLOCK = Pattern.compile(
            "FROM mcp_tool, \\(VALUES(.*?)\\) AS perms\\(grp, code\\)\\s*WHERE mcp_tool\\.name = '([A-Za-z]+)'",
            Pattern.DOTALL);
    private static final Pattern GROUP_PAIR = Pattern.compile("\\('([^']+)',\\s*'([^']+)'\\)");
    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("every positive fixture's actor can reach every tool it expects")
    void positivesAreSatisfiable() throws IOException {
        Map<String, Map<String, Set<String>>> groups = facadeGroups();
        List<String> unsatisfiable = new ArrayList<>();
        int checked = 0;

        for (JsonNode fixture : fixtures()) {
            if (isNegative(fixture)) {
                continue;
            }
            Set<String> held = declaredCodes(fixture);
            for (String tool : expectedTools(fixture)) {
                checked++;
                if (!reaches(groups, tool, held)) {
                    unsatisfiable.add(fixture.get("fixture_id").asString() + " -> " + tool);
                }
            }
        }

        // Vacuity guard: every assertion here is trivially true over an empty fixture set, and the
        // set comes out of a JSON shape that can change.
        assertThat(checked)
                .as("no positive expectations parsed out of the fixture file")
                .isPositive();
        assertThat(unsatisfiable)
                .as("positives asserting a tool the permission gate cannot offer their actor")
                .isEmpty();
    }

    @Test
    @DisplayName("every negative fixture's actor really is blocked from the tools it names")
    void negativesAreActuallyBlocked() throws IOException {
        Map<String, Map<String, Set<String>>> groups = facadeGroups();
        List<String> reachable = new ArrayList<>();

        for (JsonNode fixture : fixtures()) {
            if (!isNegative(fixture)) {
                continue;
            }
            Set<String> held = declaredCodes(fixture);
            for (String tool : expectedTools(fixture)) {
                if (reaches(groups, tool, held)) {
                    reachable.add(fixture.get("fixture_id").asString() + " -> " + tool);
                }
            }
        }

        assertThat(reachable)
                .as("negatives whose actor can in fact reach the tool — the fixture proves nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("no actor claims a permission its role does not hold")
    void actorsHoldWhatTheyClaim() throws IOException {
        Map<String, Set<String>> grants = roleGrants();
        List<String> invented = new ArrayList<>();

        for (JsonNode fixture : fixtures()) {
            String role = fixture.get("actor").get("role").asString().replaceFirst("^ROLE_", "");
            Set<String> real = grants.get(role);
            // A negative may model any caller, including one holding nothing at all, and the
            // fallback identity has no grant row to compare against.
            if (real == null || isNegative(fixture)) {
                continue;
            }
            for (String code : declaredCodes(fixture)) {
                if (!AUTHENTICATED.equals(code) && !real.contains(code)) {
                    invented.add(fixture.get("fixture_id").asString() + ": " + role + " does not hold " + code);
                }
            }
        }

        assertThat(invented)
                .as("actors built from permissions their role was never granted — a score measured "
                        + "on these describes a system that does not exist")
                .isEmpty();
    }

    @Test
    @DisplayName("the fallback identity reaches only the AUTHENTICATED-gated facade")
    void fallbackIdentityIsNotOfferedPrivilegedFacades() throws IOException {
        Map<String, Map<String, Set<String>>> groups = facadeGroups();
        Set<String> authenticatedOnly = Set.of(AUTHENTICATED);

        List<String> offered = groups.keySet().stream()
                .filter(tool -> reaches(groups, tool, authenticatedOnly))
                .sorted()
                .toList();

        // EventsFacadeTool is the only facade whose gate is the sentinel. This mirrors
        // FacadeToolPermissionSeedTest#assistantOnlyCallerQualifiesOnlyEventsFacade from the seed
        // side; asserting it here as well is what keeps a ROLE_USER fixture from re-acquiring the
        // pre-V40 expectation that any authenticated caller is offered a privileged facade.
        assertThat(offered)
                .as("facades offered to a caller holding only %s", AUTHENTICATED)
                .containsExactly("EventsFacadeTool");
    }

    private static boolean reaches(Map<String, Map<String, Set<String>>> groups, String tool, Set<String> held) {
        Map<String, Set<String>> toolGroups = groups.get(tool);
        if (toolGroups == null || toolGroups.isEmpty()) {
            return false;
        }
        return toolGroups.values().stream().anyMatch(held::containsAll);
    }

    private static boolean isNegative(JsonNode fixture) {
        for (JsonNode tag : fixture.get("tags")) {
            if ("negative".equals(tag.asString())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> declaredCodes(JsonNode fixture) {
        Set<String> codes = new LinkedHashSet<>();
        JsonNode declared = fixture.get("actor").get("permission_codes");
        if (declared != null) {
            declared.forEach(code -> codes.add(code.asString()));
        }
        // The runtime always adds it (SessionAgentManager), so a fixture that omits it is still
        // describing a caller who holds it.
        codes.add(AUTHENTICATED);
        return codes;
    }

    private static List<String> expectedTools(JsonNode fixture) {
        List<String> tools = new ArrayList<>();
        JsonNode expected = fixture.get("expected").get("tool_ids");
        if (expected != null) {
            expected.forEach(tool -> tools.add(tool.asString()));
        }
        return tools;
    }

    private static List<JsonNode> fixtures() throws IOException {
        JsonNode root = MAPPER.readTree(Files.readString(FIXTURES, StandardCharsets.UTF_8));
        List<JsonNode> all = new ArrayList<>();
        root.get("fixtures").forEach(all::add);
        assertThat(all).as("no fixtures parsed from %s", FIXTURES).isNotEmpty();
        return all;
    }

    /**
     * tool → group → codes, replaying the group-shaped migrations in version order. Each such
     * migration deletes a tool's rows before re-inserting, so a later re-derivation replaces an
     * earlier one. Same source and same reading as
     * {@code scripts/mcp-facade-reachability.py}.
     */
    private static Map<String, Map<String, Set<String>>> facadeGroups() throws IOException {
        Map<String, Map<String, Set<String>>> groups = new LinkedHashMap<>();
        List<Path> migrations;
        try (var files = Files.list(MIGRATIONS)) {
            migrations = files.filter(path ->
                            VERSION.matcher(path.getFileName().toString()).find())
                    .sorted(Comparator.comparingInt(EvalFixtureSatisfiabilityTest::version))
                    .toList();
        }
        for (Path migration : migrations) {
            Matcher block = GROUP_BLOCK.matcher(Files.readString(migration, StandardCharsets.UTF_8));
            while (block.find()) {
                Map<String, Set<String>> tool = new LinkedHashMap<>();
                groups.put(block.group(2), tool);
                Matcher pair = GROUP_PAIR.matcher(block.group(1));
                while (pair.find()) {
                    tool.computeIfAbsent(pair.group(1), key -> new LinkedHashSet<>())
                            .add(pair.group(2));
                }
            }
        }
        assertThat(groups)
                .as("no facade groups parsed — the regex, not the seed, is what broke")
                .isNotEmpty();
        return groups;
    }

    private static int version(Path migration) {
        Matcher matcher = VERSION.matcher(migration.getFileName().toString());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }

    /** role → codes, from the bulk-load baseline (canonical for grants since #1613 D8). */
    private static Map<String, Set<String>> roleGrants() throws IOException {
        Map<String, Set<String>> grants = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(GRANTS, StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split(",", 2);
            Set<String> codes = new LinkedHashSet<>();
            for (String code : parts[1].trim().replace("\"", "").split(";")) {
                if (!code.isBlank()) {
                    codes.add(code.trim());
                }
            }
            grants.put(parts[0], codes);
        }
        assertThat(grants).as("no role grants parsed from %s", GRANTS).isNotEmpty();
        assertThat(grants).as("the fallback identity is not a seeded role").doesNotContainKey(FALLBACK_ROLE);
        return grants;
    }
}
