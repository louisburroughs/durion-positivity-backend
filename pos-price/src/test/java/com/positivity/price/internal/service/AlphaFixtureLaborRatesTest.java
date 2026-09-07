package com.positivity.price.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the alpha labor-rate packs agree with the location pack they price and with the keys the
 * rate tables enforce.
 *
 * <p>The packs replaced a Flyway seed whose location ids were invented placeholders — they matched
 * no site, so the shop-scoped rows could never have answered for a real location. The files now
 * name sites by location code and the loader resolves them, which only works while the codes and
 * the location pack agree; reading both here fails the build rather than a reseed.
 *
 * <p>It also pins the key each table is unique on, because a duplicate would load one row and fail
 * the other, silently deciding which rate is in force.
 */
@SuppressWarnings({"java:S100", "java:S1192"})
class AlphaFixtureLaborRatesTest {

    private static final Path FIXTURE_ROOT = Path.of(System.getProperty("user.dir"))
            .resolve("../scripts/fixtures/seed/alpha")
            .normalize();

    private static final Path RATES = FIXTURE_ROOT.resolve("price/labor-rates.csv");
    private static final Path ADJUSTMENTS = FIXTURE_ROOT.resolve("price/labor-rate-adjustments.csv");
    private static final Path LOCATIONS = FIXTURE_ROOT.resolve("location/locations.csv");

    private static final Set<String> CATEGORIES = Set.of("REPAIR", "DIAGNOSTIC", "MAINTENANCE", "TIRE_SERVICE");
    private static final Set<String> ADJUSTMENT_TYPES = Set.of("PERCENT", "FIXED");

    @Test
    @DisplayName("every location a rate or a step names is a site the location pack creates")
    void everyNamedLocationExists() throws IOException {
        Set<String> siteCodes =
                readRows(LOCATIONS).stream().map(row -> row.get("code")).collect(Collectors.toSet());

        List<String> unknown = new ArrayList<>();
        for (Path file : List.of(RATES, ADJUSTMENTS)) {
            readRows(file).stream()
                    .map(row -> row.get("locationCode"))
                    .filter(code -> !code.isBlank())
                    .filter(code -> !siteCodes.contains(code))
                    .forEach(unknown::add);
        }

        assertThat(unknown)
                .as("rows naming location codes that location/locations.csv does not create: %s", unknown)
                .isEmpty();
    }

    @Test
    @DisplayName("the ladder is complete: a platform default, a platform category rate, and a shop's own of each")
    void allFourScopesAreReachable() throws IOException {
        // The point of the set. Without the platform default a location that has authored nothing
        // resolves to no rate at all, and the widening ladder has nothing to widen to.
        List<Map<String, String>> rates = readRows(RATES);

        assertThat(rates).as("a platform default (no location, no category)").anySatisfy(row -> {
            assertThat(row.get("locationCode")).isBlank();
            assertThat(row.get("operationCategory")).isBlank();
        });
        assertThat(rates).as("a platform category rate").anySatisfy(row -> {
            assertThat(row.get("locationCode")).isBlank();
            assertThat(row.get("operationCategory")).isNotBlank();
        });
        assertThat(rates).as("a shop's own default").anySatisfy(row -> {
            assertThat(row.get("locationCode")).isNotBlank();
            assertThat(row.get("operationCategory")).isBlank();
        });
        assertThat(rates).as("a shop's own category rate").anySatisfy(row -> {
            assertThat(row.get("locationCode")).isNotBlank();
            assertThat(row.get("operationCategory")).isNotBlank();
        });
    }

    @Test
    @DisplayName("no two rates open the same scope at the same instant")
    void rateScopeAndStartAreUnique() throws IOException {
        // ux_labor_rate_scope_start is NULLS NOT DISTINCT, so the blank columns are part of the key
        // rather than exempt from it.
        List<String> keys = readRows(RATES).stream()
                .map(row -> String.join(
                        "|", row.get("locationCode"), row.get("operationCategory"), row.get("effectiveFrom")))
                .toList();

        assertThat(keys).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("no two matrix steps share a code, a scope and a start instant")
    void adjustmentScopeCodeAndStartAreUnique() throws IOException {
        List<String> keys = readRows(ADJUSTMENTS).stream()
                .map(row -> String.join(
                        "|",
                        row.get("locationCode"),
                        row.get("operationCategory"),
                        row.get("adjustmentCode"),
                        row.get("effectiveFrom")))
                .toList();

        assertThat(keys).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("matrix steps within a scope are uniquely sequenced, because percentages compound in order")
    void stepsAreUniquelySequencedWithinAScope() throws IOException {
        Map<String, List<String>> sequencesByScope = new LinkedHashMap<>();
        for (Map<String, String> row : readRows(ADJUSTMENTS)) {
            sequencesByScope
                    .computeIfAbsent(
                            row.get("locationCode") + '|' + row.get("operationCategory"), key -> new ArrayList<>())
                    .add(row.get("sequence"));
        }
        sequencesByScope.forEach((scope, sequences) ->
                assertThat(sequences).as("scope '%s' repeats a sequence", scope).doesNotHaveDuplicates());
    }

    @Test
    @DisplayName("effectiveFrom is a full instant, not a date")
    void effectiveFromIsAnInstant() throws IOException {
        // Both controllers call Instant.parse, so a bare date would fail every row.
        for (Path file : List.of(RATES, ADJUSTMENTS)) {
            for (Map<String, String> row : readRows(file)) {
                String value = row.get("effectiveFrom");
                try {
                    Instant.parse(value);
                } catch (DateTimeParseException e) {
                    throw new AssertionError(
                            "effectiveFrom '%s' in %s is not an ISO-8601 instant".formatted(value, file), e);
                }
            }
        }
    }

    @Test
    @DisplayName("every rate is positive, in a three-letter currency, and in a category pricing knows")
    void ratesAreWellFormed() throws IOException {
        for (Map<String, String> row : readRows(RATES)) {
            assertThat(row.get("currency"))
                    .as("currency must be an ISO-4217 code")
                    .hasSize(3);
            assertThat(new BigDecimal(row.get("hourlyRate")))
                    .as("hourlyRate must be positive")
                    .isGreaterThan(BigDecimal.ZERO);
            assertCategory(row.get("operationCategory"));
        }
    }

    @Test
    @DisplayName("every step names a known type and category, and the contract discount is the negative one")
    void adjustmentsAreWellFormed() throws IOException {
        List<Map<String, String>> steps = readRows(ADJUSTMENTS);
        for (Map<String, String> row : steps) {
            assertThat(row.get("adjustmentType"))
                    .as("%s: adjustmentType", row.get("adjustmentCode"))
                    .isIn(ADJUSTMENT_TYPES);
            assertCategory(row.get("operationCategory"));
        }

        // A discount that lost its sign would read as a 10% surcharge and still load cleanly.
        List<String> negative = steps.stream()
                .filter(row -> new BigDecimal(row.get("adjustmentValue")).signum() < 0)
                .map(row -> row.get("adjustmentCode"))
                .toList();
        assertThat(negative).containsExactly("FLEET_CONTRACT");

        // And it must compound last: a contract discount is off the adjusted rate, not off the base.
        int contractSequence = steps.stream()
                .filter(row -> "FLEET_CONTRACT".equals(row.get("adjustmentCode")))
                .mapToInt(row -> Integer.parseInt(row.get("sequence")))
                .max()
                .orElseThrow();
        assertThat(steps.stream()
                        .mapToInt(row -> Integer.parseInt(row.get("sequence")))
                        .max()
                        .orElseThrow())
                .as("FLEET_CONTRACT must have the highest sequence in the matrix")
                .isEqualTo(contractSequence);
    }

    private static void assertCategory(String category) {
        if (!category.isBlank()) {
            assertThat(category).as("operationCategory").isIn(CATEGORIES);
        }
    }

    /** Quote-aware enough for these files: a field may be quoted and may contain commas. */
    private static List<Map<String, String>> readRows(Path file) throws IOException {
        List<List<String>> rows = parse(Files.readString(file, StandardCharsets.UTF_8));
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
