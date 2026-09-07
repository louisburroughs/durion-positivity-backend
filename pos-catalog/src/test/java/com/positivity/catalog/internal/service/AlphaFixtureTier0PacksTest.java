package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the four Tier 0 catalog fixture packs agree with each other and with the services the
 * catalog already has.
 *
 * <p>The packs replaced two Flyway seeds whose SQL joins enforced the same agreement:
 * {@code JOIN service s ON s.operation_code = v.operation_code} simply dropped a row naming an
 * operation that did not exist, and the file could not name a package that was not in the same
 * statement. Through the pipeline those become per-row ingest failures during a reseed, which is
 * later and quieter. Reading the real files here moves the check back to build time, so a renamed
 * operation fails with the name that no longer resolves.
 *
 * <p>It also pins the formats the ingest endpoints are strict about — labor time is decimal hours
 * in tenths, a package's members are uniquely sequenced, and ownership is the paired field the
 * V21/V23 CHECK constraints back.
 */
@SuppressWarnings({"java:S100", "java:S1192"})
class AlphaFixtureTier0PacksTest {

    private static final Path MODULE_ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path FIXTURE_ROOT =
            MODULE_ROOT.resolve("../scripts/fixtures/seed/alpha").normalize();

    private static final Path SERVICES = FIXTURE_ROOT.resolve("catalog/tier0-services.csv");
    private static final Path STANDARDS = FIXTURE_ROOT.resolve("catalog/tier0-labor-standards.csv");
    private static final Path PACKAGES = FIXTURE_ROOT.resolve("catalog/tier0-service-packages.csv");
    private static final Path MEMBERS = FIXTURE_ROOT.resolve("catalog/tier0-service-package-members.csv");

    /** The 50 general services the catalog reference seed still owns; Tier 0 rows may name them. */
    private static final Path GENERAL_SERVICES_SEED =
            MODULE_ROOT.resolve("src/main/resources/db/migration/R__seed_reference_catalog_3_services.sql");

    private static final Pattern SEEDED_OPERATION_CODE = Pattern.compile("'[0-9a-f-]{36}'::uuid,\\s*'([A-Z0-9-]+)'");

    private static final Set<String> TIME_TYPES =
            Set.of("DURION_STANDARD", "MANUFACTURER_INSTALL", "RETAIL_FLAT_RATE", "WARRANTY_FLAT_RATE");
    private static final Set<String> CATEGORIES = Set.of("REPAIR", "DIAGNOSTIC", "MAINTENANCE", "TIRE_SERVICE");

    @Test
    @DisplayName("every operation a labor standard names is one the catalog will have")
    void everyStandardNamesAKnownOperation() throws IOException {
        Set<String> known = knownOperationCodes();

        List<String> unknown = readRows(STANDARDS).stream()
                .map(row -> row.get("operationCode"))
                .filter(code -> !known.contains(code))
                .distinct()
                .toList();

        assertThat(unknown)
                .as(
                        "labor standards naming operations neither tier0-services.csv nor the reference seed"
                                + " creates: %s",
                        unknown)
                .isEmpty();
    }

    @Test
    @DisplayName("every operation a package member names is one the catalog will have")
    void everyMemberNamesAKnownOperation() throws IOException {
        Set<String> known = knownOperationCodes();

        List<String> unknown = readRows(MEMBERS).stream()
                .map(row -> row.get("operationCode"))
                .filter(code -> !known.contains(code))
                .distinct()
                .toList();

        assertThat(unknown)
                .as("package members naming unknown operations: %s", unknown)
                .isEmpty();
    }

    @Test
    @DisplayName("every package a member names is one the package pack creates")
    void everyMemberNamesAKnownPackage() throws IOException {
        Set<String> packageCodes =
                readRows(PACKAGES).stream().map(row -> row.get("packageCode")).collect(Collectors.toSet());

        List<String> unknown = readRows(MEMBERS).stream()
                .map(row -> row.get("packageCode"))
                .filter(code -> !packageCodes.contains(code))
                .distinct()
                .toList();

        assertThat(unknown)
                .as("members naming packages the pack does not create: %s", unknown)
                .isEmpty();
    }

    @Test
    @DisplayName("no operation, package or membership is declared twice")
    void naturalKeysAreUnique() throws IOException {
        assertThat(readRows(SERVICES).stream()
                        .map(row -> row.get("operationCode"))
                        .toList())
                .doesNotHaveDuplicates();
        assertThat(readRows(PACKAGES).stream()
                        .map(row -> row.get("packageCode"))
                        .toList())
                .doesNotHaveDuplicates();
        assertThat(readRows(MEMBERS).stream()
                        .map(row -> row.get("packageCode") + '/' + row.get("operationCode"))
                        .toList())
                .as("service_package_member is unique on (package, service)")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("no two active standards answer one operation's same vehicle key and time type")
    void standardsDoNotCollideOnTheActiveKey() throws IOException {
        // The V21 partial unique index refuses the second row, so a colliding pair would load one
        // standard and fail the other — silently changing which time the estimate uses.
        List<String> keys = readRows(STANDARDS).stream()
                .map(row -> String.join(
                        "|",
                        row.get("operationCode"),
                        row.get("timeType"),
                        row.get("ownerLocationCode"),
                        row.get("vehicleYear"),
                        row.get("make"),
                        row.get("model"),
                        row.get("submodel"),
                        row.get("engineCode")))
                .toList();

        assertThat(keys).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("members of one package are uniquely sequenced, because sequence is the order shown")
    void membersAreUniquelySequencedWithinAPackage() throws IOException {
        Map<String, List<String>> sequencesByPackage = new LinkedHashMap<>();
        for (Map<String, String> row : readRows(MEMBERS)) {
            sequencesByPackage
                    .computeIfAbsent(row.get("packageCode"), code -> new ArrayList<>())
                    .add(row.get("sequence"));
        }
        sequencesByPackage.forEach((packageCode, sequences) ->
                assertThat(sequences).as("%s repeats a sequence", packageCode).doesNotHaveDuplicates());
    }

    @Test
    @DisplayName("labor time is decimal hours in tenths, on the operations and the standards alike")
    void laborTimeIsInTenths() throws IOException {
        for (Map<String, String> row : readRows(SERVICES)) {
            assertTenths(row.get("defaultLaborHours"), row.get("operationCode") + " defaultLaborHours");
        }
        for (Map<String, String> row : readRows(STANDARDS)) {
            assertTenths(row.get("laborHours"), row.get("operationCode") + " laborHours");
        }
        for (Map<String, String> row : readRows(PACKAGES)) {
            assertTenths(row.get("packageLaborHours"), row.get("packageCode") + " packageLaborHours");
        }
    }

    @Test
    @DisplayName("every standard carries a source, a revision and a valid time type")
    void standardsCarryTheirProvenance() throws IOException {
        for (Map<String, String> row : readRows(STANDARDS)) {
            String code = row.get("operationCode");
            assertThat(row.get("sourceCode")).as("%s: sourceCode", code).isNotBlank();
            assertThat(row.get("sourceRevision")).as("%s: sourceRevision", code).isNotBlank();
            assertThat(row.get("timeType")).as("%s: timeType", code).isIn(TIME_TYPES);
            assertThat(LocalDate.parse(row.get("publishedAt")))
                    .as("%s: publishedAt is an ISO-8601 date", code)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("the whole invented set is still removable by one source revision")
    void theFakeSetIsIdentifiable() throws IOException {
        // The spec's D6 handle: DELETE FROM service_labor_standard WHERE source_revision = '…'.
        // A row that drifted onto another revision would survive that statement.
        assertThat(readRows(STANDARDS).stream()
                        .map(row -> row.get("sourceRevision"))
                        .distinct())
                .containsExactly("tier0-fake-2026-09");
    }

    @Test
    @DisplayName("ownership is the paired field the catalog and the CHECK constraints require")
    void ownershipIsPaired() throws IOException {
        for (Map<String, String> row : readRows(STANDARDS)) {
            assertOwnershipPaired(row.get("ownerScope"), row.get("ownerLocationCode"), row.get("operationCode"));
        }
        for (Map<String, String> row : readRows(PACKAGES)) {
            assertOwnershipPaired(row.get("ownerScope"), row.get("ownerLocationCode"), row.get("packageCode"));
        }
    }

    @Test
    @DisplayName("every operation names a category the catalog recognises")
    void operationCategoriesAreKnown() throws IOException {
        for (Map<String, String> row : readRows(SERVICES)) {
            assertThat(row.get("operationCategory"))
                    .as("%s: operationCategory", row.get("operationCode"))
                    .isIn(CATEGORIES);
        }
    }

    @Test
    @DisplayName("a fleet requirement set names a fleet, and an offering does not")
    void onlyRequirementSetsNameAFleet() throws IOException {
        List<Map<String, String>> packages = readRows(PACKAGES);

        List<String> fleetSets = packages.stream()
                .filter(row -> !row.get("fleetCustomerName").isBlank())
                .map(row -> row.get("packageCode"))
                .toList();

        // Exactly one, and it says so in its code: a requirement set is not on offer, so a package
        // that accidentally carried a fleet would vanish from the sellable listing.
        assertThat(fleetSets).containsExactly("FLEET-REQ-TARHEEL");
    }

    private static void assertOwnershipPaired(String ownerScope, String ownerLocationCode, String subject) {
        if ("SHOP".equals(ownerScope)) {
            assertThat(ownerLocationCode)
                    .as("%s: a SHOP row must name its site, or it resolves for nobody", subject)
                    .isNotBlank();
        } else {
            assertThat(ownerScope).as("%s: ownerScope", subject).isEqualTo("PLATFORM");
            assertThat(ownerLocationCode)
                    .as("%s: a PLATFORM row must not name a site", subject)
                    .isBlank();
        }
    }

    private static void assertTenths(String value, String subject) {
        assertThat(value).as("%s is required", subject).isNotBlank();
        BigDecimal hours = new BigDecimal(value);
        assertThat(hours).as("%s must be positive", subject).isGreaterThan(BigDecimal.ZERO);
        assertThat(hours.stripTrailingZeros().scale())
                .as("%s must be stated in tenths of an hour (%s)", subject, value)
                .isLessThanOrEqualTo(1);
    }

    /** Codes the Tier 0 pack creates, plus the ones the reference seed already assigned. */
    private static Set<String> knownOperationCodes() throws IOException {
        Set<String> codes = readRows(SERVICES).stream()
                .map(row -> row.get("operationCode"))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        String seed = Files.readString(GENERAL_SERVICES_SEED, StandardCharsets.UTF_8);
        Matcher matcher = SEEDED_OPERATION_CODE.matcher(seed);
        while (matcher.find()) {
            codes.add(matcher.group(1));
        }
        assertThat(codes)
                .as("the reference seed's operation codes were not found — has the seed's shape changed?")
                .contains("TIRE-ROTATION", "WHEEL-BALANCE-SET-4", "TIRE-INSTALL-SET-4");
        return codes;
    }

    /** Quote-aware enough for these files: a field may be quoted and may contain commas. */
    private static List<Map<String, String>> readRows(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        List<List<String>> rows = parse(content);
        assertThat(rows).as("fixture %s is empty", file).isNotEmpty();

        List<String> headers = rows.getFirst();
        List<Map<String, String>> parsed = new ArrayList<>();
        for (List<String> values : rows.subList(1, rows.size())) {
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                row.put(headers.get(i), i < values.size() ? values.get(i) : "");
            }
            parsed.add(row);
        }
        return parsed;
    }

    private static List<List<String>> parse(String content) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < content.length() && content.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString().trim());
                field.setLength(0);
            } else if (c == '\n') {
                row.add(field.toString().trim());
                field.setLength(0);
                rows.add(List.copyOf(row));
                row.clear();
            } else if (c != '\r') {
                field.append(c);
            }
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString().trim());
            rows.add(List.copyOf(row));
        }
        return rows;
    }
}
