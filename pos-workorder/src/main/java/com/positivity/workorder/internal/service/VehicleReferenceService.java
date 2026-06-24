package com.positivity.workorder.internal.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Resolves vehicle display data (label + VIN) from pos-customer, where vehicles are
 * mastered under the owning CRM account: {@code GET /v1/crm/{accountId}/vehicles} and
 * {@code GET /v1/crm/{accountId}/vehicles/{vehicleId}}. Resolution therefore needs both
 * the customer (account) id and the vehicle id. Fail-soft: any error yields a
 * {@code vehicle-<id>} fallback so callers can still render the other fields.
 */
@Service
public class VehicleReferenceService {

    private static final Logger log = LoggerFactory.getLogger(VehicleReferenceService.class);

    private final RestClient customerRestClient;

    public VehicleReferenceService(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.customer.service-id:customer}") String serviceId) {
        this.customerRestClient =
                restClientBuilder.baseUrl("http://" + serviceId).build();
    }

    /** A (customer, vehicle) pair to resolve. */
    public record VehicleKey(
            @Nullable UUID customerId, @Nullable UUID vehicleId) {}

    public @NonNull VehicleReference resolve(@Nullable UUID customerId, @Nullable UUID vehicleId) {
        if (vehicleId == null) {
            return VehicleReference.empty();
        }
        if (customerId == null) {
            return fallback(vehicleId);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = customerRestClient
                    .get()
                    .uri("/v1/crm/{customerId}/vehicles/{vehicleId}", customerId, vehicleId)
                    .header("X-User", "pos-workorder")
                    .header("X-Authorities", "crm:vehicle:view")
                    .retrieve()
                    .body(Map.class);
            if (body == null || body.isEmpty()) {
                return fallback(vehicleId);
            }
            return toReference(unwrapData(body), vehicleId);
        } catch (Exception ex) {
            log.debug("Unable to resolve vehicle {} for customer {}: {}", vehicleId, customerId, ex.getMessage());
            return fallback(vehicleId);
        }
    }

    /**
     * Resolve a batch of (customer, vehicle) pairs. The owning account's vehicle list is
     * fetched once per distinct customer and indexed by vehicle id. Returns a map keyed by
     * vehicle id (fallback entry when a vehicle is missing from its account's list).
     */
    public @NonNull Map<UUID, VehicleReference> resolveAll(@NonNull Collection<VehicleKey> keys) {
        Map<UUID, List<UUID>> vehiclesByCustomer = new LinkedHashMap<>();
        for (VehicleKey key : keys) {
            if (key == null || key.vehicleId() == null || key.customerId() == null) {
                continue;
            }
            vehiclesByCustomer
                    .computeIfAbsent(key.customerId(), c -> new ArrayList<>())
                    .add(key.vehicleId());
        }

        Map<UUID, VehicleReference> resolved = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<UUID>> entry : vehiclesByCustomer.entrySet()) {
            Map<UUID, VehicleReference> index = fetchCustomerVehicles(entry.getKey());
            for (UUID vehicleId : entry.getValue()) {
                if (!resolved.containsKey(vehicleId)) {
                    resolved.put(vehicleId, index.getOrDefault(vehicleId, fallback(vehicleId)));
                }
            }
        }
        return resolved;
    }

    private @NonNull Map<UUID, VehicleReference> fetchCustomerVehicles(@NonNull UUID customerId) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = customerRestClient
                    .get()
                    .uri("/v1/crm/{customerId}/vehicles", customerId)
                    .header("X-User", "pos-workorder")
                    .header("X-Authorities", "crm:vehicle:view")
                    .retrieve()
                    .body(List.class);
            if (rows == null || rows.isEmpty()) {
                return Map.of();
            }
            Map<UUID, VehicleReference> index = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String id = extract(row, "vehicleId");
                if (!StringUtils.hasText(id)) {
                    continue;
                }
                try {
                    UUID vehicleId = UUID.fromString(id.trim());
                    index.put(vehicleId, toReference(row, vehicleId));
                } catch (IllegalArgumentException ignore) {
                    // skip non-UUID vehicle ids
                }
            }
            return index;
        } catch (Exception ex) {
            log.debug("Unable to list vehicles for customer {}: {}", customerId, ex.getMessage());
            return Map.of();
        }
    }

    private @NonNull VehicleReference toReference(@NonNull Map<String, Object> payload, @NonNull UUID vehicleId) {
        String label = firstNonBlank(
                composeLabel(extract(payload, "year"), extract(payload, "make"), extract(payload, "model")),
                extract(payload, "description"),
                extract(payload, "vehicleInfo"),
                extract(payload, "displayName"),
                "vehicle-" + vehicleId);
        String vin = firstNonBlank(extract(payload, "vin"), extract(payload, "vinNormalized"));
        return new VehicleReference(label, vin);
    }

    private @Nullable String composeLabel(@Nullable String year, @Nullable String make, @Nullable String model) {
        List<String> parts = new ArrayList<>();
        for (String p : new String[] {year, make, model}) {
            if (StringUtils.hasText(p)) {
                parts.add(p.trim());
            }
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    private @NonNull VehicleReference fallback(@NonNull UUID vehicleId) {
        return new VehicleReference("vehicle-" + vehicleId, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapData(Map<String, Object> body) {
        Object data = body.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            return (Map<String, Object>) dataMap;
        }
        return body;
    }

    private @Nullable String extract(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        // year often arrives as a number; normalise without a trailing ".0"
        if (value instanceof Number number) {
            return String.valueOf(number.longValue());
        }
        return value.toString();
    }

    private @Nullable String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    public record VehicleReference(
            @Nullable String vehicleInfo, @Nullable String vin) {
        public static @NonNull VehicleReference empty() {
            return new VehicleReference("", null);
        }
    }
}
