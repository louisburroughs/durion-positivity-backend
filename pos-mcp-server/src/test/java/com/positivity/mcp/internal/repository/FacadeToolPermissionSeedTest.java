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
 * tool-registry tables), so this test asserts on the seed SQL directly. Since the migration
 * history was flattened (2026-09-09) that is one file, {@code V2__seed_mcp_server.sql}, whose
 * {@code mcp_tool_permission} rows are the net result of the retired chain V18 → V48 (initial
 * seed, AUTHENTICATED removals, the per-method AND-groups of #1606 finding 1, and every later
 * re-derivation); the derivation citations live in this class's group table and in the retired
 * migration headers in git history.
 *
 * <p><b>V40 changed the unit of the assertion.</b> Rows now carry a {@code permission_group} and a
 * tool is offered iff the caller holds ALL codes of AT LEAST ONE group, so a flat union no longer
 * describes the gate. {@link #EXPECTED_GROUPS} is therefore the source of truth here: per tool, one
 * group per {@code @Tool} method holding the codes that method's <em>required</em> downstream calls
 * need. It is declared once so the V40 migration comment and this test cannot drift apart silently:
 * any edit to the seeded SQL must be mirrored in this table and vice versa.
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
    // #1612 moved the invoice read routes off invoice:manage; all three InvoiceFacadeTool
    // methods are reads, so the whole tool moved with them (V41).
    private static final String INVOICE_VIEW = "invoice:invoice:view";
    private static final String ON_HAND_VIEW = "inventory:on_hand:view";
    private static final String CATALOG_PRODUCT_VIEW = "catalog:product:view";
    private static final String WORKORDER_VIEW = "workorder:workorder:view";
    private static final String PEOPLE_EMPLOYEE_VIEW = "people:employee:view";
    // #1898 (V48): the employee profile read moved off people:employee:view, which twelve roles
    // hold, onto a permission scoped to the contact block it is the only read to return.
    private static final String PEOPLE_EMPLOYEE_PII_VIEW = "people:employee_pii:view";
    private static final String AVAILABILITY_READ = "inventory:availability:read";
    private static final String SECURITY_PERMISSION_VIEW = "security:permission:view";
    // W2.3 facade promotion (#1601, V42): one new analytics method each on InvoiceFacadeTool,
    // WorkorderFacadeTool, and AccountingFacadeTool.
    private static final String INVOICE_ANALYTICS_VIEW = "invoice:analytics:view";
    private static final String WORKORDER_ANALYTICS_VIEW = "workorder:analytics:view";
    private static final String ACCOUNTING_ANALYTICS_VIEW = "accounting:analytics:view";
    private static final Set<String> ASSISTANT_ENTRYPOINTS =
            Set.of("mcp:chat:execute", "mcp:chat:stream", "nlti:request:submit", "nlti:request:read", AUTHENTICATED);

    /** {@code INSERT INTO mcp_tool (id, name, ...) VALUES ('<uuid>', 'ToolName', ...)}. */
    private static final Pattern TOOL_ROW = Pattern.compile(
            "INSERT\\s+INTO\\s+mcp_tool\\s*\\(id,\\s*name\\b[^)]*\\)\\s*VALUES\\s*\\('([^']+)',\\s*'([^']+)'");
    /** {@code INSERT INTO mcp_tool_permission (tool_id, permission_code, permission_group) VALUES (...)}. */
    private static final Pattern PERMISSION_ROW = Pattern.compile(
            "INSERT\\s+INTO\\s+mcp_tool_permission\\s*\\(tool_id,\\s*permission_code,\\s*permission_group\\)"
                    + "\\s*VALUES\\s*\\('([^']+)',\\s*'([^']+)',\\s*'([^']+)'\\)");

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
                            "getAgedPayables", Set.of(REPORTING),
                            "getVendorSpend", Set.of(ACCOUNTING_ANALYTICS_VIEW))),
            // #1675 (V43): resolveDateWindow makes no downstream call and enforces no permission of
            // its own — every authenticated caller may resolve a date, the same R4 shape as Events.
            Map.entry("DateWindowFacadeTool", Map.of(AUTHENTICATED, Set.of(AUTHENTICATED))),
            // #1688 (V46): lookupBusinessTerm returns definitions, not data — it reads no row and
            // calls no endpoint, so it carries the same R4 AUTHENTICATED shape as Events and
            // DateWindow.
            Map.entry("GlossaryFacadeTool", Map.of(AUTHENTICATED, Set.of(AUTHENTICATED))),
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
                            // #1898 (V48): getEmployee fronts the one endpoint returning
                            // EmployeeProfileDto.contactInfo, so it follows that endpoint's guard;
                            // searchEmployees returns no contact block and stays where it was.
                            "getEmployee", Set.of(PEOPLE_EMPLOYEE_PII_VIEW),
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
                            "getInvoice", Set.of(INVOICE_VIEW),
                            "searchInvoices", Set.of(INVOICE_VIEW),
                            "getInvoicesByCustomer", Set.of(INVOICE_VIEW),
                            "getRevenueByCustomer", Set.of(INVOICE_ANALYTICS_VIEW),
                            // #1660 (V44): E4 promoted to a facade, gated on the same permission V42 already
                            // derived for getRevenueByCustomer.
                            "getInvoicingLag", Set.of(INVOICE_ANALYTICS_VIEW))),
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
                            "getWorkorderStatus", Set.of(WORKORDER_VIEW),
                            "getTechnicianLaborAnalytics", Set.of(WORKORDER_ANALYTICS_VIEW),
                            // #1855: per-customer open work-order counts, same analytics surface.
                            "getOpenWorkordersByCustomer", Set.of(WORKORDER_ANALYTICS_VIEW))),
            // getSystemStatus makes no HTTP call and carries no guard, so it contributes no group.
            Map.entry(
                    "AdminFacadeTool",
                    Map.of(
                            "listUsers", Set.of("security:user:view"),
                            "getUserPermissions", Set.of(SECURITY_PERMISSION_VIEW),
                            // getMyPermissions drops out entirely in V41: #1612 made the self-read
                            // authorised by identity, so it requires no code and contributes no
                            // group (R3). An AUTHENTICATED group would have been R4's shape, but R4
                            // is for a tool guarded ONLY by the sentinel — adding one here would
                            // satisfy the OR for every caller and offer an admin tool to everyone,
                            // which noFacadeMixesAuthenticatedWithPrivilege below rejects (#1115).
                            "getAuditLog", Set.of("security:audit:view"))));

    @Test
    @DisplayName("the facade seed equals the #1606 per-method group table")
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
    @DisplayName(
            "assistant entrypoints alone qualify only the deliberately open Events, DateWindow and Glossary facades")
    void assistantOnlyCallerQualifiesOnlyEventsFacade() throws IOException {
        // Coupled to the seed: a new AUTHENTICATED-gated tool added to the seed without a name here
        // fails, and a name added here without the seed row fails, so the two change together.
        Set<String> authenticatedOnlyFacades = Set.of("EventsFacadeTool", "DateWindowFacadeTool", "GlossaryFacadeTool");
        netGroupGrants()
                .forEach((tool, groups) -> assertThat(qualifies(groups, ASSISTANT_ENTRYPOINTS))
                        .as(
                                "%s must not be reachable on the assistant-entrypoint baseline alone "
                                        + "(only EventsFacadeTool, DateWindowFacadeTool and GlossaryFacadeTool are "
                                        + "AUTHENTICATED-gated by design)",
                                tool)
                        .isEqualTo(authenticatedOnlyFacades.contains(tool)));
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
        // The other two codes V37's union pulled in through the same composition. invoice:manage is
        // a literal here rather than a constant: this pins what the old union wrongly admitted, and
        // is unrelated to which code guards invoice reads today.
        assertThat(qualifies(groups.get("CustomerFacadeTool"), Set.of("crm:interaction:view", "invoice:manage")))
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

    /** The seeded gate: tool name → permission group → the codes that group requires. */
    private static Map<String, Map<String, Set<String>>> netGroupGrants() throws IOException {
        String sql = read("V2__seed_mcp_server.sql");
        Map<String, String> toolNamesById = new LinkedHashMap<>();
        Matcher tools = TOOL_ROW.matcher(sql);
        while (tools.find()) {
            toolNamesById.put(tools.group(1), tools.group(2));
        }
        assertThat(toolNamesById).as("no mcp_tool rows parsed out of the seed").isNotEmpty();

        Map<String, Map<String, Set<String>>> groups = new LinkedHashMap<>();
        Matcher rows = PERMISSION_ROW.matcher(sql);
        while (rows.find()) {
            String tool = toolNamesById.get(rows.group(1));
            assertThat(tool)
                    .as("mcp_tool_permission row for tool id %s has no mcp_tool row", rows.group(1))
                    .isNotNull();
            groups.computeIfAbsent(tool, t -> new LinkedHashMap<>())
                    .computeIfAbsent(rows.group(3), g -> new LinkedHashSet<>())
                    .add(rows.group(2));
        }
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

    private static String read(String migration) throws IOException {
        // Strip -- line comments so the executable statements are parsed, not the SQL comments that
        // quote @PreAuthorize expressions like hasAuthority('...') / hasRole('ADMIN').
        return Files.readString(MIGRATIONS.resolve(migration)).replaceAll("(?m)--.*$", "");
    }
}
