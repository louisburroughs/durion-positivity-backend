package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the alpha on-hand fixture is internally consistent (issue #1554).
 *
 * <p>{@code scripts/seed-alpha.py} loads {@code inventory/on-hand.csv} through the receiving CRUD
 * endpoints, resolving every reference by business key at run time. The endpoint accepts any row
 * whose keys resolve; it does not ask whether the stock <em>belongs</em> there. A row pointing at
 * the Retired Bin, a staging floor, or a bin already at its {@code maxUnitCount} loads cleanly and
 * only misbehaves later — as refused putaway, phantom capacity, or a cycle-count plan that walks a
 * zone which cannot legally hold what the count expects. This test moves those failures to build
 * time.
 *
 * <p>It reads the real fixture files rather than copies, so it cannot drift from any of them:
 *
 * <ul>
 *   <li>the stock itself, {@code scripts/fixtures/seed/alpha/inventory/on-hand.csv};
 *   <li>the storage topology, {@code location/storage-locations.csv} — the fixture that creates the
 *       locations the stock sits in, including each bin's declared {@code maxUnitCount};
 *   <li>the catalog, {@code catalog/products.csv} — every on-hand sku must be a product the catalog
 *       pack creates, or the driver cannot resolve the row;
 *   <li>the count plans, {@code inventory/cycle-count-plans.csv} — their zones must be real, active,
 *       stock-holding locations at their own site.
 * </ul>
 *
 * <p>The capacity test additionally pins the documented bin trio at {@code CLT-MAIN-001} (issue
 * #1554): Bin A-01 roomy, Bin A-02 exactly one unit short of full, Bin A-03 exactly full. Both
 * sides of each relationship are read from the CSVs, so editing either file without the other
 * fails the build with an explanation instead of silently un-demonstrating a capacity state.
 */
class AlphaFixtureOnHandTest {

    private static final Path MODULE_DIR = Path.of(System.getProperty("user.dir"));
    private static final Path FIXTURE_ROOT =
            MODULE_DIR.resolve("../scripts/fixtures/seed/alpha").normalize();
    private static final Path ON_HAND_CSV = FIXTURE_ROOT.resolve("inventory/on-hand.csv");
    private static final Path PLANS_CSV = FIXTURE_ROOT.resolve("inventory/cycle-count-plans.csv");
    private static final Path STORAGE_CSV = FIXTURE_ROOT.resolve("location/storage-locations.csv");
    private static final Path PRODUCTS_CSV = FIXTURE_ROOT.resolve("catalog/products.csv");

    /** Storage classes that refuse stock by rule: putaway sources, never holding locations. */
    private static final Set<String> SOURCE_ONLY = Set.of("STAGING", "QUARANTINE");

    private static final String SITE = "CLT-MAIN-001";

    // ---------------------------------------------------------------------------------------------
    // Fixture models
    // ---------------------------------------------------------------------------------------------

    /** One row of {@code on-hand.csv}. Quantity stays a string until the test that validates it. */
    private record OnHand(String sku, String locationCode, String storageLocationName, String quantity, String uom) {

        String label() {
            return sku + " @ " + locationCode + "/" + storageLocationName;
        }
    }

    /** One row of {@code storage-locations.csv}, as far as stock placement cares. */
    private record StorageLocation(
            String locationCode, String name, String storageCategoryCode, String status, String maxUnitCount) {}

    /**
     * Map key for a storage location. A record rather than a concatenated string: location names
     * contain spaces ("Bin A-01", "Parts Shelf A"), so no separator character is obviously safe,
     * and an ambiguous key would silently make two different locations collide.
     */
    private record LocationKey(String locationCode, String name) {}

    private static LocationKey key(String locationCode, String name) {
        return new LocationKey(locationCode, name);
    }

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

    private static List<OnHand> onHand() throws IOException {
        List<OnHand> rows = new ArrayList<>();
        for (Map<String, String> row : readCsv(ON_HAND_CSV)) {
            rows.add(new OnHand(
                    row.get("sku"),
                    row.get("locationCode"),
                    row.get("storageLocationName"),
                    row.get("quantity"),
                    row.get("unitOfMeasure")));
        }
        return rows;
    }

    /** (locationCode, name) -> the storage location the location pack creates. */
    private static Map<LocationKey, StorageLocation> storageLocations() throws IOException {
        Map<LocationKey, StorageLocation> byKey = new LinkedHashMap<>();
        for (Map<String, String> row : readCsv(STORAGE_CSV)) {
            StorageLocation location = new StorageLocation(
                    row.get("locationCode"),
                    row.get("name"),
                    row.get("storageCategoryCode"),
                    row.get("status"),
                    row.get("maxUnitCount"));
            byKey.put(key(location.locationCode(), location.name()), location);
        }
        return byKey;
    }

    /** Why {@code location} refuses stock by rule, or null when it can hold some. */
    private static String refusalReason(StorageLocation location) {
        if ("INACTIVE".equalsIgnoreCase(location.status())) {
            return "status INACTIVE (the Retired Bin case)";
        }
        if (SOURCE_ONLY.contains(location.storageCategoryCode())) {
            return location.storageCategoryCode() + " is a putaway source, not a holding location";
        }
        return null;
    }

    /** Summed on-hand quantity per (locationCode, storageLocationName). */
    private static Map<LocationKey, Long> fillByLocation(List<OnHand> rows) {
        Map<LocationKey, Long> fill = new LinkedHashMap<>();
        for (OnHand row : rows) {
            // A malformed quantity is quantitiesAndUnitsAreSane's finding; treating it as 0 here
            // keeps that the reported failure instead of a NumberFormatException in this helper.
            long quantity;
            try {
                quantity = Long.parseLong(row.quantity());
            } catch (NumberFormatException e) {
                quantity = 0;
            }
            fill.merge(key(row.locationCode(), row.storageLocationName()), quantity, Long::sum);
        }
        return fill;
    }

    // ---------------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("#1554 - every on-hand row sits in a storage location its site actually has")
    void everyOnHandLocationResolves() throws IOException {
        Map<LocationKey, StorageLocation> storage = storageLocations();

        Set<String> unresolved = new TreeSet<>();
        for (OnHand row : onHand()) {
            if (!storage.containsKey(key(row.locationCode(), row.storageLocationName()))) {
                unresolved.add(row.label());
            }
        }

        assertThat(unresolved)
                .as("on-hand rows whose (locationCode, storageLocationName) location/storage-locations.csv does"
                        + " not create — seed-alpha.py cannot resolve these and the stock silently never exists")
                .isEmpty();
    }

    @Test
    @DisplayName("#1554 - no on-hand row targets an INACTIVE, STAGING or QUARANTINE location")
    void noStockInLocationsThatRefuseIt() throws IOException {
        Map<LocationKey, StorageLocation> storage = storageLocations();

        Map<String, String> offenders = new TreeMap<>();
        for (OnHand row : onHand()) {
            StorageLocation location = storage.get(key(row.locationCode(), row.storageLocationName()));
            if (location == null) {
                // Unresolvable location; everyOnHandLocationResolves names it.
                continue;
            }
            String refusal = refusalReason(location);
            if (refusal != null) {
                offenders.put(row.label(), refusal);
            }
        }

        assertThat(offenders)
                .as("on-hand rows in locations that refuse stock by rule — INACTIVE locations hold nothing, and"
                        + " STAGING/QUARANTINE are putaway sources, so seeded stock there contradicts the"
                        + " compatibility rules the putaway fixture enforces")
                .isEmpty();
    }

    @Test
    @DisplayName("#1554 - every on-hand sku is a product the catalog pack creates")
    void everySkuResolvesInCatalog() throws IOException {
        Set<String> catalogSkus = new HashSet<>();
        for (Map<String, String> row : readCsv(PRODUCTS_CSV)) {
            catalogSkus.add(row.get("sku"));
        }

        Set<String> unresolved = new TreeSet<>();
        for (OnHand row : onHand()) {
            // Case-sensitive on purpose: the driver resolves by exact sku, so "mobi-120764" would
            // load as a distinct, catalog-less product.
            if (!catalogSkus.contains(row.sku())) {
                unresolved.add(row.sku());
            }
        }

        assertThat(unresolved)
                .as("on-hand skus with no exact-match row in catalog/products.csv — seed-alpha.py cannot resolve"
                        + " these to a product and the stock is unsellable")
                .isEmpty();
    }

    @Test
    @DisplayName("#1554 - keys are unique, quantities are positive integers, one unit of measure per sku")
    void quantitiesAndUnitsAreSane() throws IOException {
        List<OnHand> rows = onHand();

        Set<String> seen = new HashSet<>();
        Set<String> duplicateKeys = new TreeSet<>();
        Set<String> badQuantities = new TreeSet<>();
        Map<String, Set<String>> uomsBySku = new TreeMap<>();
        for (OnHand row : rows) {
            if (!seen.add(row.label())) {
                duplicateKeys.add(row.label());
            }
            boolean positiveInteger;
            try {
                positiveInteger = Long.parseLong(row.quantity()) > 0;
            } catch (NumberFormatException e) {
                positiveInteger = false;
            }
            if (!positiveInteger) {
                badQuantities.add(row.label() + " qty '" + row.quantity() + "'");
            }
            uomsBySku.computeIfAbsent(row.sku(), k -> new TreeSet<>()).add(row.uom());
        }
        Map<String, Set<String>> conflictingUoms = new TreeMap<>();
        uomsBySku.forEach((sku, uoms) -> {
            if (uoms.size() > 1) {
                conflictingUoms.put(sku, uoms);
            }
        });

        assertThat(duplicateKeys)
                .as("duplicate (sku, locationCode, storageLocationName) keys — the loader would apply the same"
                        + " stock twice or last-write-wins, either way the fixture stops meaning what it says")
                .isEmpty();
        assertThat(badQuantities)
                .as("quantities that are not positive integers — zero or negative on-hand is not seedable stock")
                .isEmpty();
        assertThat(conflictingUoms)
                .as("skus stocked under more than one unitOfMeasure across the file — quantities in different"
                        + " units cannot be summed, so capacity and count arithmetic silently goes wrong")
                .isEmpty();
    }

    @Test
    @DisplayName("#1554 - fills respect every declared maxUnitCount, and the CLT-MAIN-001 trio holds its states")
    void fillsRespectDeclaredCapacityAndTheTrioContract() throws IOException {
        Map<LocationKey, StorageLocation> storage = storageLocations();
        Map<LocationKey, Long> fill = fillByLocation(onHand());

        Map<String, String> overfilled = new TreeMap<>();
        for (StorageLocation location : storage.values()) {
            if (location.maxUnitCount().isEmpty()) {
                continue;
            }
            long cap = Long.parseLong(location.maxUnitCount());
            long filled = fill.getOrDefault(key(location.locationCode(), location.name()), 0L);
            if (filled > cap) {
                overfilled.put(
                        location.locationCode() + "/" + location.name(), filled + " on hand > maxUnitCount " + cap);
            }
        }
        assertThat(overfilled)
                .as("storage locations seeded past their declared maxUnitCount — the receiving flow would refuse"
                        + " the overflow, so the fixture as written cannot actually be loaded")
                .isEmpty();

        // The documented trio (issue #1554): three adjacent bins demonstrating the three capacity
        // states putaway and receiving must distinguish. Both fills and caps come from the CSVs;
        // changing either file without the other breaks a relationship below, on purpose.
        long capA01 = trioCap(storage, "Bin A-01");
        long capA02 = trioCap(storage, "Bin A-02");
        long capA03 = trioCap(storage, "Bin A-03");
        long fillA01 = fill.getOrDefault(key(SITE, "Bin A-01"), 0L);
        long fillA02 = fill.getOrDefault(key(SITE, "Bin A-02"), 0L);
        long fillA03 = fill.getOrDefault(key(SITE, "Bin A-03"), 0L);

        assertThat(fillA01)
                .as(
                        "the trio contract (issue #1554): Bin A-01 at %s is the roomy bin — its on-hand fill (%s) must"
                                + " stay strictly below its maxUnitCount (%s), leaving several units of headroom to accept"
                                + " a putaway. If you changed on-hand.csv or storage-locations.csv, adjust the other file"
                                + " to keep the three demonstration states",
                        SITE, fillA01, capA01)
                .isLessThan(capA01);
        assertThat(fillA02)
                .as(
                        "the trio contract (issue #1554): Bin A-02 at %s must sit exactly one unit short of full — its"
                                + " on-hand fill (%s) must equal maxUnitCount (%s) minus one, so a single-unit putaway"
                                + " fills it and a two-unit putaway must overflow. If you changed on-hand.csv or"
                                + " storage-locations.csv, adjust the other file to keep the three demonstration states",
                        SITE, fillA02, capA02)
                .isEqualTo(capA02 - 1);
        assertThat(fillA03)
                .as(
                        "the trio contract (issue #1554): Bin A-03 at %s must be exactly full — its on-hand fill (%s)"
                                + " must equal maxUnitCount (%s), so any putaway there must overflow immediately. If you"
                                + " changed on-hand.csv or storage-locations.csv, adjust the other file to keep the three"
                                + " demonstration states",
                        SITE, fillA03, capA03)
                .isEqualTo(capA03);
    }

    /** A trio bin's declared cap; the trio is meaningless if a bin stops declaring one. */
    private static long trioCap(Map<LocationKey, StorageLocation> storage, String binName) {
        StorageLocation bin = storage.get(key(SITE, binName));
        assertThat(bin)
                .as("the trio contract (issue #1554) needs %s to exist at %s in storage-locations.csv", binName, SITE)
                .isNotNull();
        assertThat(bin.maxUnitCount())
                .as("the trio contract (issue #1554) needs %s at %s to declare a maxUnitCount", binName, SITE)
                .isNotEmpty();
        return Long.parseLong(bin.maxUnitCount());
    }

    @Test
    @DisplayName("#1554 - every cycle-count zone is a real, active, stock-holding location at its plan's site")
    void cycleCountPlansResolve() throws IOException {
        Map<LocationKey, StorageLocation> storage = storageLocations();

        Map<String, String> badZones = new TreeMap<>();
        Set<String> badSchedules = new TreeSet<>();
        for (Map<String, String> row : readCsv(PLANS_CSV)) {
            String plan = row.get("planName");
            String site = row.get("locationCode");
            for (String zone : row.get("zoneNames").split("\\|")) {
                StorageLocation location = storage.get(key(site, zone.trim()));
                if (location == null) {
                    badZones.put(plan + " zone '" + zone.trim() + "'", "does not exist at " + site);
                    continue;
                }
                String refusal = refusalReason(location);
                if (refusal != null) {
                    badZones.put(plan + " zone '" + zone.trim() + "'", refusal);
                }
            }
            boolean positiveInteger;
            try {
                positiveInteger = Integer.parseInt(row.get("scheduledDaysOut")) > 0;
            } catch (NumberFormatException e) {
                positiveInteger = false;
            }
            if (!positiveInteger) {
                badSchedules.add(plan + " scheduledDaysOut '" + row.get("scheduledDaysOut") + "'");
            }
        }

        assertThat(badZones)
                .as("cycle-count zones that are missing, INACTIVE, or STAGING/QUARANTINE at their plan's site — a"
                        + " count over such a zone either fails to resolve or walks a location that cannot"
                        + " legally hold the stock it is counting")
                .isEmpty();
        assertThat(badSchedules)
                .as("scheduledDaysOut values that are not positive integers — the plan would be scheduled in the"
                        + " past or not at all")
                .isEmpty();
    }
}
