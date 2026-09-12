package com.positivity.bulkloader.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The alpha vehicle fixture must carry the fields the loader requires, derived the way the file
 * says it derives them.
 *
 * <h2>What this defends</h2>
 *
 * {@code unitNumber} and {@code description} were blank in all 329 rows while {@link
 * VehicleLoaderStrategy} requires both, so the pack could never load and the alpha seed simply had
 * no vehicles. {@link AlphaFixtureHeadersMapTest} reads only headers, so it stayed green throughout
 * — a column can be present and empty in every row without that test noticing.
 *
 * <p>The derivation encodes the distinction the file itself makes. A fleet numbers its own units,
 * so an {@code ORGANIZATION} row takes a per-owner sequence; an {@code INDIVIDUAL} has no fleet
 * numbering, so its row falls back to the tail of the VIN, which is unique and already on the row.
 * {@code unit_number} is NOT NULL with a non-unique index
 * ({@code V1__baseline_vehicle_inventory.sql:79,122}), so the same {@code 001} appearing in two
 * different fleets is correct and is asserted as such rather than being treated as a collision.
 *
 * <p>Reading the real file means a reseed cannot silently regress to blanks.
 */
@DisplayName("alpha vehicle fixture derivation")
class VehicleFixtureDerivationTest {

    private static final Path VEHICLES = Path.of(System.getProperty("user.dir"))
            .resolve("../scripts/fixtures/seed/alpha/vehicle/vehicles.csv")
            .normalize();

    private static List<Map<String, String>> rows() throws IOException {
        List<String> lines = Files.readAllLines(VEHICLES, StandardCharsets.UTF_8);
        List<String> headers = split(lines.getFirst());
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            List<String> values = split(line);
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                row.put(headers.get(i), i < values.size() ? values.get(i) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    /** Minimal RFC-4180 split: the descriptions this file carries contain commas inside quotes. */
    private static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out;
    }

    @Test
    void everyRowCarriesTheFieldsTheLoaderRequires() throws IOException {
        List<Map<String, String>> rows = rows();
        assertThat(rows).as("the fixture should not be empty").isNotEmpty();

        assertThat(rows)
                .as("VehicleLoaderStrategy requires unitNumber on every row")
                .allSatisfy(r -> assertThat(r.get("unitNumber")).isNotBlank());
        assertThat(rows)
                .as("VehicleLoaderStrategy requires description on every row")
                .allSatisfy(r -> assertThat(r.get("description")).isNotBlank());
    }

    @Test
    void individualsTakeTheTailOfTheirVin() throws IOException {
        List<Map<String, String>> individuals = rows().stream()
                .filter(r -> "INDIVIDUAL".equals(r.get("ownerType")))
                .toList();

        assertThat(individuals)
                .as("the fixture should still contain individual owners")
                .isNotEmpty();
        assertThat(individuals).allSatisfy(r -> {
            String vin = r.get("vin");
            assertThat(r.get("unitNumber"))
                    .as("an individual has no fleet numbering, so the VIN tail identifies the unit")
                    .isEqualTo(vin.substring(vin.length() - 6));
        });
    }

    @Test
    void eachFleetNumbersItsOwnUnitsFromOne() throws IOException {
        Map<String, List<String>> byOwner = new HashMap<>();
        for (Map<String, String> r : rows()) {
            if ("ORGANIZATION".equals(r.get("ownerType"))) {
                byOwner.computeIfAbsent(r.get("ownerName"), k -> new ArrayList<>())
                        .add(r.get("unitNumber"));
            }
        }

        assertThat(byOwner).as("the fixture should still contain fleet owners").isNotEmpty();
        byOwner.forEach((owner, units) -> {
            List<String> expected = java.util.stream.IntStream.rangeClosed(1, units.size())
                    .mapToObj("%03d"::formatted)
                    .toList();
            assertThat(units)
                    .as("%s numbers its units from 001 with no gaps or repeats", owner)
                    .containsExactlyElementsOf(expected);
        });
    }

    @Test
    void theSameUnitNumberMayAppearInDifferentFleets() throws IOException {
        // unit_number is NOT NULL with a plain index, not a unique one: two fleets both having a
        // unit 001 is how fleets actually number, and must not be "fixed" into global uniqueness.
        List<String> firstUnits = rows().stream()
                .filter(r -> "ORGANIZATION".equals(r.get("ownerType")))
                .filter(r -> "001".equals(r.get("unitNumber")))
                .map(r -> r.get("ownerName"))
                .distinct()
                .toList();

        assertThat(firstUnits).as("more than one fleet should carry a unit 001").hasSizeGreaterThan(1);
    }

    @Test
    void descriptionsAreBuiltFromWhatTheRowAlreadyStates() throws IOException {
        assertThat(rows()).allSatisfy(r -> {
            StringBuilder expected = new StringBuilder()
                    .append(r.get("year"))
                    .append(' ')
                    .append(r.get("make"))
                    .append(' ')
                    .append(r.get("model"));
            if (!r.get("trim").isBlank()) {
                expected.append(' ').append(r.get("trim"));
            }
            assertThat(r.get("description")).isEqualTo(expected.toString());
        });
    }
}
