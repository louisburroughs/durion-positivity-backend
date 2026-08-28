package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Opening on-hand stock, keyed by site code and storage-location name.
 *
 * <p>Storage location ids are generated per environment, so a portable file cannot carry them. Each
 * row names its destination the way the storage topology does — {@code CLT-MAIN-001} /
 * {@code Bin A-01} — and the pair is resolved here against the live location service, one lookup
 * per site rather than one per row.
 */
@Component
@Slf4j
public class InventoryStockCountLoaderStrategy implements DomainLoaderStrategy<InventoryStockCountRecord> {

    private static final String LOCATION_SERVICE_ID = "location";

    /** One page big enough for a site's whole topology; the alpha sites carry 38 rows each. */
    private static final int STORAGE_PAGE_SIZE = 500;

    @Override
    public DomainType getDomainType() {
        return DomainType.INVENTORY_STOCK_COUNT;
    }

    @Override
    public InventoryStockCountRecord mapRow(@NonNull Map<String, String> row) {
        InventoryStockCountRecord record = new InventoryStockCountRecord();
        record.setSku(row.get("sku"));
        record.setQuantity(row.get("quantity"));
        record.setUnitOfMeasure(row.get("unitOfMeasure"));
        record.setReasonCode(row.get("reasonCode"));
        record.setLocationCode(row.get("locationCode"));
        record.setStorageLocationName(row.get("storageLocationName"));
        record.setLocationId(row.get("locationId"));
        return record;
    }

    /**
     * Turns {@code locationCode} + {@code storageLocationName} into the storage location's id.
     *
     * <p>The site's whole storage list is fetched once and memoized, so a 263-row site costs one
     * call. A name that is not in that list resolves to nothing and the row then fails validation:
     * defaulting to the site itself would put the stock somewhere real but wrong, which loads
     * cleanly and is only noticed when putaway or a count walks a bin that is empty.
     */
    @Override
    @NonNull
    public InventoryStockCountRecord resolve(
            @NonNull InventoryStockCountRecord item, @NonNull ResolutionContext context) {

        if (isPresent(item.getLocationId())
                || !isPresent(item.getLocationCode())
                || !isPresent(item.getStorageLocationName())) {
            return item;
        }

        String locationCode = item.getLocationCode().trim();
        String storageName = item.getStorageLocationName().trim();

        siteId(context, locationCode)
                .flatMap(siteId -> storageLocationId(context, siteId, locationCode, storageName))
                .ifPresent(item::setLocationId);
        return item;
    }

    private Optional<String> siteId(ResolutionContext context, String locationCode) {
        return context.memoize("site:" + locationCode.toLowerCase(Locale.ROOT), () -> {
            List<Map<String, Object>> roster = context.get(LOCATION_SERVICE_ID, "/v1/locations", List.class)
                    .map(this::asMapList)
                    .orElseGet(List::of);
            Optional<String> match = roster.stream()
                    .filter(location -> locationCode.equalsIgnoreCase(asText(location.get("code"))))
                    .map(location -> asText(location.get("id")))
                    .filter(this::isPresent)
                    .findFirst();
            if (match.isEmpty()) {
                log.warn("Opening stock: site '{}' is not in the location roster", locationCode);
            }
            return match;
        });
    }

    private Optional<String> storageLocationId(
            ResolutionContext context, String siteId, String locationCode, String storageName) {

        String cacheKey = "storage:" + siteId + ':' + storageName.toLowerCase(Locale.ROOT);
        return context.memoize(cacheKey, () -> {
            Optional<String> match = storageLocationsOf(context, siteId).stream()
                    .filter(storage -> storageName.equalsIgnoreCase(asText(storage.get("name"))))
                    .map(storage -> asText(storage.get("id")))
                    .filter(this::isPresent)
                    .findFirst();
            if (match.isEmpty()) {
                log.warn(
                        "Opening stock: storage location '{}' does not exist at {} — the row will fail on its"
                                + " missing locationId",
                        storageName,
                        locationCode);
            }
            return match;
        });
    }

    /** The site's storage list, fetched once per site however many rows name it. */
    private List<Map<String, Object>> storageLocationsOf(ResolutionContext context, String siteId) {
        return context.<List<Map<String, Object>>>memoize("storage-page:" + siteId, () -> {
                    String uri = UriComponentsBuilder.fromPath("/v1/locations/{siteId}/storage-locations")
                            .queryParam("size", STORAGE_PAGE_SIZE)
                            .encode(StandardCharsets.UTF_8)
                            .buildAndExpand(siteId)
                            .toUriString();
                    return Optional.of(context.get(LOCATION_SERVICE_ID, uri, Map.class)
                            .map(body -> asMapList(body.get("content")))
                            .orElseGet(List::of));
                })
                .orElseGet(List::of);
    }

    @Override
    public List<String> validate(@NonNull InventoryStockCountRecord item) {
        List<String> errors = new ArrayList<>();
        if (!isPresent(item.getSku())) {
            errors.add("sku is required");
        }
        if (!isPresent(item.getLocationId())) {
            errors.add("locationId is required (or a locationCode and storageLocationName that resolve to one)");
        } else {
            try {
                UUID.fromString(item.getLocationId().trim());
            } catch (IllegalArgumentException _) {
                errors.add("locationId must be a valid UUID");
            }
        }
        validateQuantity(item, errors);
        return errors;
    }

    /**
     * Opening stock has to be a positive number: the line declares stock that is not there yet, so
     * a blank, unparseable or non-positive quantity has nothing to establish and must not be
     * defaulted to something.
     */
    private void validateQuantity(InventoryStockCountRecord item, List<String> errors) {
        if (!isPresent(item.getQuantity())) {
            errors.add("quantity is required");
            return;
        }
        try {
            if (new BigDecimal(item.getQuantity().trim()).signum() <= 0) {
                errors.add("quantity must be greater than zero");
            }
        } catch (NumberFormatException _) {
            errors.add("quantity must be a number");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private String asText(Object value) {
        return value == null ? null : value.toString();
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
