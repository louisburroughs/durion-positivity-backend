package com.positivity.bulkloader.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.service.RuleBasedContentDetectionServiceImpl;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves every alpha fixture column still reaches a field of its domain's loader record.
 *
 * <p>The loader-backed packs are read by header, not by position: a column whose header maps to
 * nothing is dropped, and the row loads looking complete with that field empty. That failure is
 * invisible at run time — the job reports success, and the missing value only surfaces much later
 * as a location with no timezone or a customer with no phone number.
 *
 * <p>The real fixture files are read here rather than copies, so renaming a CSV column or a record
 * field fails the build with the offending header named, instead of quietly emptying it during a
 * reseed.
 */
@SuppressWarnings({"java:S100", "java:S1192"})
class AlphaFixtureHeadersMapTest {

    private static final Path FIXTURE_ROOT = Path.of(System.getProperty("user.dir"))
            .resolve("../scripts/fixtures/seed/alpha")
            .normalize();

    private final RuleBasedContentDetectionServiceImpl detection = new RuleBasedContentDetectionServiceImpl();

    /**
     * The loader-backed packs: the fixture file, the domain its bulk-load job runs as, and any
     * columns the loader is meant to drop.
     *
     * <p>The third argument exists so that dropping a column is a decision recorded here rather
     * than something that happens by accident. A column named in it is documentation for whoever
     * reads the CSV; a column that ends up unmapped without being named here fails the build.
     */
    static List<Arguments> loaderBackedPacks() {
        return List.of(
                Arguments.of("catalog/products.csv", DomainType.CATALOG_PRODUCT, Set.<String>of()),
                Arguments.of("customer/person-customers.csv", DomainType.CUSTOMER, Set.<String>of()),
                Arguments.of("customer/commercial-customers.csv", DomainType.COMMERCIAL_CUSTOMER, Set.<String>of()),
                Arguments.of("location/locations.csv", DomainType.LOCATION, Set.<String>of()),
                Arguments.of("people/employees.csv", DomainType.PERSON, Set.<String>of()),
                Arguments.of("vehicle/vehicles.csv", DomainType.VEHICLE, Set.<String>of()),
                // "description" names the product for a human reading the file; the SKU is what
                // identifies it to the service.
                Arguments.of("inventory/on-hand.csv", DomainType.INVENTORY_STOCK_COUNT, Set.of("description")),
                Arguments.of("location/storage-locations.csv", DomainType.STORAGE_LOCATION, Set.<String>of()),
                Arguments.of("location/bays.csv", DomainType.BAY, Set.<String>of()),
                Arguments.of("location/mobile-units.csv", DomainType.MOBILE_UNIT, Set.<String>of()),
                Arguments.of("people/staffing-assignments.csv", DomainType.STAFFING_ASSIGNMENT, Set.<String>of()),
                Arguments.of("inventory/putaway-rules.csv", DomainType.PUTAWAY_RULE, Set.<String>of()),
                Arguments.of("inventory/cycle-count-plans.csv", DomainType.CYCLE_COUNT_PLAN, Set.<String>of()));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("loaderBackedPacks")
    @DisplayName("every fixture column maps to a field of its domain record")
    void everyFixtureColumnMapsToATargetField(String relativePath, DomainType domainType, Set<String> droppedOnPurpose)
            throws IOException {
        List<String> headers = headersOf(relativePath);
        assertThat(headers).as("%s has no header row", relativePath).isNotEmpty();

        Map<String, String> mappings = detection.suggestMappings(headers, domainType);

        List<String> unmapped = new ArrayList<>(headers);
        unmapped.removeAll(mappings.keySet());
        unmapped.removeAll(droppedOnPurpose);
        assertThat(unmapped)
                .as(
                        "%s columns %s map to no field of %s — the bulk-load job would drop them"
                                + " silently. Add a synonym in RuleBasedContentDetectionServiceImpl, or"
                                + " rename the column to match the record field.",
                        relativePath, unmapped, domainType)
                .isEmpty();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("loaderBackedPacks")
    @DisplayName("no two fixture columns compete for the same target field")
    void fixtureColumnsMapOneToOne(String relativePath, DomainType domainType, Set<String> droppedOnPurpose)
            throws IOException {
        List<String> headers = headersOf(relativePath);
        Map<String, String> mappings = detection.suggestMappings(headers, domainType);

        // suggestMappings keeps the first column claiming a target, so a collision shows up as a
        // header that mapped to nothing rather than as a duplicate value.
        assertThat(mappings.values())
                .as("%s maps two columns onto one field", relativePath)
                .doesNotHaveDuplicates();
        assertThat(mappings).hasSize(headers.size() - droppedOnPurpose.size());
    }

    /**
     * A vehicles file may name its owner either way: by account id, or by the owner keys the loader
     * resolves. Both shapes have to map, because the fixture uses the second and a customer file
     * would typically use the first.
     */
    @org.junit.jupiter.api.Test
    void vehicleHeadersMapWhetherTheOwnerIsAnIdOrAName() {
        List<String> byAccountId = List.of("accountId", "vin", "unitNumber", "description");
        List<String> byOwnerName = List.of("ownerType", "ownerName", "vin", "unitNumber", "description");

        assertThat(detection.suggestMappings(byAccountId, DomainType.VEHICLE))
                .hasSameSizeAs(byAccountId)
                .containsEntry("accountId", "accountId");
        assertThat(detection.suggestMappings(byOwnerName, DomainType.VEHICLE))
                .hasSameSizeAs(byOwnerName)
                .containsEntry("ownerType", "ownerType")
                .containsEntry("ownerName", "ownerName");
    }

    /**
     * Guards the guard: the checks above are only meaningful if an unknown column really does map
     * to nothing. It also pins the fallback that made the LOCATION fixture safe to read by header —
     * {@code addressLine2} and {@code active} are named by the record but by no synonym.
     */
    @org.junit.jupiter.api.Test
    void mappingIsSelective_andCoversFieldsNoSynonymNames() {
        Map<String, String> mappings = detection.suggestMappings(
                List.of("addressLine2", "active", "not_a_field_of_any_record"), DomainType.LOCATION);

        assertThat(mappings)
                .containsEntry("addressLine2", "addressLine2")
                .containsEntry("active", "active")
                .doesNotContainKey("not_a_field_of_any_record");
    }

    private List<String> headersOf(String relativePath) throws IOException {
        Path file = FIXTURE_ROOT.resolve(relativePath);
        assertThat(file).as("fixture %s is missing", relativePath).exists();
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.findFirst()
                    .map(header -> Arrays.stream(header.split(",", -1))
                            .map(String::trim)
                            .toList())
                    .orElse(List.of());
        }
    }
}
