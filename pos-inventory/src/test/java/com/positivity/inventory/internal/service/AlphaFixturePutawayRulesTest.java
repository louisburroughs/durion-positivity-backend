package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the alpha putaway-rule fixture is internally consistent (issue #1514).
 *
 * <p>The fixture is loaded through the CRUD endpoint by {@code scripts/seed-alpha.py}, which resolves
 * every reference by business key at run time. That makes the pack robust against re-seeding but it
 * cannot tell whether a rule is <em>sensible</em>: a rule sending {@code Fluids & Chemicals} to a tire
 * rack is accepted by the endpoint (nothing there consults the compatibility matrix), loads cleanly,
 * and only fails later — once for every received line, as a
 * {@code LOCATION_NOT_VALID_FOR_SKU} refusal after the putaway task has already been generated. This
 * test moves that failure to build time.
 *
 * <p>It reads the real files rather than copies, so it cannot drift from any of them:
 *
 * <ul>
 *   <li>the fixture, {@code scripts/fixtures/seed/alpha/inventory/putaway-rules.csv};
 *   <li>the catalog taxonomy, {@code pos-catalog R__seed_reference_catalog.sql} — the names the
 *       fixture keys on must exist there, because that is what the driver resolves against;
 *   <li>the storage topology and its capabilities, {@code location/storage-locations.csv} — the
 *       fixture that creates the destinations these rules point at;
 *   <li>the compatibility matrix, {@code V43__storage_compatibility.sql} — the authority on which
 *       storage class may hold which catalog class;
 *   <li>the product fixture, {@code catalog/products.csv} — the driver resolves a category name to
 *       its id through an exemplar product, so a name with no product is unresolvable in practice
 *       even though it exists in the taxonomy.
 * </ul>
 *
 * <p>The acceptance check mirrors {@link StorageCompatibilityEvaluator}: source-only classes refuse
 * everything, an item whose every accepted class demands containment carries that requirement itself,
 * {@code GENERAL} is otherwise permissive, subcategory rows replace their parent's rather than
 * supplementing them, and a matched row demanding containment needs a destination that declares it.
 */
class AlphaFixturePutawayRulesTest {

    private static final Path MODULE_DIR = Path.of(System.getProperty("user.dir"));
    private static final Path FIXTURE_ROOT =
            MODULE_DIR.resolve("../scripts/fixtures/seed/alpha").normalize();
    private static final Path RULES_CSV = FIXTURE_ROOT.resolve("inventory/putaway-rules.csv");
    private static final Path STORAGE_CSV = FIXTURE_ROOT.resolve("location/storage-locations.csv");
    private static final Path PRODUCTS_CSV = FIXTURE_ROOT.resolve("catalog/products.csv");
    private static final Path CATALOG_SEED = MODULE_DIR
            .resolve("../pos-catalog/src/main/resources/db/migration/R__seed_reference_catalog.sql")
            .normalize();
    private static final Path MATRIX_SQL =
            MODULE_DIR.resolve("src/main/resources/db/migration/V43__storage_compatibility.sql");

    /** {@code ('<uuid>', 'Name'} in a taxonomy seed VALUES tuple. */
    private static final Pattern TAXONOMY_ROW = Pattern.compile("'([0-9a-fA-F-]{36})',\\s*'([^']*)'");

    /** One {@code storage_compatibility} VALUES tuple. */
    private static final Pattern MATRIX_ROW = Pattern.compile("'[0-9a-fA-F-]{36}',\\s*'(CATEGORY|SUBCATEGORY)',"
            + "\\s*'([0-9a-fA-F-]{36})',\\s*'([A-Z_]+)',\\s*(TRUE|FALSE)");

    /** Putaway sources; they are destinations for nothing. */
    private static final Set<String> SOURCE_ONLY = Set.of("STAGING", "QUARANTINE");

    private static final String GENERAL = "GENERAL";

    /** {@code PutawayDestinationStrategy}, as the fixture's CSV spells it. */
    private static final Set<String> STRATEGIES = Set.of("FIXED", "LAST_USED", "CLOSEST_AVAILABLE");

    // ---------------------------------------------------------------------------------------------
    // Fixture / seed models
    // ---------------------------------------------------------------------------------------------

    /** One row of {@code putaway-rules.csv}. */
    private record Rule(
            int priority,
            String matchType,
            String matchName,
            String locationCode,
            String destinationName,
            String destinationStrategy,
            boolean enabled) {

        String label() {
            return matchName.isEmpty() ? matchType : matchType + " '" + matchName + "'";
        }
    }

    /** One row of {@code storage-locations.csv}: what the location is fit to hold. */
    private record StorageLocation(
            String locationCode, String name, String parentName, String storageCategoryCode, boolean containment) {}

    /** One accepted (storage class, containment-required) pair from the matrix. */
    private record Accepted(String storageCategoryCode, boolean requiresContainment) {}

    // ---------------------------------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------------------------------

    /** Minimal RFC4180-adequate split; these fixtures embed no quotes or newlines in a cell. */
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

    private static List<Map<String, String>> readCsv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .toList();
        List<String> header =
                splitCsvLine(lines.get(0)).stream().map(String::trim).toList();
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            List<String> cells = splitCsvLine(line);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                row.put(header.get(i), i < cells.size() ? cells.get(i).trim() : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<Rule> rules() throws IOException {
        List<Rule> rules = new ArrayList<>();
        for (Map<String, String> row : readCsv(RULES_CSV)) {
            rules.add(new Rule(
                    Integer.parseInt(row.get("priority")),
                    row.get("matchType"),
                    row.get("matchName"),
                    row.get("locationCode"),
                    row.get("destinationName"),
                    row.get("destinationStrategy"),
                    Boolean.parseBoolean(row.get("isEnabled"))));
        }
        return rules;
    }

    /** (locationCode, name) -> the storage location the location pack creates. */
    private static Map<LocationKey, StorageLocation> storageLocations() throws IOException {
        Map<LocationKey, StorageLocation> byKey = new LinkedHashMap<>();
        for (Map<String, String> row : readCsv(STORAGE_CSV)) {
            StorageLocation location = new StorageLocation(
                    row.get("locationCode"),
                    row.get("name"),
                    row.get("parentName"),
                    // An empty capability is what the service reports back as GENERAL.
                    row.get("storageCategoryCode").isEmpty()
                            ? GENERAL
                            : row.get("storageCategoryCode").toUpperCase(Locale.ROOT),
                    Boolean.parseBoolean(row.get("hazardContainment")));
            byKey.put(key(location.locationCode(), location.name()), location);
        }
        return byKey;
    }

    /**
     * Map key for a storage location. A record rather than a concatenated string: location names
     * contain spaces ("Bin A-01", "Parts Shelf A"), so no separator character is obviously safe, and
     * an ambiguous key would silently make two different locations collide.
     */
    private record LocationKey(String locationCode, String name) {}

    private static LocationKey key(String locationCode, String name) {
        return new LocationKey(locationCode, name);
    }

    /** Lowercased taxonomy name -> id, for one {@code INSERT INTO <table> (id, name} block. */
    private static Map<String, String> taxonomy(String table, String nextTableMarker) throws IOException {
        // Deliberately the real pos-catalog seed, not a copy: the fixture keys on these names and a
        // copy would drift. Named explicitly so a rename over in pos-catalog fails here with a
        // sentence rather than a bare NoSuchFileException in an unrelated module.
        assertThat(CATALOG_SEED)
                .as("this pos-inventory test reads the pos-catalog taxonomy seed to resolve the names in"
                        + " putaway-rules.csv; update the path here if that file moves")
                .exists();
        String seed = Files.readString(CATALOG_SEED, StandardCharsets.UTF_8);
        int start = seed.indexOf("INSERT INTO " + table + " (id, name");
        assertThat(start)
                .as("catalog seed must contain an INSERT INTO %s", table)
                .isNotNegative();
        int end = nextTableMarker == null ? -1 : seed.indexOf(nextTableMarker, start);
        String block = end < 0 ? seed.substring(start) : seed.substring(start, end);

        Map<String, String> byName = new TreeMap<>();
        Matcher matcher = TAXONOMY_ROW.matcher(block);
        while (matcher.find()) {
            byName.put(matcher.group(2).trim().toLowerCase(Locale.ROOT), matcher.group(1));
        }
        return byName;
    }

    /** (match level, catalog ref id) -> the classes the matrix accepts for it. */
    private static Map<String, Set<Accepted>> matrix() throws IOException {
        Map<String, Set<Accepted>> byRef = new LinkedHashMap<>();
        Matcher matcher = MATRIX_ROW.matcher(Files.readString(MATRIX_SQL, StandardCharsets.UTF_8));
        while (matcher.find()) {
            byRef.computeIfAbsent(matcher.group(1) + "|" + matcher.group(2), k -> new LinkedHashSet<>())
                    .add(new Accepted(matcher.group(3), "TRUE".equals(matcher.group(4))));
        }
        return byRef;
    }

    // ---------------------------------------------------------------------------------------------
    // The evaluator's decision, replayed over fixture data
    // ---------------------------------------------------------------------------------------------

    /**
     * Why {@code destination} would refuse an item of {@code accepted}'s class, or null when it
     * accepts. {@code accepted} is empty for an unclassified item (the {@code ANY} tier).
     *
     * <p>Mirrors {@link StorageCompatibilityEvaluator#evaluate} step for step. Kept as a
     * reimplementation rather than a call into the evaluator because the evaluator needs a repository
     * and a Spring context, and the thing under test here is the fixture, not the evaluator — whose
     * own behaviour {@code StorageCompatibilityEvaluatorTest} covers. Any divergence between the two
     * shows up as this test passing while a reseed still refuses a line, so the two must be changed
     * together.
     */
    private static String refusalReason(StorageLocation destination, Set<Accepted> accepted) {
        String code = destination.storageCategoryCode();
        if (SOURCE_ONLY.contains(code)) {
            return code + " is a putaway source, not a destination";
        }
        boolean itemCarriesContainment =
                !accepted.isEmpty() && accepted.stream().allMatch(Accepted::requiresContainment);
        if (itemCarriesContainment && !destination.containment()) {
            return "the class may only be stored with hazard containment, which " + destination.name()
                    + " does not declare";
        }
        if (GENERAL.equals(code)) {
            return null;
        }
        if (accepted.isEmpty()) {
            return "an unclassified item is accepted only by GENERAL, and this destination is " + code;
        }
        Accepted match = accepted.stream()
                .filter(row -> row.storageCategoryCode().equalsIgnoreCase(code))
                .findFirst()
                .orElse(null);
        if (match == null) {
            return code + " does not accept the class (accepted: "
                    + new TreeSet<>(
                            accepted.stream().map(Accepted::storageCategoryCode).toList())
                    + ")";
        }
        if (match.requiresContainment() && !destination.containment()) {
            return code + " requires hazard containment for the class, which " + destination.name()
                    + " does not declare";
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("#1514 - the fixture covers all 12 categories, the matrix's overrides and one ANY rule")
    void coversEveryTierItMustCover() throws IOException {
        List<Rule> rules = rules();
        Map<String, String> categories = taxonomy("category", "INSERT INTO subcategory");
        Map<String, String> subcategories = taxonomy("subcategory", null);
        Map<String, Set<Accepted>> matrix = matrix();

        assertThat(rules).as("putaway rule rows").hasSize(16);

        Set<String> ruledCategoryIds = new TreeSet<>();
        Set<String> ruledSubcategoryIds = new TreeSet<>();
        for (Rule rule : rules) {
            // An unresolvable name is reported by everyReferenceResolves, which names the offending
            // value; collecting a null here would only turn that diagnostic into a bare NPE.
            String name = rule.matchName().toLowerCase(Locale.ROOT);
            if ("CATEGORY".equals(rule.matchType()) && categories.containsKey(name)) {
                ruledCategoryIds.add(categories.get(name));
            } else if ("SUBCATEGORY".equals(rule.matchType()) && subcategories.containsKey(name)) {
                ruledSubcategoryIds.add(subcategories.get(name));
            }
        }

        assertThat(ruledCategoryIds)
                .as("every seeded catalog category needs a CATEGORY rule; without one its lines fall through to"
                        + " the ANY rule, which only a GENERAL destination accepts")
                .containsExactlyInAnyOrderElementsOf(new TreeSet<>(categories.values()));

        // A subcategory the matrix overrides but the fixture does not rule on is the failure mode
        // SUBCATEGORY precedence exists to prevent: its lines route by the parent category's rule,
        // to a destination the override rejects. Batteries are the case that matters — Electrical
        // System sends them to a small-parts bin, and the matrix then refuses acid without a bund.
        Set<String> overriddenSubcategoryIds = matrix.keySet().stream()
                .filter(k -> k.startsWith("SUBCATEGORY|"))
                .map(k -> k.substring("SUBCATEGORY|".length()))
                .collect(TreeSet::new, Set::add, Set::addAll);
        assertThat(ruledSubcategoryIds)
                .as("every subcategory the matrix overrides needs its own SUBCATEGORY rule, or its lines route by"
                        + " the parent category's rule to a destination the override refuses")
                .containsAll(overriddenSubcategoryIds);

        List<Rule> anyRules =
                rules.stream().filter(rule -> "ANY".equals(rule.matchType())).toList();
        assertThat(anyRules)
                .as("exactly one ANY rule: it is the terminal fallback that stops a receipt for a brand-new"
                        + " uncategorised SKU dead-ending, and the endpoint refuses a second enabled one with 409")
                .hasSize(1);
        assertThat(anyRules.get(0).enabled()).as("the ANY rule must be enabled").isTrue();
        assertThat(anyRules.get(0).matchName())
                .as("an ANY rule must carry no matchValue")
                .isEmpty();
    }

    @Test
    @DisplayName("#1514 - every category, subcategory and destination in the fixture resolves")
    void everyReferenceResolves() throws IOException {
        Map<String, String> categories = taxonomy("category", "INSERT INTO subcategory");
        Map<String, String> subcategories = taxonomy("subcategory", null);
        Map<LocationKey, StorageLocation> storage = storageLocations();

        Set<String> unresolvedClasses = new TreeSet<>();
        Set<String> unresolvedDestinations = new TreeSet<>();
        Set<String> badMatchTypes = new TreeSet<>();
        Set<String> badStrategies = new TreeSet<>();

        for (Rule rule : rules()) {
            switch (rule.matchType()) {
                case "CATEGORY" -> {
                    if (!categories.containsKey(rule.matchName().toLowerCase(Locale.ROOT))) {
                        unresolvedClasses.add(rule.label());
                    }
                }
                case "SUBCATEGORY" -> {
                    if (!subcategories.containsKey(rule.matchName().toLowerCase(Locale.ROOT))) {
                        unresolvedClasses.add(rule.label());
                    }
                }
                case "ANY" -> {
                    /* no match value to resolve */
                }
                default -> badMatchTypes.add(rule.matchType());
            }
            if (!storage.containsKey(key(rule.locationCode(), rule.destinationName()))) {
                unresolvedDestinations.add(rule.locationCode() + "/" + rule.destinationName());
            }
            if (!STRATEGIES.contains(rule.destinationStrategy())) {
                badStrategies.add(rule.destinationStrategy());
            }
        }

        assertThat(unresolvedClasses)
                .as("matchName values with no matching seeded category/subcategory — seed-alpha.py cannot resolve"
                        + " these to a matchValue and skips the row with a WARN")
                .isEmpty();
        assertThat(unresolvedDestinations)
                .as("(locationCode, destinationName) pairs that location/storage-locations.csv does not create —"
                        + " seed-alpha.py reports these as unresolved destinations")
                .isEmpty();
        assertThat(badMatchTypes)
                .as("matchType values this pack cannot load. SKU is a legal tier in the model but seed-alpha.py"
                        + " resolves catalog classes, not products, and refuses the row — see the inventory pack"
                        + " section of scripts/fixtures/seed/alpha/README.md")
                .isEmpty();
        assertThat(badStrategies)
                .as("destinationStrategy values outside PutawayDestinationStrategy")
                .isEmpty();
    }

    @Test
    @DisplayName("#1514 - the compatibility matrix accepts every rule's destination for its own class")
    void everyDestinationIsMatrixLegal() throws IOException {
        Map<String, String> categories = taxonomy("category", "INSERT INTO subcategory");
        Map<String, String> subcategories = taxonomy("subcategory", null);
        Map<LocationKey, StorageLocation> storage = storageLocations();
        Map<String, String> parents = parentCategoryOfSubcategory(categories);
        Map<String, Set<Accepted>> matrix = matrix();

        Map<String, String> contradictions = new LinkedHashMap<>();
        for (Rule rule : rules()) {
            StorageLocation destination = storage.get(key(rule.locationCode(), rule.destinationName()));
            assertThat(destination)
                    .as("destination %s/%s", rule.locationCode(), rule.destinationName())
                    .isNotNull();

            Set<Accepted> accepted = acceptedFor(rule, categories, subcategories, parents, matrix);
            String refusal = refusalReason(destination, accepted);
            if (refusal != null) {
                contradictions.put(rule.label() + " -> " + rule.destinationName(), refusal);
            }
        }

        assertThat(contradictions)
                .as("rules whose destination the matrix refuses for their own catalog class. Each of these loads"
                        + " cleanly and then fails at putaway time with LOCATION_NOT_VALID_FOR_SKU, once per"
                        + " received line")
                .isEmpty();
    }

    /**
     * The matrix rows governing a rule, with the evaluator's precedence: a subcategory's own rows
     * replace its parent's, and an {@code ANY} rule stands for an item with no classification at all.
     */
    private static Set<Accepted> acceptedFor(
            Rule rule,
            Map<String, String> categories,
            Map<String, String> subcategories,
            Map<String, String> parentCategoryOfSubcategory,
            Map<String, Set<Accepted>> matrix) {
        String name = rule.matchName().toLowerCase(Locale.ROOT);
        return switch (rule.matchType()) {
            case "CATEGORY" -> matrix.getOrDefault("CATEGORY|" + categories.get(name), Set.of());
            case "SUBCATEGORY" -> {
                Set<Accepted> overrides = matrix.get("SUBCATEGORY|" + subcategories.get(name));
                if (overrides != null) {
                    yield overrides;
                }
                // No override for this subcategory, so StorageCompatibilityEvaluator.governingRows
                // falls back to the parent category's rows — and a SUBCATEGORY rule on an
                // un-overridden subcategory (a legitimate way to slot one subcategory of a category
                // differently) has to be legal under those. Treating it as "no accepted classes"
                // instead would fail the build on a rule that works.
                String parentCategoryId = parentCategoryOfSubcategory.get(name);
                yield parentCategoryId == null
                        ? Set.of()
                        : matrix.getOrDefault("CATEGORY|" + parentCategoryId, Set.of());
            }
            default -> Set.of();
        };
    }

    /**
     * Lowercased subcategory name -> its parent category id, read off the product fixture.
     *
     * <p>The taxonomy seed's {@code subcategory} table carries no parent column, so the containment
     * is only observable where the two names appear together — which is exactly what a product row
     * does, and what the driver's exemplar-product resolution already relies on.
     */
    private static Map<String, String> parentCategoryOfSubcategory(Map<String, String> categories) throws IOException {
        Map<String, String> parents = new TreeMap<>();
        for (Map<String, String> row : readCsv(PRODUCTS_CSV)) {
            String subcategory = row.get("subcategoryName").trim().toLowerCase(Locale.ROOT);
            String categoryId = categories.get(row.get("categoryName").trim().toLowerCase(Locale.ROOT));
            if (!subcategory.isEmpty() && categoryId != null) {
                parents.putIfAbsent(subcategory, categoryId);
            }
        }
        return parents;
    }

    @Test
    @DisplayName("#1514 - a CLOSEST_AVAILABLE anchor has topology, and its first overflow hop is legal")
    void closestAvailableOverflowStaysLegal() throws IOException {
        // PutawayDestinationResolver ranks *every* ACTIVE location at the site by BFS hops from the
        // anchor and takes the first with capacity; it does not consult the compatibility matrix. The
        // anchor itself is hop 0, so while capacity is undeclared (uncapped, per #1514) the strategy
        // behaves exactly like FIXED. The moment a capacity is declared, overflow goes to the nearest
        // neighbour — the parent shelf at one hop, sibling bins at two — so those have to be legal
        // for the same class, or the first overflow generates a task validation then refuses.
        //
        // It also means an anchor with no parent and no children is wrong for this strategy: nothing
        // is reachable, every other candidate ties at "unreachable", and the fallback degrades to the
        // lowest location id at the site — which may well be a staging floor.
        Map<String, String> categories = taxonomy("category", "INSERT INTO subcategory");
        Map<String, String> subcategories = taxonomy("subcategory", null);
        Map<LocationKey, StorageLocation> storage = storageLocations();
        Map<String, String> parents = parentCategoryOfSubcategory(categories);
        Map<String, Set<Accepted>> matrix = matrix();

        Map<LocationKey, List<StorageLocation>> childrenByParent = new HashMap<>();
        for (StorageLocation location : storage.values()) {
            if (!location.parentName().isEmpty()) {
                childrenByParent
                        .computeIfAbsent(key(location.locationCode(), location.parentName()), k -> new ArrayList<>())
                        .add(location);
            }
        }

        Set<String> anchorsWithoutTopology = new TreeSet<>();
        Map<String, String> illegalNeighbours = new LinkedHashMap<>();
        for (Rule rule : rules()) {
            if (!"CLOSEST_AVAILABLE".equals(rule.destinationStrategy())) {
                continue;
            }
            LocationKey anchorKey = key(rule.locationCode(), rule.destinationName());
            StorageLocation anchor = storage.get(anchorKey);
            if (anchor == null) {
                // Unresolvable destination; everyReferenceResolves names it. Skipping keeps that
                // the reported failure instead of an NPE here.
                continue;
            }
            List<StorageLocation> neighbours = new ArrayList<>(childrenByParent.getOrDefault(anchorKey, List.of()));
            if (!anchor.parentName().isEmpty()) {
                StorageLocation parent = storage.get(key(rule.locationCode(), anchor.parentName()));
                if (parent != null) {
                    neighbours.add(parent);
                    // Siblings: the parent's other children, two hops away.
                    childrenByParent.getOrDefault(key(rule.locationCode(), parent.name()), List.of()).stream()
                            .filter(sibling -> !sibling.name().equals(anchor.name()))
                            .forEach(neighbours::add);
                }
            }
            if (neighbours.isEmpty()) {
                anchorsWithoutTopology.add(rule.label() + " -> " + rule.destinationName());
                continue;
            }

            Set<Accepted> accepted = acceptedFor(rule, categories, subcategories, parents, matrix);
            for (StorageLocation neighbour : neighbours) {
                String refusal = refusalReason(neighbour, accepted);
                if (refusal != null) {
                    illegalNeighbours.put(rule.label() + " overflowing to " + neighbour.name(), refusal);
                }
            }
        }

        assertThat(anchorsWithoutTopology)
                .as("CLOSEST_AVAILABLE anchors with neither a parent nor children: proximity ordering has nothing"
                        + " to rank, so overflow degrades to the lowest location id at the site. Use FIXED for a"
                        + " standalone rack or floor")
                .isEmpty();
        assertThat(illegalNeighbours)
                .as("locations one or two hops from a CLOSEST_AVAILABLE anchor that the matrix refuses for the"
                        + " rule's class — the destination the strategy picks on the first overflow. This covers"
                        + " the first overflow hop only; PutawayDestinationResolver keeps walking the whole"
                        + " candidate list, and its capacity gate does not exclude STAGING/QUARANTINE, so a site"
                        + " whose nearer bins are all full can still be offered a staging floor")
                .isEmpty();
    }

    @Test
    @DisplayName("#1514 - every class the fixture names has an exemplar product for the driver to resolve through")
    void everyClassHasAnExemplarProduct() throws IOException {
        // seed-alpha.py resolves a class name to its catalog id by reading one loaded product back
        // (pos-catalog exposes no endpoint that lists categories). A name that exists in the taxonomy
        // but on no product in the catalog pack is therefore unresolvable at seed time, however
        // correct it looks here.
        Set<String> categoryNames = new HashSet<>();
        Set<String> subcategoryNames = new HashSet<>();
        for (Map<String, String> row : readCsv(PRODUCTS_CSV)) {
            categoryNames.add(row.get("categoryName").toLowerCase(Locale.ROOT));
            subcategoryNames.add(row.get("subcategoryName").toLowerCase(Locale.ROOT));
        }

        Set<String> withoutExemplar = new TreeSet<>();
        for (Rule rule : rules()) {
            String name = rule.matchName().toLowerCase(Locale.ROOT);
            boolean resolvable =
                    switch (rule.matchType()) {
                        case "CATEGORY" -> categoryNames.contains(name);
                        case "SUBCATEGORY" -> subcategoryNames.contains(name);
                        default -> true;
                    };
            if (!resolvable) {
                withoutExemplar.add(rule.label());
            }
        }

        assertThat(withoutExemplar)
                .as("classes no product in catalog/products.csv carries — seed-alpha.py cannot resolve these to a"
                        + " matchValue and skips the row with a WARN")
                .isEmpty();
    }

    @Test
    @DisplayName("#1514 - the fixture exercises more than one destination strategy")
    void exercisesEveryDestinationStrategy() throws IOException {
        // The strategies are the part of the rule model most likely to rot unnoticed: FIXED works
        // everywhere, so a fixture that only ever says FIXED never demonstrates that LAST_USED or
        // CLOSEST_AVAILABLE resolve at all on seeded data.
        Set<String> used = new TreeSet<>();
        rules().forEach(rule -> used.add(rule.destinationStrategy()));
        assertThat(used)
                .as("destination strategies the fixture exercises")
                .containsExactlyInAnyOrderElementsOf(new TreeSet<>(STRATEGIES));
    }

    @Test
    @DisplayName("#1514 - priorities are distinct within a tier, so rule order is not arbitrary")
    void prioritiesAreDistinctWithinATier() throws IOException {
        // Ties inside a tier are broken arbitrarily (PutawayRuleRequest#priority), so two rules at
        // the same priority in the same tier make which one governs an implementation detail.
        Map<String, Set<Integer>> seen = new LinkedHashMap<>();
        Set<String> duplicates = new TreeSet<>();
        for (Rule rule : rules()) {
            if (!seen.computeIfAbsent(rule.matchType(), k -> new LinkedHashSet<>())
                    .add(rule.priority())) {
                duplicates.add(rule.matchType() + " priority " + rule.priority());
            }
        }
        assertThat(duplicates).as("duplicate priorities within one match tier").isEmpty();
    }
}
