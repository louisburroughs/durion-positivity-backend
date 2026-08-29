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
 * Opening-stock rows: mapping, destination resolution, and the validation that stops a row landing
 * somewhere it was not meant to.
 */
@SuppressWarnings({"java:S100", "java:S1192"})
class InventoryStockCountLoaderStrategyTest {

    private static final String SITE_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10";
    private static final String BIN_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01";

    private final InventoryStockCountLoaderStrategy strategy = new InventoryStockCountLoaderStrategy();

    /** Serves a location roster and one site's storage list, recording what was asked for. */
    private static final class StubContext implements ResolutionContext {
        private final List<Map<String, Object>> roster;
        private final List<Map<String, Object>> storage;
        private final Map<String, Optional<?>> cache = new HashMap<>();
        final List<String> requestedUris = new ArrayList<>();

        StubContext(List<Map<String, Object>> roster, List<Map<String, Object>> storage) {
            this.roster = roster;
            this.storage = storage;
        }

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
            if (uri.contains("storage-locations")) {
                return (Optional<R>) Optional.of(Map.of("content", storage));
            }
            return (Optional<R>) Optional.of(roster);
        }

        @Override
        @NonNull
        @SuppressWarnings("unchecked")
        public <R> Optional<R> memoize(@NonNull String cacheKey, @NonNull Supplier<Optional<R>> loader) {
            // Mirrors RestResolutionContext: lookups nest, so the loader must not run inside a
            // computeIfAbsent on the same map.
            Optional<?> cached = cache.get(cacheKey);
            if (cached != null) {
                return (Optional<R>) cached;
            }
            Optional<R> loaded = loader.get();
            cache.put(cacheKey, loaded);
            return loaded;
        }
    }

    private static StubContext context() {
        return new StubContext(
                List.of(Map.of("id", SITE_ID, "code", "CLT-MAIN-001")),
                List.of(Map.of("id", BIN_ID, "name", "Bin A-01")));
    }

    private static InventoryStockCountRecord row(String locationCode, String storageName, String quantity) {
        InventoryStockCountRecord record = new InventoryStockCountRecord();
        record.setSku("MOBI-120764");
        record.setQuantity(quantity);
        record.setUnitOfMeasure("EA");
        record.setLocationCode(locationCode);
        record.setStorageLocationName(storageName);
        return record;
    }

    @Test
    void getDomainType_isInventoryStockCount() {
        assertThat(strategy.getDomainType()).isEqualTo(DomainType.INVENTORY_STOCK_COUNT);
    }

    @Test
    void mapRow_readsEveryColumn() {
        InventoryStockCountRecord mapped = strategy.mapRow(Map.of(
                "sku", "WIXF-51394",
                "quantity", "12",
                "unitOfMeasure", "EA",
                "reasonCode", "OPENING_BALANCE",
                "locationCode", "CLT-MAIN-001",
                "storageLocationName", "Bin A-01"));

        assertThat(mapped.getSku()).isEqualTo("WIXF-51394");
        assertThat(mapped.getQuantity()).isEqualTo("12");
        assertThat(mapped.getUnitOfMeasure()).isEqualTo("EA");
        assertThat(mapped.getReasonCode()).isEqualTo("OPENING_BALANCE");
        assertThat(mapped.getLocationCode()).isEqualTo("CLT-MAIN-001");
        assertThat(mapped.getStorageLocationName()).isEqualTo("Bin A-01");
    }

    @Test
    void resolve_findsTheStorageLocationBySiteCodeAndName() {
        InventoryStockCountRecord resolved = strategy.resolve(row("CLT-MAIN-001", "Bin A-01", "24"), context());

        assertThat(resolved.getLocationId()).isEqualTo(BIN_ID);
        assertThat(strategy.validate(resolved)).isEmpty();
    }

    @Test
    void resolve_unknownStorageName_leavesTheRowToFail() {
        // Falling back to the site would put the stock somewhere real but wrong, which loads
        // cleanly and is only noticed when putaway or a count walks a bin that is empty.
        InventoryStockCountRecord resolved = strategy.resolve(row("CLT-MAIN-001", "Bin Z-99", "24"), context());

        assertThat(resolved.getLocationId()).isNull();
        assertThat(strategy.validate(resolved)).anyMatch(error -> error.contains("locationId is required"));
    }

    @Test
    void resolve_unknownSiteCode_leavesTheRowToFail() {
        InventoryStockCountRecord resolved = strategy.resolve(row("NOWHERE-001", "Bin A-01", "24"), context());

        assertThat(resolved.getLocationId()).isNull();
        assertThat(strategy.validate(resolved)).isNotEmpty();
    }

    @Test
    void resolve_fetchesEachSiteTopologyOnce() {
        // A 263-row site must not cost 263 calls to the location service.
        StubContext context = context();

        strategy.resolve(row("CLT-MAIN-001", "Bin A-01", "24"), context);
        strategy.resolve(row("CLT-MAIN-001", "Bin A-01", "12"), context);
        strategy.resolve(row("CLT-MAIN-001", "Bin Z-99", "1"), context);

        assertThat(context.requestedUris.stream().filter(uri -> uri.contains("storage-locations")))
                .hasSize(1);
        assertThat(context.requestedUris.stream().filter(uri -> uri.equals("/v1/locations")))
                .hasSize(1);
    }

    @Test
    void resolve_existingLocationIdIsLeftAlone_andCostsNoLookup() {
        StubContext context = context();
        InventoryStockCountRecord record = row("CLT-MAIN-001", "Bin A-01", "24");
        record.setLocationId(BIN_ID);

        assertThat(strategy.resolve(record, context).getLocationId()).isEqualTo(BIN_ID);
        assertThat(context.requestedUris).isEmpty();
    }

    @Test
    void validate_rejectsAMissingSku() {
        InventoryStockCountRecord record = strategy.resolve(row("CLT-MAIN-001", "Bin A-01", "24"), context());
        record.setSku("  ");

        assertThat(strategy.validate(record)).contains("sku is required");
    }

    @Test
    void validate_rejectsANonPositiveQuantity() {
        // Opening stock declares stock that is not there yet: zero establishes nothing, and a
        // negative would post a withdrawal against a location that has nothing to withdraw.
        assertThat(strategy.validate(strategy.resolve(row("CLT-MAIN-001", "Bin A-01", "0"), context())))
                .contains("quantity must be greater than zero");
        assertThat(strategy.validate(strategy.resolve(row("CLT-MAIN-001", "Bin A-01", "-5"), context())))
                .contains("quantity must be greater than zero");
    }

    @Test
    void validate_rejectsAnUnparseableQuantity() {
        assertThat(strategy.validate(strategy.resolve(row("CLT-MAIN-001", "Bin A-01", "lots"), context())))
                .contains("quantity must be a number");
    }

    @Test
    void validate_rejectsAMissingQuantity() {
        assertThat(strategy.validate(strategy.resolve(row("CLT-MAIN-001", "Bin A-01", null), context())))
                .contains("quantity is required");
    }

    @Test
    void validate_rejectsALocationIdThatIsNotAUuid() {
        InventoryStockCountRecord record = row("CLT-MAIN-001", "Bin A-01", "24");
        record.setLocationId("not-a-uuid");

        assertThat(strategy.validate(record)).contains("locationId must be a valid UUID");
    }
}
