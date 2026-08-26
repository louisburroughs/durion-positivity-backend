package com.positivity.mcp.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Facade permission seed guard (#1115, #1519 Wave 4).
 *
 * <p>The facade grants are Postgres-seed data with no offline Flyway path (the H2 chain has no
 * tool-registry tables), so this test asserts on the net effect of the migration SQL directly:
 * {@code V18} (initial seed) → {@code V29} (AUTHENTICATED removals) → {@code V35} (retarget) →
 * {@code V36} (ADR-0057 availability code) → {@code V37} (full re-derivation from the real
 * downstream endpoints after the #1519 Wave 2/3 retargeting; per-tool delete-and-reinsert).
 *
 * <p>{@link #EXPECTED} is the Wave 4 derivation table — per tool, the union of the merged
 * class+method {@code @PreAuthorize} permission codes across every downstream endpoint the tool's
 * {@code @Tool} methods (and composition legs, per {@code facade-contract.yaml}) call. It is
 * declared once here so the V37 migration comment and this test cannot drift apart silently: any
 * edit to the seeded SQL must be mirrored in this table and vice versa.
 *
 * <p>It also keeps the #1115 regression guard: no facade may carry the {@code AUTHENTICATED}
 * pseudo-permission alongside a privileged code — every authenticated caller holds
 * {@code AUTHENTICATED}, so such a facade would pass the selection-layer permission gate for
 * everyone (mcp_tool_permission is OR-semantics).
 */
class FacadeToolPermissionSeedTest {

    private static final Path MIGRATIONS = Paths.get(System.getProperty("user.dir"), "src/main/resources/db/migration");
    private static final String AUTHENTICATED = "AUTHENTICATED";
    private static final Set<String> ASSISTANT_ENTRYPOINTS =
            Set.of("mcp:chat:execute", "mcp:chat:stream", "nlti:request:submit", "nlti:request:read", AUTHENTICATED);

    // One INSERT block: VALUES ('code'), ('code2') ... WHERE mcp_tool.name = 'ToolName';
    private static final Pattern TOOL_NAME = Pattern.compile("mcp_tool\\.name\\s*=\\s*'([^']+)'");
    private static final Pattern QUOTED_CODE = Pattern.compile("\\('([^']+)'\\)");
    // V37 per-tool full delete: DELETE FROM mcp_tool_permission WHERE tool_id IN
    //   (SELECT id FROM mcp_tool WHERE name = 'ToolName');
    private static final Pattern FULL_DELETE = Pattern.compile(
            "DELETE\\s+FROM\\s+mcp_tool_permission\\s+WHERE\\s+tool_id\\s+IN\\s*"
                    + "\\(\\s*SELECT\\s+id\\s+FROM\\s+mcp_tool\\s+WHERE\\s+name\\s*=\\s*'([^']+)'\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    /**
     * #1519 Wave 4 derivation table: tool → union of downstream {@code @PreAuthorize} permission
     * codes (role-only fragments dropped; {@code AUTHENTICATED} only where the tool class has zero
     * permission-coded guards). Endpoint-by-endpoint citations live in the V37 migration header.
     */
    private static final Map<String, Set<String>> EXPECTED = Map.ofEntries(
            Map.entry("AccountingFacadeTool", Set.of("accounting:coa:view", "reporting:view:financial-statements")),
            Map.entry("ReportingFacadeTool", Set.of("reporting:view:financial-statements", "inventory:on_hand:view")),
            Map.entry("CatalogFacadeTool", Set.of("catalog:product:view")),
            Map.entry(
                    "CustomerFacadeTool",
                    Set.of("crm:party:view", "crm:interaction:view", "invoice:manage", "workorder:workorder:view")),
            Map.entry("EventsFacadeTool", Set.of(AUTHENTICATED)),
            Map.entry("HrFacadeTool", Set.of("people:employee:view", "people:availability:view")),
            Map.entry("InventoryFacadeTool", Set.of("inventory:availability:read", "inventory:on_hand:view")),
            Map.entry("InvoiceFacadeTool", Set.of("invoice:manage")),
            Map.entry("LocationFacadeTool", Set.of("location:read", "inventory:on_hand:view")),
            Map.entry("OrderFacadeTool", Set.of("order:order:view")),
            Map.entry(
                    "PricingFacadeTool",
                    Set.of(
                            "catalog:product:view",
                            "catalog:location_price_override:read",
                            "catalog:price_book:read",
                            "pricing:promotion:view",
                            "pricing:rule:view")),
            Map.entry("ShopManagerFacadeTool", Set.of("location:read", "shop:schedule:view", "workorder:wip:view")),
            Map.entry("TaxFacadeTool", Set.of("tax:calculate", "location:read", "reporting:view:financial-statements")),
            Map.entry(
                    "VehicleFacadeTool",
                    Set.of("vehicle-inventory:registry:view", "vehicle-inventory:search:view", "crm:vehicle:view")),
            Map.entry("WorkorderFacadeTool", Set.of("workorder:workorder:view")),
            Map.entry(
                    "AdminFacadeTool",
                    Set.of("security:user:view", "security:permission:view", "security:audit:view")));

    @Test
    @DisplayName("net facade seed (V18..V37) equals the #1519 Wave 4 derivation table")
    void netSeedMatchesDerivationTable() throws IOException {
        Map<String, Set<String>> grants = netGrants();

        assertThat(grants.keySet())
                .as("tools with permission rows after V37")
                .containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());
        EXPECTED.forEach((tool, codes) -> assertThat(grants.get(tool))
                .as("%s seed must equal the Wave 4 derivation (see V37 header)", tool)
                .containsExactlyInAnyOrderElementsOf(codes));
    }

    @Test
    @DisplayName("V37 re-derives every facade it clears (delete always followed by a reinsert)")
    void everyClearedToolIsReseeded() throws IOException {
        String v37 = read("V37__facade_permission_rederivation.sql");
        Set<String> cleared = parseFullDeletes(v37);
        Map<String, Set<String>> reinserted = parseSeed(v37);

        assertThat(cleared).isNotEmpty();
        assertThat(reinserted.keySet())
                .as("each per-tool DELETE in V37 must be paired with an INSERT of the derived codes")
                .containsExactlyInAnyOrderElementsOf(cleared);
        reinserted.values().forEach(codes -> assertThat(codes).isNotEmpty());
    }

    @Test
    @DisplayName("no facade grants AUTHENTICATED alongside a privileged permission (#1115)")
    void noFacadeMixesAuthenticatedWithPrivilege() throws IOException {
        Map<String, Set<String>> grants = netGrants();

        assertThat(grants).isNotEmpty();
        grants.forEach((tool, codes) -> {
            if (codes.contains(AUTHENTICATED)) {
                assertThat(codes)
                        .as(
                                "%s is granted AUTHENTICATED alongside privileged codes %s; gate it on the "
                                        + "privileged code(s) instead (see V29/V37 / issue #1115)",
                                tool, codes)
                        .containsExactly(AUTHENTICATED);
            }
        });
    }

    @Test
    @DisplayName("assistant entrypoints alone qualify only the deliberately open Events facade")
    void assistantOnlyCallerQualifiesOnlyEventsFacade() throws IOException {
        netGrants()
                .forEach((tool, codes) -> assertThat(intersectsAssistantBaseline(codes))
                        .as(
                                "%s must not be reachable on the assistant-entrypoint baseline alone "
                                        + "(only EventsFacadeTool is AUTHENTICATED-gated by design)",
                                tool)
                        .isEqualTo("EventsFacadeTool".equals(tool)));
    }

    // ── parsing ───────────────────────────────────────────────────────────────

    private static Map<String, Set<String>> parseSeed(String sql) {
        Map<String, Set<String>> grants = new LinkedHashMap<>();
        for (String block : sql.split("(?i)INSERT\\s+INTO\\s+mcp_tool_permission")) {
            Matcher nameMatcher = TOOL_NAME.matcher(block);
            if (!nameMatcher.find()) {
                continue;
            }
            String tool = nameMatcher.group(1);
            Set<String> codes = grants.computeIfAbsent(tool, t -> new LinkedHashSet<>());
            Matcher codeMatcher = QUOTED_CODE.matcher(block);
            while (codeMatcher.find()) {
                codes.add(codeMatcher.group(1));
            }
        }
        return grants;
    }

    /**
     * Apply an AUTHENTICATED-removal DELETE (V29/V35 shape): strip AUTHENTICATED from every tool
     * named in the migration's {@code name IN (...)} list. Parsed generically so the guard tracks
     * the migration rather than a hardcoded tool set.
     */
    private static void applyAuthenticatedDeletes(Map<String, Set<String>> grants, String sql) {
        if (!sql.toUpperCase(java.util.Locale.ROOT).contains("'" + AUTHENTICATED + "'")) {
            return;
        }
        Matcher inList = Pattern.compile("name\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE)
                .matcher(sql);
        if (!inList.find()) {
            return;
        }
        Matcher names = Pattern.compile("'([^']+)'").matcher(inList.group(1));
        while (names.find()) {
            Set<String> codes = grants.get(names.group(1));
            if (codes != null) {
                codes.remove(AUTHENTICATED);
            }
        }
    }

    /** Tool names cleared by V37's per-tool full deletes. */
    private static Set<String> parseFullDeletes(String sql) {
        Set<String> tools = new LinkedHashSet<>();
        Matcher deletes = FULL_DELETE.matcher(sql);
        while (deletes.find()) {
            tools.add(deletes.group(1));
        }
        return tools;
    }

    private static Map<String, Set<String>> netGrants() throws IOException {
        Map<String, Set<String>> grants = parseSeed(read("V18__seed_facade_tool_permissions.sql"));
        applyAuthenticatedDeletes(grants, read("V29__fix_facade_authenticated_gating.sql"));
        String v35 = read("V35__retarget_facade_authenticated_gating.sql");
        mergeSeed(grants, v35);
        applyAuthenticatedDeletes(grants, v35);
        mergeSeed(grants, read("V36__inventory_facade_availability_permission.sql"));
        String v37 = read("V37__facade_permission_rederivation.sql");
        parseFullDeletes(v37).forEach(grants::remove);
        mergeSeed(grants, v37);
        return grants;
    }

    private static void mergeSeed(Map<String, Set<String>> grants, String sql) {
        parseSeed(sql)
                .forEach((tool, codes) -> grants.computeIfAbsent(tool, ignored -> new LinkedHashSet<>())
                        .addAll(codes));
    }

    private static boolean intersectsAssistantBaseline(Set<String> codes) {
        return codes != null && codes.stream().anyMatch(ASSISTANT_ENTRYPOINTS::contains);
    }

    private static String read(String migration) throws IOException {
        // Strip -- line comments so the executable statements are parsed, not the SQL comments that
        // quote @PreAuthorize expressions like hasAuthority('...') / hasRole('ADMIN').
        return Files.readString(MIGRATIONS.resolve(migration)).replaceAll("(?m)--.*$", "");
    }
}
