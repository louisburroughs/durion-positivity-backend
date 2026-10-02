package com.positivity.securityservice.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.enums.PermissionCode;
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
 * Who may take a card payment on an invoice, and who may lift its limits (#2393).
 *
 * <p>#2393 was payment initiation and capture answering 403 for every human caller: pos-invoice
 * checked four raw authority strings ({@code PROCESS_PAYMENT}, {@code OVERRIDE_PAYMENT_LIMIT},
 * {@code SELECT_PAYMENT_FLOW}, {@code MANUAL_CAPTURE}) that had no catalog bit, so no token could
 * carry them. They are now catalog permissions, and this test pins the three things a catalog
 * permission needs before a person can use it: a bit, a registration in the owning module's
 * manifest, and a grant in the source its role is provisioned from.
 *
 * <p>Both grant sources are read for the reason {@link CycleCountApprovalGrantsTest} gives: Flyway's
 * {@code R__seed_role_permissions.sql} carries the floor roles, the bulk-load baseline CSV carries
 * every role that moved to bulk load (#1613 D8), and a tenant's rows come from one source, so the
 * per-source assertions are the ones that matter.
 *
 * <p>The grants follow the #2226 population: taking tender goes to the counter roles that already
 * void, refund and print receipts; the two elevations and the capture of a hold go to the manager
 * tier that already holds {@code invoice:payment:override}. A change to that split is a business
 * decision and should fail here first.
 */
@DisplayName("invoice payment initiate/capture grants (#2393)")
class InvoicePaymentGrantsTest {

    private static final String PROCESS = "invoice:payment:process";
    private static final String LIMIT_OVERRIDE = "invoice:payment:limit_override";
    private static final String FLOW_SELECT = "invoice:payment:flow_select";
    private static final String CAPTURE = "invoice:payment:capture";

    private static final String VOID = "invoice:payment:void";
    private static final String REFUND = "invoice:payment:refund";
    private static final String WINDOW_OVERRIDE = "invoice:payment:override";

    private static final Set<String> MANAGER_TIER =
            Set.of("ADMIN", "GENERAL_MANAGER", "LOCATION_MANAGER", "SHOP_MANAGER", "MANAGER", "ACCOUNT_MANAGER");

    private static final Path FIXTURES = Path.of("..", "scripts", "fixtures", "seed", "alpha", "security");
    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");
    private static final Path INVOICE_MANIFEST =
            Path.of("..", "pos-invoice", "src", "main", "resources", "permissions.yaml");

    private static final Pattern GRANT_PAIR =
            Pattern.compile("\\(\\s*'([A-Z_]+)'\\s*,\\s*'([A-Za-z0-9:_\\-]+)'\\s*\\)");
    private static final Pattern PERMISSION_ROW = Pattern.compile(
            "\\(\\s*'(invoice:payment:[a-z_]+)'\\s*,\\s*'invoice'\\s*,\\s*'payment'\\s*,\\s*'([a-z_]+)'\\s*,\\s*(\\d+)\\s*\\)");

    /** Role → grants, from both sources merged. */
    private static Map<String, Set<String>> grants;

    /** The bulk-load baseline alone. */
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
        assertThat(grants.keySet()).containsAll(MANAGER_TIER).contains("SERVICE_ADVISOR", "TECHNICIAN");
    }

    @Test
    @DisplayName("the four codes have catalog bits 544-547, the same bits the seed's permission rows carry")
    void theCodesHaveBitsAndTheSeedAgrees() throws IOException {
        // Bits are permanent once a token has carried them: these four numbers never move.
        Map<String, Integer> expected = Map.of(CAPTURE, 544, PROCESS, 545, FLOW_SELECT, 546, LIMIT_OVERRIDE, 547);
        expected.forEach((code, bit) -> assertThat(PermissionCode.fromCode(code))
                .as("PermissionCode for %s", code)
                .hasValueSatisfying(
                        permission -> assertThat(permission.bitIndex()).isEqualTo(bit)));

        Map<String, Integer> seededBits = new TreeMap<>();
        Matcher matcher = PERMISSION_ROW.matcher(
                Files.readString(MIGRATIONS.resolve("R__seed_role_permissions.sql"), StandardCharsets.UTF_8));
        while (matcher.find()) {
            seededBits.put(matcher.group(1), Integer.valueOf(matcher.group(3)));
        }
        assertThat(seededBits).as("section-2 permission rows of the seed").containsAllEntriesOf(expected);
    }

    @Test
    @DisplayName("pos-invoice registers all four in its permissions.yaml, so role administration can grant them")
    void theOwningModuleRegistersThem() throws IOException {
        String manifest = Files.readString(INVOICE_MANIFEST, StandardCharsets.UTF_8);

        for (String code : List.of(PROCESS, LIMIT_OVERRIDE, FLOW_SELECT, CAPTURE)) {
            assertThat(manifest).as("pos-invoice permissions.yaml").contains("- name: \"" + code + "\"");
        }
    }

    @Test
    @DisplayName("everyone who can void or refund a payment at the counter can take one")
    void takingTenderFollowsTheCounterPopulation() {
        // The #2226 population. A role that can reverse a payment but not take one is the #2393
        // failure with a smaller blast radius, so this is stated as the invariant, not as a list.
        Set<String> reversers = rolesHolding(VOID);
        assertThat(reversers).as("no role holds %s: the parse is broken", VOID).isNotEmpty();
        assertThat(rolesHolding(REFUND)).isEqualTo(reversers);
        assertThat(rolesHolding(PROCESS)).isEqualTo(reversers);

        assertThat(grants.get("SERVICE_ADVISOR")).contains(PROCESS);
    }

    @Test
    @DisplayName("the limit override, flow selection and capture stay with the manager tier")
    void elevationsAndCaptureAreManagerTier() {
        for (String code : List.of(LIMIT_OVERRIDE, FLOW_SELECT, CAPTURE)) {
            assertThat(rolesHolding(code)).as("holders of %s", code).isEqualTo(new TreeSet<>(MANAGER_TIER));
        }
        // The same holders as the #2226 window override: one supervisory population for payments.
        assertThat(rolesHolding(WINDOW_OVERRIDE)).isEqualTo(new TreeSet<>(MANAGER_TIER));

        // The advisor takes ordinary tender only: above 500.00, an AUTH_ONLY hold and a later
        // capture all need a manager (BACKEND_CONTRACT_GUIDE, Story #9 permission matrix).
        assertThat(grants.get("SERVICE_ADVISOR")).doesNotContain(LIMIT_OVERRIDE, FLOW_SELECT, CAPTURE);
    }

    @Test
    @DisplayName("an elevation is never held without the base permission it elevates")
    void anElevationImpliesTheBasePermission() {
        // limit_override and flow_select are only read after invoice:payment:process has passed, so
        // a role holding one without process holds a grant it can never use.
        assertThat(rolesHolding(PROCESS))
                .containsAll(rolesHolding(LIMIT_OVERRIDE))
                .containsAll(rolesHolding(FLOW_SELECT));
    }

    @Test
    @DisplayName("a role that may place an AUTH_ONLY hold may also settle it")
    void whoeverPlacesAHoldCanCaptureIt() {
        assertThat(rolesHolding(FLOW_SELECT)).as("no role can place a hold").isNotEmpty();
        assertThat(rolesHolding(CAPTURE))
                .as("an AUTH_ONLY hold could be placed by a role that cannot capture it")
                .containsAll(rolesHolding(FLOW_SELECT));
    }

    @Test
    @DisplayName("each role is granted in the source it is provisioned from")
    void eachSourceCarriesItsOwnRoles() {
        // The two floor roles Flyway provisions on every deploy (#1613 D8)...
        for (String floorRole : List.of("ADMIN", "SHOP_MANAGER")) {
            assertThat(sql.get(floorRole))
                    .as("Flyway seed: %s", floorRole)
                    .contains(PROCESS, LIMIT_OVERRIDE, FLOW_SELECT, CAPTURE);
        }
        // ...and every role, floor or bulk-loaded, in the baseline CSV: a grant present only in the
        // SQL would leave a bulk-loaded manager refused on a tenant, which is the #2138 shape.
        for (String role : MANAGER_TIER) {
            assertThat(csv.get(role))
                    .as("bulk-load baseline: %s", role)
                    .contains(PROCESS, LIMIT_OVERRIDE, FLOW_SELECT, CAPTURE);
        }
        assertThat(csv.get("SERVICE_ADVISOR"))
                .as("bulk-load baseline: SERVICE_ADVISOR")
                .contains(PROCESS);
    }

    @Test
    @DisplayName("card payment authority is not handed to roles outside the counter and its managers")
    void notHandedToUnrelatedRoles() {
        for (String role :
                List.of("TECHNICIAN", "DISPATCHER", "CONTROLLER", "SUPPORT", "SYSTEM_ADMINISTRATOR", "CUSTOMER")) {
            assertThat(grants.getOrDefault(role, Set.of()))
                    .as("grants of %s", role)
                    .doesNotContain(PROCESS, LIMIT_OVERRIDE, FLOW_SELECT, CAPTURE);
        }
    }

    @Test
    @DisplayName("the platform tenant's bootstrap seed grants none of them to a platform role")
    void neverGrantedThroughThePlatformSeed() throws IOException {
        // PLATFORM_ADMIN holds platform:* and nothing a tenant's counter does (ADR-0062 section 7).
        assertThat(Files.readString(MIGRATIONS.resolve("R__seed_tenant_template.sql"), StandardCharsets.UTF_8))
                .doesNotContain("invoice:payment:");
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
