package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *
 * <p>Issue #1536 gave {@code subcategory} a mandatory parent {@code category_id}, which makes a product's
 * category a function of its subcategory. This class is the home for that seed/fixture consistency check
 * too: the declared parents, the 500 seeded product rows, and the 500 alpha CSV rows must all agree, or the
 * seed itself would produce exactly the contradictory pair the issue removes.
 */
class AlphaFixtureCategoryNamesResolveTest {

    /** Matches {@code ('<uuid>', 'Name'} in the seed's VALUES tuples. */
    private static final Pattern SEED_ROW = Pattern.compile("'[0-9a-fA-F-]{36}',\\s*'([^']*)'");

    /** Matches {@code ('<id>', 'Name'} and captures both, for the parent-less category tuple. */
    private static final Pattern CATEGORY_ROW = Pattern.compile("'([0-9a-fA-F-]{36})',\\s*'([^']*)'");

    /** Matches {@code ('<id>', 'Name', '<category_id>'} — the subcategory tuple shape since #1536. */
    private static final Pattern SEED_ROW_WITH_PARENT =
            Pattern.compile("'([0-9a-fA-F-]{36})',\\s*'([^']*)',\\s*'([0-9a-fA-F-]{36})'");

    /** Matches the trailing {@code '<category_id>', '<subcategory_id>', NOW()} of a seeded product tuple. */
    private static final Pattern PRODUCT_PAIR =
            Pattern.compile("'([0-9a-fA-F-]{36})',\\s*'([0-9a-fA-F-]{36})',\\s*NOW\\(\\)");

    private static final Path MODULE_DIR = Path.of(System.getProperty("user.dir"));
    private static final Path SEED_SQL =
            MODULE_DIR.resolve("src/main/resources/db/migration/R__seed_reference_catalog.sql");
    private static final Path PRODUCTS_CSV = MODULE_DIR
            .resolve("../scripts/fixtures/seed/alpha/catalog/products.csv")
            .normalize();
    private static final Path PRODUCTS_SEED_SQL =
            MODULE_DIR.resolve("src/main/resources/db/migration/R__seed_reference_catalog_2_products.sql");

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

    /** Extracts the raw {@code INSERT INTO <table>} VALUES block from the seed. */
    private static String insertBlock(String seed, String table, String nextTableMarker) {
        int start = seed.indexOf("INSERT INTO " + table + " (id, name");
        assertThat(start).as("seed must contain an INSERT INTO %s", table).isNotNegative();
        int end = nextTableMarker == null ? -1 : seed.indexOf(nextTableMarker, start);
        return end < 0 ? seed.substring(start) : seed.substring(start, end);
    }

    /** Reads the seeded category ids keyed by lowercased name. */
    private static Map<String, String> seededCategoryIdsByName(String seed) {
        Map<String, String> byName = new LinkedHashMap<>();
        Matcher matcher = CATEGORY_ROW.matcher(insertBlock(seed, "category", "INSERT INTO subcategory"));
        while (matcher.find()) {
            byName.put(matcher.group(2).trim().toLowerCase(Locale.ROOT), matcher.group(1));
        }
        return byName;
    }

    /** Reads {@code subcategoryId -> categoryId} and {@code lowercased name -> subcategoryId} from the seed. */
    private static Map<String, String> seededSubcategoryParents(String seed) {
        Map<String, String> parents = new LinkedHashMap<>();
        Matcher matcher = SEED_ROW_WITH_PARENT.matcher(insertBlock(seed, "subcategory", null));
        while (matcher.find()) {
            parents.put(matcher.group(1), matcher.group(3));
        }
        return parents;
    }

    private static Map<String, String> seededSubcategoryIdsByName(String seed) {
        Map<String, String> byName = new LinkedHashMap<>();
        Matcher matcher = SEED_ROW_WITH_PARENT.matcher(insertBlock(seed, "subcategory", null));
        while (matcher.find()) {
            byName.put(matcher.group(2).trim().toLowerCase(Locale.ROOT), matcher.group(1));
        }
        return byName;
    }

    @Test
    void everySeededSubcategoryDeclaresAParentAmongTheSeededCategories() throws IOException {
        String seed = readSeed();
        Map<String, String> parents = seededSubcategoryParents(seed);
        Set<String> categoryIds =
                new LinkedHashSet<>(seededCategoryIdsByName(seed).values());

        assertThat(categoryIds).as("seeded category ids").hasSize(12);
        assertThat(parents)
                .as("every seeded subcategory must declare a parent category_id — the column is NOT NULL"
                        + " since #1536, so a tuple without one would fail the seed outright")
                .hasSize(40);

        Set<String> danglingParents = new TreeSet<>();
        parents.forEach((subcategoryId, categoryId) -> {
            if (!categoryIds.contains(categoryId)) {
                danglingParents.add(subcategoryId + " -> " + categoryId);
            }
        });
        assertThat(danglingParents)
                .as("subcategory rows whose category_id is not one of the 12 seeded categories — these would"
                        + " violate fk_subcategory_category")
                .isEmpty();
    }

    @Test
    void seededProductPairsAgreeWithTheDeclaredSubcategoryParents() throws IOException {
        Map<String, String> parents = seededSubcategoryParents(readSeed());
        String products = Files.readString(PRODUCTS_SEED_SQL, StandardCharsets.UTF_8);

        Set<String> mismatches = new TreeSet<>();
        Set<String> unknownSubcategories = new TreeSet<>();
        int pairs = 0;
        Matcher matcher = PRODUCT_PAIR.matcher(products);
        while (matcher.find()) {
            pairs++;
            String categoryId = matcher.group(1);
            String subcategoryId = matcher.group(2);
            String declaredParent = parents.get(subcategoryId);
            if (declaredParent == null) {
                unknownSubcategories.add(subcategoryId);
            } else if (!declaredParent.equals(categoryId)) {
                mismatches.add(subcategoryId + " declares parent " + declaredParent + " but a product pairs it"
                        + " with " + categoryId);
            }
        }

        assertThat(pairs)
                .as("seeded product (category_id, subcategory_id) pairs")
                .isEqualTo(500);
        assertThat(unknownSubcategories)
                .as("product rows referencing a subcategory that the reference seed does not define")
                .isEmpty();
        assertThat(mismatches)
                .as("seeded products whose category contradicts their subcategory's declared parent — after"
                        + " #1536 these rows are unrepresentable, and V16 Stage D would silently rewrite them")
                .isEmpty();
    }

    @Test
    void alphaFixtureCsvPairsAgreeWithTheDeclaredSubcategoryParents() throws IOException {
        String seed = readSeed();
        Map<String, String> categoryIdsByName = seededCategoryIdsByName(seed);
        Map<String, String> subcategoryIdsByName = seededSubcategoryIdsByName(seed);
        Map<String, String> parents = seededSubcategoryParents(seed);

        List<String> lines = Files.readAllLines(PRODUCTS_CSV, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .toList();
        List<String> header = splitCsvLine(lines.get(0));
        int categoryColumn = header.indexOf("categoryName");
        int subcategoryColumn = header.indexOf("subcategoryName");

        Set<String> mismatches = new TreeSet<>();
        int checkedPairs = 0;

        for (String line : lines.subList(1, lines.size())) {
            List<String> cells = splitCsvLine(line);
            String categoryName = cells.get(categoryColumn).trim();
            String subcategoryName = cells.get(subcategoryColumn).trim();
            // A row that omits either name is legal: the category is derived from the subcategory, and a
            // product with neither is simply unclassified. Only a supplied pair can contradict itself.
            if (categoryName.isEmpty() || subcategoryName.isEmpty()) {
                continue;
            }
            String categoryId = categoryIdsByName.get(categoryName.toLowerCase(Locale.ROOT));
            String subcategoryId = subcategoryIdsByName.get(subcategoryName.toLowerCase(Locale.ROOT));
            if (categoryId == null || subcategoryId == null) {
                // Unresolvable names are the other test's subject; do not double-report them here.
                continue;
            }
            checkedPairs++;
            String declaredParent = parents.get(subcategoryId);
            if (!declaredParent.equals(categoryId)) {
                mismatches.add(subcategoryName + " (parent " + declaredParent + ") paired with " + categoryName + " ("
                        + categoryId + ")");
            }
        }

        assertThat(checkedPairs).as("alpha fixture rows carrying both names").isEqualTo(500);
        assertThat(mismatches)
                .as("products.csv rows whose categoryName contradicts the subcategoryName's declared parent —"
                        + " these rows would now fail bulk ingest with CATALOG_INGEST_FAILED")
                .isEmpty();
    }
}
