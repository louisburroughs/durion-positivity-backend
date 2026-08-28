package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the alpha site-defaults fixture agrees with the storage topology it points into
 * (issue #1557).
 *
 * <p>Every environment the seed pipeline builds had null site defaults, so
 * {@code StagingLocationResolver} fell through to a hardcoded
 * {@code 00000000-0000-0000-0000-000000000002} that is not a row in any {@code storage_location}
 * table. Putaway refuses any receipt not booked at the resolved staging location, so the staging
 * bins the pipeline creates were unreachable: a receipt booked at the real Staging Floor — the one
 * a human or a UI would pick — was refused with RECEIPT_NOT_STAGED.
 *
 * <p>The fixture that fixes it names its locations rather than carrying ids, which means a rename
 * on either side breaks it silently: the driver would warn and skip, the defaults would stay null,
 * and putaway would be broken again with nothing failing. Both files are read here so that goes
 * wrong at build time instead.
 */
@SuppressWarnings({"java:S100", "java:S1192"})
class AlphaFixtureSiteDefaultsTest {

    private static final Path FIXTURE_ROOT = Path.of(System.getProperty("user.dir"))
            .resolve("../scripts/fixtures/seed/alpha")
            .normalize();

    private static final Path SITE_DEFAULTS = FIXTURE_ROOT.resolve("location/site-defaults.csv");
    private static final Path STORAGE_LOCATIONS = FIXTURE_ROOT.resolve("location/storage-locations.csv");
    private static final Path LOCATIONS = FIXTURE_ROOT.resolve("location/locations.csv");

    @Test
    @DisplayName("every site in the roster declares its defaults")
    void everySiteDeclaresDefaults() throws IOException {
        List<String> siteCodes =
                readRows(LOCATIONS).stream().map(row -> row.get("code")).toList();
        List<String> declared = readRows(SITE_DEFAULTS).stream()
                .map(row -> row.get("locationCode"))
                .toList();

        // A site with no defaults resolves through the hardcoded fallback, so leaving one out
        // silently re-creates the defect for that site alone.
        assertThat(declared).as("sites missing from site-defaults.csv").containsExactlyInAnyOrderElementsOf(siteCodes);
    }

    @Test
    @DisplayName("each named default exists at its own site")
    void namedDefaultsExistAtTheirSite() throws IOException {
        List<Map<String, String>> storage = readRows(STORAGE_LOCATIONS);

        for (Map<String, String> row : readRows(SITE_DEFAULTS)) {
            String code = row.get("locationCode");
            List<String> namesAtSite = storage.stream()
                    .filter(storageRow -> code.equals(storageRow.get("locationCode")))
                    .map(storageRow -> storageRow.get("name"))
                    .toList();

            assertThat(namesAtSite)
                    .as("%s: staging location '%s' is not in storage-locations.csv", code, row.get("stagingName"))
                    .contains(row.get("stagingName"));
            assertThat(namesAtSite)
                    .as("%s: quarantine location '%s' is not in storage-locations.csv", code, row.get("quarantineName"))
                    .contains(row.get("quarantineName"));
        }
    }

    @Test
    @DisplayName("staging and quarantine are different locations")
    void defaultsDiffer() throws IOException {
        // SiteDefaultsServiceImpl rejects equal ids with DEFAULT_LOCATION_ROLE_CONFLICT, so a row
        // naming one location twice fails the whole site at run time.
        for (Map<String, String> row : readRows(SITE_DEFAULTS)) {
            assertThat(row.get("stagingName"))
                    .as("%s names the same location for both roles", row.get("locationCode"))
                    .isNotEqualTo(row.get("quarantineName"));
        }
    }

    @Test
    @DisplayName("each default is declared for the role it is being given")
    void defaultsCarryTheMatchingStorageCategory() throws IOException {
        Map<String, String> categoryByKey = new LinkedHashMap<>();
        for (Map<String, String> row : readRows(STORAGE_LOCATIONS)) {
            categoryByKey.put(row.get("locationCode") + '/' + row.get("name"), row.get("storageCategoryCode"));
        }

        for (Map<String, String> row : readRows(SITE_DEFAULTS)) {
            String code = row.get("locationCode");
            // The compatibility matrix reads the capability, not the name: a staging default whose
            // category is not STAGING would accept putaway lines it is meant to be the source of.
            assertThat(categoryByKey.get(code + '/' + row.get("stagingName")))
                    .as("%s: staging default '%s' is not declared STAGING", code, row.get("stagingName"))
                    .isEqualTo("STAGING");
            assertThat(categoryByKey.get(code + '/' + row.get("quarantineName")))
                    .as("%s: quarantine default '%s' is not declared QUARANTINE", code, row.get("quarantineName"))
                    .isEqualTo("QUARANTINE");
        }
    }

    @Test
    @DisplayName("defaults point at active locations")
    void defaultsAreActive() throws IOException {
        Map<String, String> statusByKey = new LinkedHashMap<>();
        for (Map<String, String> row : readRows(STORAGE_LOCATIONS)) {
            statusByKey.put(row.get("locationCode") + '/' + row.get("name"), row.get("status"));
        }

        for (Map<String, String> row : readRows(SITE_DEFAULTS)) {
            String code = row.get("locationCode");
            for (String name : List.of(row.get("stagingName"), row.get("quarantineName"))) {
                String status = statusByKey.get(code + '/' + name);
                // Blank means the fixture left it to the service default, which is ACTIVE.
                assertThat(status == null || status.isBlank() || "ACTIVE".equals(status))
                        .as("%s: default '%s' is %s, not active", code, name, status)
                        .isTrue();
            }
        }
    }

    private static List<Map<String, String>> readRows(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).as("fixture %s is empty", file).isNotEmpty();

        List<String> headers = split(lines.getFirst());
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            List<String> values = split(line);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                row.put(headers.get(i), i < values.size() ? values.get(i) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<String> split(String line) {
        return java.util.Arrays.stream(line.split(",", -1)).map(String::trim).toList();
    }
}
