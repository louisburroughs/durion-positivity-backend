package com.positivity.location.internal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Parses the free-form storage-location capacity JSON descriptor into the derived unit
 * capacity used for validation (accepts {@code maxUnitCount}/{@code unitCount} in camel or
 * snake case). Shared by {@link StorageLocationServiceImpl} (validation responses) and
 * {@link LocationFactPublisher} ({@code location.storage-location.updated} facts, issue #892).
 */
final class StorageCapacityJson {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String[] UNIT_COUNT_KEYS = {"maxUnitCount", "max_unit_count", "unitCount", "unit_count"};

    private StorageCapacityJson() {}

    static @Nullable Integer extractMaxUnitCapacity(@Nullable String capacityJson) {
        if (capacityJson == null || capacityJson.isBlank()) {
            return null;
        }
        Map<String, Object> capacity;
        try {
            capacity = JSON_MAPPER.readValue(capacityJson, MAP_TYPE);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize capacity for storage location", ex);
        }
        if (capacity == null) {
            return null;
        }
        for (String key : UNIT_COUNT_KEYS) {
            Integer value = toInteger(capacity.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static @Nullable Integer toInteger(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }
}
