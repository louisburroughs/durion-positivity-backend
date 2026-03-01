package com.positivity.workorder.internal.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Service
public class VehicleReferenceService {

    private static final Logger log = LoggerFactory.getLogger(VehicleReferenceService.class);

    private final RestClient vehicleRestClient;

    public VehicleReferenceService(
            RestClient.Builder restClientBuilder,
            @Value("${pos.vehicle.base-url:http://pos-vehicle:8088}") String vehicleBaseUrl) {
        this.vehicleRestClient = restClientBuilder.baseUrl(vehicleBaseUrl).build();
    }

    public @NonNull VehicleReference resolve(@Nullable UUID vehicleId) {
        if (vehicleId == null) {
            return VehicleReference.empty();
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = vehicleRestClient.get()
                    .uri("/v1/vehicles/{vehicleId}", vehicleId)
                    .retrieve()
                    .body(Map.class);

            String fallbackInfo = "vehicle-" + vehicleId;
            if (body == null || body.isEmpty()) {
                return new VehicleReference(fallbackInfo, null);
            }

            Map<String, Object> payload = unwrapData(body);
            String info = firstNonBlank(
                    extract(payload, "vehicleInfo"),
                    extract(payload, "vehicleDescription"),
                    extract(payload, "description"),
                    extract(payload, "displayName"),
                    fallbackInfo);
            String vin = firstNonBlank(
                    extract(payload, "vin"),
                    extract(payload, "vehicleVin"));
            return new VehicleReference(info, vin);
        } catch (Exception ex) {
            log.debug("Unable to resolve vehicle reference for {}: {}", vehicleId, ex.getMessage());
            return new VehicleReference("vehicle-" + vehicleId, null);
        }
    }

    public @NonNull Map<UUID, VehicleReference> resolveAll(@NonNull Collection<UUID> vehicleIds) {
        Map<UUID, VehicleReference> resolved = new LinkedHashMap<>();
        for (UUID vehicleId : vehicleIds) {
            if (vehicleId == null || resolved.containsKey(vehicleId)) {
                continue;
            }
            resolved.put(vehicleId, resolve(vehicleId));
        }
        return resolved;
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
        return value != null ? value.toString() : null;
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

    public record VehicleReference(@Nullable String vehicleInfo, @Nullable String vin) {
        public static @NonNull VehicleReference empty() {
            return new VehicleReference("", null);
        }
    }
}
