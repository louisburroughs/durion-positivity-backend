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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Facade permission seed guard (#1115, #1519 Wave 4, #1606 finding 1).
 *
 * <p>The facade grants are Postgres-seed data with no offline Flyway path (the H2 chain has no
 * tool-registry tables), so this test asserts on the net effect of the migration SQL directly:
 * {@code V18} (initial seed) → {@code V29} (AUTHENTICATED removals) → {@code V35} (retarget) →
 * {@code V36} (ADR-0057 availability code) → {@code V37} (full re-derivation from the real
 * downstream endpoints after the #1519 Wave 2/3 retargeting; per-tool delete-and-reinsert) →
 * {@code V38} (adds {@code tax:rates:view} to TaxFacadeTool for the restored getTaxRate, #1522) →
 * {@code V39} (re-derives AccountingFacadeTool for the W1.2 aging facades; permission-net-neutral)
 * → {@code V40} (per-method AND-groups, #1606 finding 1).
 *
 * <p><b>V40 changed the unit of the assertion.</b> Rows now carry a {@code permission_group} and a
 * tool is offered iff the caller holds ALL codes of AT LEAST ONE group, so a flat union no longer
 * describes the gate. {@link #EXPECTED_GROUPS} is therefore the source of truth here: per tool, one
 * group per {@code @Tool} method holding the codes that method's <em>required</em> downstream calls
 * need. It is declared once so the V40 migration comment and this test cannot drift apart silently:
 * any edit to the seeded SQL must be mirrored in this table and vice versa.
 *
 * <p>The replay models the migration chain exactly: everything through V39 is flat, V40's backfill
 * turns each of those rows into its own singleton group ({@code permission_group = permission_code},
 * behaviour-identical to the old OR gate), and V40's per-tool delete-and-reinsert then replaces the
 * 16 facades with real method groups.
 *
 * <p>It also keeps the #1115 regression guard: no facade may carry the {@code AUTHENTICATED}
 * pseudo-permission alongside a privileged code — every authenticated caller holds
 * {@code AUTHENTICATED}, so such a facade would pass the selection-layer permission gate for
 * everyone (an {@code AUTHENTICATED} group is a satisfied group for every caller).
 */
class FacadeToolPermissionSeedTest {

    private static final Path MIGRATIONS = Paths.get(System.getProperty("user.dir"), "src/main/resources/db/migration");
    private static final String AUTHENTICATED = "AUTHENTICATED";
    private static final String REPORTING = "reporting:view:financial-statements";
    private static final String CRM_PARTY_VIEW = "crm:party:view";
    private static final String LOCATION_READ = "location:read";
    private static final String INVOICE_MANAGE = "invoice:manage";
    private static final String ON_HAND_VIEW = "inventory:on_hand:view";
    private static final String CATALOG_PRODUCT_VIEW = "catalog:product:view";
    private static final String WORKORDER_VIEW = "workorder:workorder:view";
    private static final String PEOPLE_EMPLOYEE_VIEW = "people:employee:view";
    private static final String AVAILABILITY_READ = "inventory:availability:read";
    private static final String SECURITY_PERMISSION_VIEW = "security:permission:view";
    private static final Set<String> ASSISTANT_ENTRYPOINTS =
            Set.of("mcp:chat:execute", "mcp:chat:stream", "nlti:request:submit", "nlti:request:read", AUTHENTICATED);

    // One pre-V40 INSERT block: VALUES ('code'), ('code2') ... WHERE mcp_tool.name = 'ToolName';
    private static final Pattern TOOL_NAME = Pattern.compile("mcp_tool\\.name\\s*=\\s*'([^']+)'");
    private static final Pattern QUOTED_CODE = Pattern.compile("\\('([^']+)'\\)");
    // One V40 INSERT block: VALUES ('group', 'code'), ('group2', 'code2') ...
    private static final Pattern QUOTED_GROUP_CODE = Pattern.compile("\\('([^']+)'\\s*,\\s*'([^']+)'\\)");
    // V37/V38/V39/V40 per-tool full delete: DELETE FROM mcp_tool_permission WHERE tool_id IN
    //   (SELECT id FROM mcp_tool WHERE name = 'ToolName');
    private static final Pattern FULL_DELETE = Pattern.compile(
            "DELETE\\s+FROM\\s+mcp_tool_permission\\s+WHERE\\s+tool_id\\s+IN\\s*"
                    + "\\(\\s*SELECT\\s+id\\s+FROM\\s+mcp_tool\\s+WHERE\\s+name\\s*=\\s*'([^']+)'\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    /**
     * #1606 finding-1 group table: tool → {@code @Tool} method → the permission codes that method's
     * <em>required</em> downstream calls need. A composition contributes only its {@code .require()}d
     * legs (optional legs degrade individually, so they impose no precondition); a method that
     * requires no codes contributes no group at all. Endpoint-by-endpoint citations live in the V40
     * migration header, which folds in V37's per-method derivation plus V38 and V39.
     */
    private static final Map<String, Map<String, Set<String>>> EXPECTED_GROUPS = Map.ofEntries(
            Map.entry(
                    "AccountingFacadeTool",
                    Map.of(
                            "getAccountBalance", Set.of("accounting:coa:view"),
                            "getGeneralLedger", Set.of(REPORTING),
                            "getFinancialSummary", Set.of(REPORTING),
                            "getAgedReceivables", Set.of(REPORTING),
                            "getAgedPayables", Set.of(REPORTING))),
            Map.entry(
                    "ReportingFacadeTool",
                    Map.of(
                            "getSalesReport", Set.of(REPORTING),
                            "getInventoryReport", Set.of(ON_HAND_VIEW),
                            "getRevenueReport", Set.of(REPORTING))),
            Map.entry(
                    "CatalogFacadeTool",
                    Map.of(
                            "getProduct", Set.of(CATALOG_PRODUCT_VIEW),
                            "searchCatalog", Set.of(CATALOG_PRODUCT_VIEW),
                            "getCatalogByCategory", Set.of(CATALOG_PRODUCT_VIEW))),
            // getCustomerHistory .require()s no leg, so it contributes NO group — the whole point of
            // #1606 finding 1: its optional workorder leg used to admit the tool on its own.
            Map.entry(
                    "CustomerFacadeTool",
                    Map.of(
                            "getCustomer", Set.of(CRM_PARTY_VIEW),
                            "searchCustomers", Set.of(CRM_PARTY_VIEW))),
            Map.entry("EventsFacadeTool", Map.of(AUTHENTICATED, Set.of(AUTHENTICATED))),
            Map.entry(
                    "HrFacadeTool",
                    Map.of(
                            "getEmployee", Set.of(PEOPLE_EMPLOYEE_VIEW),
                            "getEmployeeSchedule", Set.of("people:availability:view"),
                            "searchEmployees", Set.of(PEOPLE_EMPLOYEE_VIEW))),
            Map.entry(
                    "InventoryFacadeTool",
                    Map.of(
                            "checkStock", Set.of(AVAILABILITY_READ),
                            "searchInventory", Set.of(AVAILABILITY_READ),
                            "getLocationStock", Set.of(ON_HAND_VIEW))),
            Map.entry(
                    "InvoiceFacadeTool",
                    Map.of(
                            "getInvoice", Set.of(INVOICE_MANAGE),
                            "searchInvoices", Set.of(INVOICE_MANAGE),
                            "getInvoicesByCustomer", Set.of(INVOICE_MANAGE))),
            Map.entry(
                    "LocationFacadeTool",
                    Map.of(
                            "getLocation", Set.of(LOCATION_READ),
                            "searchLocations", Set.of(LOCATION_READ),
                            "getLocationInventory", Set.of(ON_HAND_VIEW))),
            Map.entry(
                    "OrderFacadeTool",
                    Map.of(
                            "getOrder", Set.of("order:order:view"),
                            "listOrders", Set.of("order:order:view"))),
            // catalog:location_price_override:read leaves the gate: the effectivePrice leg is optional
            // and only issued when a locationId argument is supplied.
            Map.entry(
                    "PricingFacadeTool",
                    Map.of(
                            "getPriceForSku", Set.of(CATALOG_PRODUCT_VIEW),
                            "getPromotionByCode", Set.of("pricing:promotion:view"),
                            "listPriceRestrictions", Set.of("pricing:rule:view"),
                            "getPriceList", Set.of("catalog:price_book:read"))),
            // shop:schedule:view leaves the gate: the schedule leg is optional in both compositions.
            Map.entry(
                    "ShopManagerFacadeTool",
                    Map.of(
                            "getShopStatus", Set.of(LOCATION_READ),
                            "getShopQueue", Set.of("workorder:wip:view"),
                            "searchShops", Set.of(LOCATION_READ))),
            Map.entry(
                    "TaxFacadeTool",
                    Map.of(
                            "calculateTax", Set.of(LOCATION_READ, "tax:calculate"),
                            "getTaxRate", Set.of(LOCATION_READ, "tax:rates:view"),
                            "getTaxSummary", Set.of(REPORTING))),
            Map.entry(
                    "VehicleFacadeTool",
                    Map.of(
                            "getVehicle", Set.of("vehicle-inventory:registry:view"),
                            "searchVehicles", Set.of("vehicle-inventory:search:view"),
                            "getVehiclesByCustomer", Set.of("crm:vehicle:view"))),
            Map.entry(
                    "WorkorderFacadeTool",
                    Map.of(
                            "getWorkorder", Set.of(WORKORDER_VIEW),
                            "searchWorkorders", Set.of(WORKORDER_VIEW),
                            "getWorkorderStatus", Set.of(WORKORDER_VIEW))),
            // getSystemStatus makes no HTTP call and carries no guard, so it contributes no group.
            Map.entry(
                    "AdminFacadeTool",
                    Map.of(
                            "listUsers", Set.of("security:user:view"),
                            "getUserPermissions", Set.of(SECURITY_PERMISSION_VIEW),
                            "getMyPermissions", Set.of(SECURITY_PERMISSION_VIEW),
                            "getAuditLog", Set.of("security:audit:view"))));

    @Test
    @DisplayName("net facade seed (V18..V40) equals the #1606 per-method group table")
    void netSeedMatchesGroupTable() throws IOException {
        Map<String, Map<String, Set<String>>> groups = netGroupGrants();

        assertThat(groups.keySet())
                .as("tools with permission rows after V40")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_GROUPS.keySet());
        EXPECTED_GROUPS.forEach((tool, expected) -> {
            assertThat(groups.get(tool).keySet())
                    .as("%s permission groups (one per @Tool method with required codes)", tool)
                    .containsExactlyInAnyOrderElementsOf(expected.keySet());
            expected.forEach((group, codes) -> assertThat(groups.get(tool).get(group))
                    .as("%s group '%s' must equal the V40 derivation (see V40 header)", tool, group)
                    .containsExactlyInAnyOrderElementsOf(codes));
        });
    }

    @Test
    @DisplayName("no permission group is empty (an empty group would admit every caller)")
    void noGroupIsEmpty() throws IOException {
        netGroupGrants()
                .forEach((tool, groups) -> groups.forEach((group, codes) -> assertThat(codes)
                        .as("%s group '%s' is empty; bool_and over no rows is vacuously true", tool, group)
                        .isNotEmpty()));
    }

    @Test
    @DisplayName("V40 re-derives every facade it clears (delete always followed by a reinsert)")
    void everyClearedToolIsReseeded() throws IOException {
        String v40 = read("V40__mcp_tool_permission_groups.sql");
        Set<String> cleared = parseFullDeletes(v40);
        Map<String, Map<String, Set<String>>> reinserted = parseGroupSeed(v40);

        assertThat(cleared).isNotEmpty();
        assertThat(reinserted.keySet())
                .as("each per-tool DELETE in V40 must be paired with an INSERT of the derived groups")
                .containsExactlyInAnyOrderElementsOf(cleared);
        reinserted.values().forEach(groups -> assertThat(groups).isNotEmpty());
    }

    @Test
    @DisplayName("V37 re-derives every facade it clears (delete always followed by a reinsert)")
    void everyClearedToolIsReseededInV37() throws IOException {
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
                                        + "privileged code(s) instead (see V29/V37/V40 / issue #1115)",
                                tool, codes)
                        .containsExactly(AUTHENTICATED);
            }
        });
    }

    @Test
    @DisplayName("assistant entrypoints alone qualify only the deliberately open Events facade")
    void assistantOnlyCallerQualifiesOnlyEventsFacade() throws IOException {
        netGroupGrants()
                .forEach((tool, groups) -> assertThat(qualifies(groups, ASSISTANT_ENTRYPOINTS))
                        .as(
                                "%s must not be reachable on the assistant-entrypoint baseline alone "
                                        + "(only EventsFacadeTool is AUTHENTICATED-gated by design)",
                                tool)
                        .isEqualTo("EventsFacadeTool".equals(tool)));
    }

    @Test
    @DisplayName("#1606: an optional composition leg's code alone never admits a facade")
    void optionalLegCodeAloneAdmitsNoFacade() throws IOException {
        Map<String, Map<String, Set<String>>> groups = netGroupGrants();

        // The live eval fixtures ts-customerfacadetool-neg-role-technician / -dispatcher: a caller
        // holding only workorder:workorder:view (from getCustomerHistory's optional workorder leg)
        // must not reach CustomerFacadeTool, while crm:party:view must.
        assertThat(qualifies(groups.get("CustomerFacadeTool"), Set.of(WORKORDER_VIEW)))
                .isFalse();
        assertThat(qualifies(groups.get("CustomerFacadeTool"), Set.of(CRM_PARTY_VIEW)))
                .isTrue();
        // The other two codes V37's union pulled in through the same composition.
        assertThat(qualifies(groups.get("CustomerFacadeTool"), Set.of("crm:interaction:view", INVOICE_MANAGE)))
                .isFalse();
        // PricingFacadeTool's optional effectivePrice leg, and ShopManagerFacadeTool's optional
        // schedule leg, likewise no longer admit their tools.
        assertThat(qualifies(groups.get("PricingFacadeTool"), Set.of("catalog:location_price_override:read")))
                .isFalse();
        assertThat(qualifies(groups.get("ShopManagerFacadeTool"), Set.of("shop:schedule:view")))
                .isFalse();
    }

    @Test
    @DisplayName("#1606: a multi-code group needs every one of its codes")
    void multiCodeGroupNeedsAllCodes() throws IOException {
        Map<String, Set<String>> tax = netGroupGrants().get("TaxFacadeTool");

        assertThat(qualifies(tax, Set.of(LOCATION_READ))).isFalse();
        assertThat(qualifies(tax, Set.of("tax:calculate"))).isFalse();
        // One code from calculateTax and one from getTaxRate completes neither group.
        assertThat(qualifies(tax, Set.of("tax:calculate", "tax:rates:view"))).isFalse();
        assertThat(qualifies(tax, Set.of(LOCATION_READ, "tax:calculate"))).isTrue();
    }

    // ── the gate, modelled exactly as ToolMetadataRepositoryImpl's SQL does it ─

    /** True iff the caller holds every code of at least one group. Never true for an empty map. */
    private static boolean qualifies(Map<String, Set<String>> groups, Set<String> held) {
        return groups != null && groups.values().stream().anyMatch(held::containsAll);
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

    /** V40 shape: {@code VALUES ('group', 'code'), ...} → tool → group → codes. */
    private static Map<String, Map<String, Set<String>>> parseGroupSeed(String sql) {
        Map<String, Map<String, Set<String>>> grants = new LinkedHashMap<>();
        for (String block : sql.split("(?i)INSERT\\s+INTO\\s+mcp_tool_permission")) {
            Matcher nameMatcher = TOOL_NAME.matcher(block);
            if (!nameMatcher.find()) {
                continue;
            }
            Map<String, Set<String>> groups = grants.computeIfAbsent(nameMatcher.group(1), t -> new LinkedHashMap<>());
            Matcher pairMatcher = QUOTED_GROUP_CODE.matcher(block);
            while (pairMatcher.find()) {
                groups.computeIfAbsent(pairMatcher.group(1), g -> new LinkedHashSet<>())
                        .add(pairMatcher.group(2));
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

    /** Tool names cleared by a migration's per-tool full deletes. */
    private static Set<String> parseFullDeletes(String sql) {
        Set<String> tools = new LinkedHashSet<>();
        Matcher deletes = FULL_DELETE.matcher(sql);
        while (deletes.find()) {
            tools.add(deletes.group(1));
        }
        return tools;
    }

    /** The flat (pre-V40) row set after V18 → V39. */
    private static Map<String, Set<String>> flatGrantsThroughV39() throws IOException {
        Map<String, Set<String>> grants = parseSeed(read("V18__seed_facade_tool_permissions.sql"));
        applyAuthenticatedDeletes(grants, read("V29__fix_facade_authenticated_gating.sql"));
        String v35 = read("V35__retarget_facade_authenticated_gating.sql");
        mergeSeed(grants, v35);
        applyAuthenticatedDeletes(grants, v35);
        mergeSeed(grants, read("V36__inventory_facade_availability_permission.sql"));
        String v37 = read("V37__facade_permission_rederivation.sql");
        parseFullDeletes(v37).forEach(grants::remove);
        mergeSeed(grants, v37);
        String v38 = read("V38__facade_rate_lookup_permission.sql");
        parseFullDeletes(v38).forEach(grants::remove);
        mergeSeed(grants, v38);
        String v39 = read("V39__aged_reports_facade_tools.sql");
        parseFullDeletes(v39).forEach(grants::remove);
        mergeSeed(grants, v39);
        return grants;
    }

    /**
     * The net grouped state after V40: the flat V18→V39 rows become singleton groups (V40's
     * behaviour-preserving backfill, {@code permission_group = permission_code}), then V40's
     * per-tool delete-and-reinsert replaces the 16 facades with real per-method groups.
     */
    private static Map<String, Map<String, Set<String>>> netGroupGrants() throws IOException {
        Map<String, Map<String, Set<String>>> groups = new LinkedHashMap<>();
        flatGrantsThroughV39().forEach((tool, codes) -> {
            Map<String, Set<String>> singletons = groups.computeIfAbsent(tool, t -> new LinkedHashMap<>());
            codes.forEach(code ->
                    singletons.computeIfAbsent(code, g -> new LinkedHashSet<>()).add(code));
        });
        String v40 = read("V40__mcp_tool_permission_groups.sql");
        parseFullDeletes(v40).forEach(groups::remove);
        parseGroupSeed(v40).forEach((tool, seeded) -> {
            Map<String, Set<String>> existing = groups.computeIfAbsent(tool, t -> new LinkedHashMap<>());
            seeded.forEach((group, codes) ->
                    existing.computeIfAbsent(group, g -> new LinkedHashSet<>()).addAll(codes));
        });
        return groups;
    }

    /** The flat union of every group — the codes a tool references, for the #1115 invariants. */
    private static Map<String, Set<String>> netGrants() throws IOException {
        return netGroupGrants().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().values().stream()
                                .flatMap(Set::stream)
                                .collect(Collectors.toCollection(TreeSet::new)),
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    private static void mergeSeed(Map<String, Set<String>> grants, String sql) {
        parseSeed(sql)
                .forEach((tool, codes) -> grants.computeIfAbsent(tool, ignored -> new LinkedHashSet<>())
                        .addAll(codes));
    }

    private static String read(String migration) throws IOException {
        // Strip -- line comments so the executable statements are parsed, not the SQL comments that
        // quote @PreAuthorize expressions like hasAuthority('...') / hasRole('ADMIN').
        return Files.readString(MIGRATIONS.resolve(migration)).replaceAll("(?m)--.*$", "");
    }
}
