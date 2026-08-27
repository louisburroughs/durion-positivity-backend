package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Guards the alpha catalog fixture pack against the behaviour change introduced for issue #1514.
 *
 * <p>{@code categoryName} / {@code subcategoryName} used to be silently ignored on the bulk-ingest path, so an
 * unrecognised name in {@code products.csv} was harmless. Now an unknown name fails that row
 * ({@code CATALOG_INGEST_FAILED}), which means a fixture name that does not exist in the Flyway reference seed
 * would turn a previously "successful" pipeline load into a per-row failure. This test fails loudly at build
 * time instead, and names the offending values.
 *
 * <p>It reads the real seed and the real CSV rather than a copy, so it cannot drift out of sync with either.
 */
class AlphaFixtureCategoryNamesResolveTest {

    /** Matches {@code ('<uuid>', 'Name'} in the seed's VALUES tuples. */
    private static final Pattern SEED_ROW = Pattern.compile("'[0-9a-fA-F-]{36}',\\s*'([^']*)'");

    private static final Path MODULE_DIR = Path.of(System.getProperty("user.dir"));
    private static final Path SEED_SQL =
            MODULE_DIR.resolve("src/main/resources/db/migration/R__seed_reference_catalog.sql");
    private static final Path PRODUCTS_CSV = MODULE_DIR
            .resolve("../scripts/fixtures/seed/alpha/catalog/products.csv")
            .normalize();

    private static String readSeed() throws IOException {
        return Files.readString(SEED_SQL, StandardCharsets.UTF_8);
    }

    /** Extracts the names from a single {@code INSERT INTO <table>} statement in the seed. */
    private static Set<String> seededNames(String seed, String table, String nextTableMarker) {
        int start = seed.indexOf("INSERT INTO " + table + " (id, name");
        assertThat(start).as("seed must contain an INSERT INTO %s", table).isNotNegative();
        int end = nextTableMarker == null ? seed.length() : seed.indexOf(nextTableMarker, start);
        String block = end < 0 ? seed.substring(start) : seed.substring(start, end);

        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = SEED_ROW.matcher(block);
        while (matcher.find()) {
            names.add(matcher.group(1).trim());
        }
        return names;
    }

    /** Minimal RFC4180-adequate split; the fixture uses no embedded quotes in these two columns. */
    private static List<String> splitCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells;
    }

    private static Set<String> lowercased(Set<String> names) {
        return names.stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void everyFixtureCategoryAndSubcategoryNameResolvesAgainstTheReferenceSeed() throws IOException {
        String seed = readSeed();
        Set<String> categories = seededNames(seed, "category", "INSERT INTO subcategory");
        Set<String> subcategories = seededNames(seed, "subcategory", null);

        assertThat(categories).as("seeded categories").hasSize(12);
        assertThat(subcategories).as("seeded subcategories").hasSize(40);

        // The resolver trims and matches case-insensitively, so compare on the same footing.
        Set<String> categoryKeys = lowercased(categories);
        Set<String> subcategoryKeys = lowercased(subcategories);

        List<String> lines = Files.readAllLines(PRODUCTS_CSV, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .toList();
        List<String> header = splitCsvLine(lines.get(0));
        int categoryColumn = header.indexOf("categoryName");
        int subcategoryColumn = header.indexOf("subcategoryName");
        assertThat(categoryColumn)
                .as("products.csv must carry a categoryName column")
                .isNotNegative();
        assertThat(subcategoryColumn)
                .as("products.csv must carry a subcategoryName column")
                .isNotNegative();

        Set<String> unresolvedCategories = new TreeSet<>();
        Set<String> unresolvedSubcategories = new TreeSet<>();
        int dataRows = 0;

        for (String line : lines.subList(1, lines.size())) {
            List<String> cells = splitCsvLine(line);
            dataRows++;
            String categoryName = cells.get(categoryColumn).trim();
            String subcategoryName = cells.get(subcategoryColumn).trim();
            // A blank name is "unclassified", which resolves to null without error — not a mismatch.
            if (!categoryName.isEmpty() && !categoryKeys.contains(categoryName.toLowerCase(Locale.ROOT))) {
                unresolvedCategories.add(categoryName);
            }
            if (!subcategoryName.isEmpty() && !subcategoryKeys.contains(subcategoryName.toLowerCase(Locale.ROOT))) {
                unresolvedSubcategories.add(subcategoryName);
            }
        }

        assertThat(dataRows).as("alpha fixture product rows").isEqualTo(500);
        assertThat(unresolvedCategories)
                .as("products.csv categoryName values with no matching seeded category — these rows would now"
                        + " fail bulk ingest with CATALOG_INGEST_FAILED")
                .isEmpty();
        assertThat(unresolvedSubcategories)
                .as("products.csv subcategoryName values with no matching seeded subcategory — these rows would"
                        + " now fail bulk ingest with CATALOG_INGEST_FAILED")
                .isEmpty();
    }

    @Test
    void batteriesSubcategoryIsPresentInBothSeedAndFixture() throws IOException {
        // The Batteries -> BATTERY_RACK containment case in #1514 depends on this subcategory
        // actually reaching products, so pin it explicitly rather than relying on the bulk assertion.
        assertThat(seededNames(readSeed(), "subcategory", null)).contains("Batteries");

        List<String> lines = Files.readAllLines(PRODUCTS_CSV, StandardCharsets.UTF_8);
        int subcategoryColumn = splitCsvLine(lines.get(0)).indexOf("subcategoryName");
        long batteryRows = lines.subList(1, lines.size()).stream()
                .filter(line -> !line.isBlank())
                .filter(line -> "Batteries"
                        .equalsIgnoreCase(
                                splitCsvLine(line).get(subcategoryColumn).trim()))
                .count();

        assertThat(batteryRows).as("fixture rows in the Batteries subcategory").isPositive();
    }
}
