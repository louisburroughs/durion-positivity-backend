package com.positivity.bulkloader.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

/**
 * The six packs converted from row-by-row scripts to loader domains.
 *
 * <p>What they share is that every reference in their files is a name, so the case that matters for
 * each is the same: a name that resolves to nothing must fail its row rather than quietly load a
 * record pointing somewhere else.
 */
@SuppressWarnings({"java:S100", "java:S1192"})
class ConvertedPackStrategiesTest {

    private static final String SITE_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10";
    private static final String BIN_A = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01";
    private static final String BIN_B = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02";
    private static final String CATEGORY_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a20";
    private static final String PRODUCT_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a30";

    /** Serves the location roster, one site's storage list, and the catalog exemplar lookups. */
    private static final class StubContext implements ResolutionContext {
        private final Map<String, Optional<?>> cache = new HashMap<>();
        final List<String> requestedUris = new ArrayList<>();
        String categoryName = "Engine Parts";

        @Override
        @NonNull
        public UUID jobLocationId() {
            return UUID.fromString(SITE_ID);
        }

        @Override
        @NonNull
        @SuppressWarnings("unchecked")
        public <R> Optional<R> get(@NonNull String serviceId, @NonNull String uri, @NonNull Class<R> responseType) {
            requestedUris.add(uri);
            if (uri.equals("/v1/locations")) {
                return (Optional<R>) Optional.of(List.of(Map.of("id", SITE_ID, "code", "CLT-MAIN-001")));
            }
            if (uri.contains("storage-locations")) {
                return (Optional<R>) Optional.of(Map.of(
                        "content",
                        List.of(Map.of("id", BIN_A, "name", "Bin A-01"), Map.of("id", BIN_B, "name", "Bin A-02"))));
            }
            if (uri.contains("/products/search")) {
                return (Optional<R>) Optional.of(Map.of("data", List.of(Map.of("productId", PRODUCT_ID))));
            }
            if (uri.contains("/catalog/products/")) {
                return (Optional<R>) Optional.of(Map.of("category", Map.of("id", CATEGORY_ID, "name", categoryName)));
            }
            return Optional.empty();
        }

        @Override
        @NonNull
        @SuppressWarnings("unchecked")
        public <R> Optional<R> memoize(@NonNull String cacheKey, @NonNull Supplier<Optional<R>> loader) {
            Optional<?> cached = cache.get(cacheKey);
            if (cached != null) {
                return (Optional<R>) cached;
            }
            Optional<R> loaded = loader.get();
            cache.put(cacheKey, loaded);
            return loaded;
        }
    }

    // ─── storage locations ───────────────────────────────────────────────────

    @Test
    void storageLocations_resolveTheSite_andLeaveTheParentNameForTheService() {
        StorageLocationLoaderStrategy strategy = new StorageLocationLoaderStrategy();
        StorageLocationLoaderRecord record = strategy.mapRow(Map.of(
                "locationCode", "CLT-MAIN-001", "name", "Bin A-01", "type", "BIN", "parentName", "Parts Shelf A"));

        StorageLocationLoaderRecord resolved = strategy.resolve(record, new StubContext());

        assertThat(resolved.getSiteId()).isEqualTo(SITE_ID);
        // Only the owning service can see both what is at the site and what earlier rows created.
        assertThat(resolved.getParentName()).isEqualTo("Parts Shelf A");
        assertThat(strategy.validate(resolved)).isEmpty();
    }

    @Test
    void storageLocations_unknownSiteFailsTheRow() {
        StorageLocationLoaderStrategy strategy = new StorageLocationLoaderStrategy();
        StorageLocationLoaderRecord record =
                strategy.mapRow(Map.of("locationCode", "NOWHERE", "name", "Bin A-01", "type", "BIN"));

        assertThat(strategy.validate(strategy.resolve(record, new StubContext())))
                .anyMatch(error -> error.contains("siteId is required"));
    }

    @Test
    void storageLocations_rejectANonNumericCapacity() {
        StorageLocationLoaderStrategy strategy = new StorageLocationLoaderStrategy();
        StorageLocationLoaderRecord record = strategy.mapRow(
                Map.of("locationCode", "CLT-MAIN-001", "name", "Bin A-01", "type", "BIN", "maxUnitCount", "loads"));

        assertThat(strategy.validate(strategy.resolve(record, new StubContext())))
                .contains("maxUnitCount must be a whole number");
    }

    // ─── bays and mobile units ───────────────────────────────────────────────

    @Test
    void bays_resolveTheirLocation() {
        BayLoaderStrategy strategy = new BayLoaderStrategy();
        BayLoaderRecord record = strategy.mapRow(Map.of(
                "locationCode",
                "CLT-MAIN-001",
                "name",
                "Bay 1",
                "bayType",
                "GENERAL_SERVICE",
                "maxConcurrentVehicles",
                "1"));

        BayLoaderRecord resolved = strategy.resolve(record, new StubContext());

        assertThat(resolved.getLocationId()).isEqualTo(SITE_ID);
        assertThat(strategy.validate(resolved)).isEmpty();
        assertThat(strategy.getDomainType()).isEqualTo(DomainType.BAY);
    }

    @Test
    void mobileUnits_resolveTheirBaseLocation() {
        MobileUnitLoaderStrategy strategy = new MobileUnitLoaderStrategy();
        MobileUnitLoaderRecord record =
                strategy.mapRow(Map.of("baseLocationCode", "CLT-MAIN-001", "name", "Van 01", "status", "INACTIVE"));

        MobileUnitLoaderRecord resolved = strategy.resolve(record, new StubContext());

        assertThat(resolved.getBaseLocationId()).isEqualTo(SITE_ID);
        assertThat(strategy.validate(resolved)).isEmpty();
    }

    // ─── staffing assignments ────────────────────────────────────────────────

    @Test
    void staffingAssignments_resolveTheLocationButPassTheEmployeeNumberThrough() {
        StaffingAssignmentLoaderStrategy strategy = new StaffingAssignmentLoaderStrategy();
        StaffingAssignmentLoaderRecord record = strategy.mapRow(
                Map.of("employeeNumber", "EMP-0001", "locationCode", "CLT-MAIN-001", "role", "TECHNICIAN"));

        StaffingAssignmentLoaderRecord resolved = strategy.resolve(record, new StubContext());

        assertThat(resolved.getLocationId()).isEqualTo(SITE_ID);
        // pos-people owns employee numbers and resolves them when the batch lands.
        assertThat(resolved.getEmployeeNumber()).isEqualTo("EMP-0001");
        assertThat(strategy.validate(resolved)).isEmpty();
    }

    @Test
    void staffingAssignments_rejectAMalformedDate() {
        StaffingAssignmentLoaderStrategy strategy = new StaffingAssignmentLoaderStrategy();
        StaffingAssignmentLoaderRecord record = strategy.mapRow(Map.of(
                "employeeNumber",
                "EMP-0001",
                "locationCode",
                "CLT-MAIN-001",
                "role",
                "TECHNICIAN",
                "effectiveFrom",
                "16/02/2026"));

        assertThat(strategy.validate(strategy.resolve(record, new StubContext())))
                .contains("effectiveFrom must be a date in yyyy-MM-dd form");
    }

    // ─── putaway rules ───────────────────────────────────────────────────────

    private PutawayRuleLoaderRecord rule(PutawayRuleLoaderStrategy strategy, String matchType, String matchName) {
        Map<String, String> row = new HashMap<>();
        row.put("priority", "10");
        row.put("matchType", matchType);
        row.put("matchName", matchName);
        row.put("locationCode", "CLT-MAIN-001");
        row.put("destinationName", "Bin A-01");
        row.put("destinationStrategy", "FIXED");
        return strategy.mapRow(row);
    }

    @Test
    void putawayRules_resolveTheDestinationAndTheCatalogClass() {
        PutawayRuleLoaderStrategy strategy = new PutawayRuleLoaderStrategy();

        PutawayRuleLoaderRecord resolved =
                strategy.resolve(rule(strategy, "CATEGORY", "Engine Parts"), new StubContext());

        assertThat(resolved.getDestinationLocationId()).isEqualTo(BIN_A);
        assertThat(resolved.getMatchValue()).isEqualTo(CATEGORY_ID);
        assertThat(strategy.validate(resolved)).isEmpty();
    }

    @Test
    void putawayRules_anExemplarResolvingToAnotherClassIsRefused() {
        // The search matches loosely; taking the id anyway would route a whole class of parts to
        // the wrong bin while looking like a clean load.
        PutawayRuleLoaderStrategy strategy = new PutawayRuleLoaderStrategy();
        StubContext context = new StubContext();
        context.categoryName = "Brake System";

        PutawayRuleLoaderRecord resolved = strategy.resolve(rule(strategy, "CATEGORY", "Engine Parts"), context);

        assertThat(resolved.getMatchValue()).isNull();
        assertThat(strategy.validate(resolved)).anyMatch(error -> error.contains("matchValue is required"));
    }

    @Test
    void putawayRules_theAnyTierNeedsNoMatchValue_andRefusesOne() {
        PutawayRuleLoaderStrategy strategy = new PutawayRuleLoaderStrategy();

        PutawayRuleLoaderRecord any = strategy.resolve(rule(strategy, "ANY", ""), new StubContext());
        assertThat(any.getMatchValue()).isNull();
        assertThat(strategy.validate(any)).isEmpty();

        any.setMatchValue(CATEGORY_ID);
        assertThat(strategy.validate(any)).contains("matchValue must be omitted for the ANY tier");
    }

    @Test
    void putawayRules_unknownDestinationFailsTheRow() {
        PutawayRuleLoaderStrategy strategy = new PutawayRuleLoaderStrategy();
        PutawayRuleLoaderRecord record = rule(strategy, "CATEGORY", "Engine Parts");
        record.setDestinationName("Bin Z-99");

        assertThat(strategy.validate(strategy.resolve(record, new StubContext())))
                .anyMatch(error -> error.contains("destinationLocationId is required"));
    }

    // ─── cycle count plans ───────────────────────────────────────────────────

    @Test
    void cycleCountPlans_resolveEveryZone() {
        CycleCountPlanLoaderStrategy strategy = new CycleCountPlanLoaderStrategy();
        CycleCountPlanLoaderRecord record = strategy.mapRow(Map.of(
                "locationCode",
                "CLT-MAIN-001",
                "planName",
                "Q1",
                "zoneNames",
                "Bin A-01|Bin A-02",
                "scheduledDaysOut",
                "30"));

        CycleCountPlanLoaderRecord resolved = strategy.resolve(record, new StubContext());

        assertThat(resolved.getLocationId()).isEqualTo(SITE_ID);
        assertThat(resolved.getZoneIds()).isEqualTo(BIN_A + "," + BIN_B);
        assertThat(strategy.validate(resolved)).isEmpty();
    }

    @Test
    void cycleCountPlans_oneUnresolvableZoneFailsTheWholePlan() {
        // A plan that quietly walks fewer zones than intended reports no variance for the missing
        // one, which is indistinguishable from finding none.
        CycleCountPlanLoaderStrategy strategy = new CycleCountPlanLoaderStrategy();
        CycleCountPlanLoaderRecord record = strategy.mapRow(
                Map.of("locationCode", "CLT-MAIN-001", "planName", "Q1", "zoneNames", "Bin A-01|Bin Z-99"));

        CycleCountPlanLoaderRecord resolved = strategy.resolve(record, new StubContext());

        assertThat(resolved.getZoneIds()).isNull();
        assertThat(strategy.validate(resolved)).anyMatch(error -> error.contains("zoneIds is required"));
    }
}
