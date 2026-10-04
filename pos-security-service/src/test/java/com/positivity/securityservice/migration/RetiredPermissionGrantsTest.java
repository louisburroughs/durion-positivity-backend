package com.positivity.securityservice.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.enums.PermissionCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A retired permission keeps its bit and grants nothing (#2422).
 *
 * <p>The retirement convention is the {@link PermissionCode} javadoc's: a retired code is marked
 * {@code @Deprecated} and keeps its permanent bit index, its permission-definition row and its slot
 * in the gateway and downstream catalogs, while every grant of it goes. A bit nothing enforces
 * grants nothing, but a grant of one still shows up in role listings as if it meant something,
 * which is how #2422 found the {@code inventory:purchase_order:*} family after the purchase-order
 * aggregate moved to pos-order (CAP-320 #1334).
 *
 * <p>Both grant sources are read for the reason {@link CycleCountApprovalGrantsTest} gives: the
 * Flyway seed carries the floor roles, the bulk-load baseline CSV every other role.
 */
@DisplayName("retired permission codes are granted to no role (#2422)")
class RetiredPermissionGrantsTest {

    private static final Path FIXTURES = Path.of("..", "scripts", "fixtures", "seed", "alpha", "security");
    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    private static final Pattern GRANT_PAIR =
            Pattern.compile("\\(\\s*'([A-Z_]+)'\\s*,\\s*'([A-Za-z0-9:_\\-]+)'\\s*\\)");

    private static Set<String> retired;

    @BeforeAll
    static void collectRetiredCodes() {
        retired = Stream.of(PermissionCode.values())
                .filter(RetiredPermissionGrantsTest::isDeprecated)
                .map(PermissionCode::code)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(retired)
                .as("no @Deprecated PermissionCode found: the reflection is broken")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the retired inventory:purchase_order:* family keeps bits 71-74 and is marked @Deprecated")
    void thePurchaseOrderFamilyIsRetiredInPlace() {
        // Superseded by order:purchase_order:* (create/view/approve) and by
        // inventory:goods_receipt:create / inventory:receiving:complete (receive).
        Map<String, Integer> expected = Map.of(
                "inventory:purchase_order:create", 71,
                "inventory:purchase_order:view", 72,
                "inventory:purchase_order:approve", 73,
                "inventory:purchase_order:receive", 74);
        expected.forEach((code, bit) -> assertThat(PermissionCode.fromCode(code))
                .as("PermissionCode for %s", code)
                .hasValueSatisfying(
                        permission -> assertThat(permission.bitIndex()).isEqualTo(bit)));
        assertThat(retired).containsAll(expected.keySet());
    }

    @Test
    @DisplayName("the Flyway seed grants no retired code")
    void theFlywaySeedGrantsNoRetiredCode() throws IOException {
        Map<String, Set<String>> offending = new TreeMap<>();
        Matcher matcher = GRANT_PAIR.matcher(
                Files.readString(MIGRATIONS.resolve("R__seed_role_permissions.sql"), StandardCharsets.UTF_8));
        int pairs = 0;
        while (matcher.find()) {
            pairs++;
            if (retired.contains(matcher.group(2))) {
                offending
                        .computeIfAbsent(matcher.group(1), key -> new TreeSet<>())
                        .add(matcher.group(2));
            }
        }
        assertThat(pairs)
                .as("no (role, permission) pairs parsed out of the seed")
                .isPositive();
        assertThat(offending)
                .as("roles granted a retired code by R__seed_role_permissions.sql")
                .isEmpty();
    }

    @Test
    @DisplayName("the bulk-load baseline grants no retired code")
    void theBulkLoadBaselineGrantsNoRetiredCode() throws IOException {
        Map<String, Set<String>> offending = new TreeMap<>();
        List<String> lines = Files.readAllLines(FIXTURES.resolve("role-permissions.csv"), StandardCharsets.UTF_8);
        assertThat(lines).as("bulk-load baseline is empty").hasSizeGreaterThan(1);
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf(',');
            for (String permission : line.substring(separator + 1).split(";")) {
                if (retired.contains(permission.trim())) {
                    offending
                            .computeIfAbsent(line.substring(0, separator).trim(), key -> new TreeSet<>())
                            .add(permission.trim());
                }
            }
        }
        assertThat(offending)
                .as("roles granted a retired code by role-permissions.csv")
                .isEmpty();
    }

    @Test
    @DisplayName("pos-inventory test fixtures do not grant deprecated codes")
    void posInventoryFixturesGrantNoDeprecatedCodes() throws IOException {
        Set<String> offending = new TreeSet<>();

        // Find the inventory module relative to current working directory
        Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
        Path inventoryModule = projectRoot.resolve("pos-inventory");

        // Check BaseContractIntegrationTest
        Path baseContractTest = inventoryModule.resolve(
                "src/test/java/com/positivity/inventory/contract/BaseContractIntegrationTest.java");
        if (Files.exists(baseContractTest)) {
            String baseContractContent = Files.readString(baseContractTest, StandardCharsets.UTF_8);
            for (String code : retired) {
                if (baseContractContent.contains("\"" + code + "\"")) {
                    offending.add("BaseContractIntegrationTest: " + code);
                }
            }
        }

        // Check TestSecurityConfig
        Path testSecurityConfig =
                inventoryModule.resolve("src/test/java/com/positivity/inventory/config/TestSecurityConfig.java");
        if (Files.exists(testSecurityConfig)) {
            String testSecurityContent = Files.readString(testSecurityConfig, StandardCharsets.UTF_8);
            for (String code : retired) {
                if (testSecurityContent.contains("\"" + code + "\"")) {
                    offending.add("TestSecurityConfig: " + code);
                }
            }
        }

        // Check AsOfOnHandContractBehaviorIT
        Path asOfOnHandTest = inventoryModule.resolve(
                "src/test/java/com/positivity/inventory/contract/AsOfOnHandContractBehaviorIT.java");
        if (Files.exists(asOfOnHandTest)) {
            String asOfOnHandContent = Files.readString(asOfOnHandTest, StandardCharsets.UTF_8);
            for (String code : retired) {
                if (asOfOnHandContent.contains("\"" + code + "\"")) {
                    offending.add("AsOfOnHandContractBehaviorIT: " + code);
                }
            }
        }

        assertThat(offending)
                .as("pos-inventory test fixtures must not grant deprecated codes (#2456)")
                .isEmpty();
    }

    private static boolean isDeprecated(PermissionCode permission) {
        try {
            return PermissionCode.class.getField(permission.name()).isAnnotationPresent(Deprecated.class);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }
}
