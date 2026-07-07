package com.positivity.workorder.internal.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

@Service
public class CustomerReferenceService {

    private static final Logger log = LoggerFactory.getLogger(CustomerReferenceService.class);
    private final RestClient customerRestClient;

    public CustomerReferenceService(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.customer.service-id:customer}") String serviceId) {
        this.customerRestClient =
                restClientBuilder.baseUrl("http://" + serviceId).build();
    }

    public @NonNull CustomerContact resolve(@Nullable UUID customerId) {
        if (customerId == null) {
            return CustomerContact.empty();
        }
        return resolveAll(List.of(customerId)).getOrDefault(customerId, fallback(customerId));
    }

    /**
     * Resolve display names and best-effort phone for a batch of customer ids in a single CRM call
     * ({@code POST /v1/crm/accounts/parties:resolve}). Every requested id is present in the result;
     * ids the CRM cannot resolve fall back to {@code customer-<id>} with a null phone.
     */
    public @NonNull Map<UUID, CustomerContact> resolveAll(@NonNull Collection<UUID> customerIds) {
        List<UUID> ids =
                customerIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<UUID, CustomerContact> resolved = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return resolved;
        }

        Map<UUID, CustomerContact> fromCrm = new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = customerRestClient
                    .post()
                    .uri("/v1/crm/accounts/parties:resolve")
                    .header("X-User", "pos-workorder")
                    .header("X-Authorities", "crm:party:view")
                    .body(Map.of("partyIds", ids))
                    .retrieve()
                    .body(List.class);
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    UUID id = parseUuidOrNull(extract(row, "partyId"));
                    String displayName = extract(row, "displayName");
                    if (id != null && StringUtils.hasText(displayName)) {
                        String phone = extract(row, "phoneNumber");
                        fromCrm.put(
                                id,
                                new CustomerContact(
                                        displayName.trim(), StringUtils.hasText(phone) ? phone.trim() : null));
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("Unable to resolve {} customer name(s): {}", ids.size(), ex.getMessage());
        }

        for (UUID id : ids) {
            resolved.put(id, fromCrm.getOrDefault(id, fallback(id)));
        }
        return resolved;
    }

    private static @NonNull CustomerContact fallback(@NonNull UUID customerId) {
        return new CustomerContact("customer-" + customerId, null);
    }

    public @NonNull List<CustomerRef> searchIdsByName(@Nullable String name, int limit) {
        if (!StringUtils.hasText(name)) {
            return List.of();
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = customerRestClient
                    .get()
                    .uri("/v1/crm/accounts/parties?name={name}&size={size}", name, limit)
                    .header("X-User", "pos-workorder")
                    .header("X-Authorities", "crm:party:view")
                    .retrieve()
                    .body(Map.class);

            if (body == null) {
                return List.of();
            }

            Object resultsObj = body.get("results");
            if (!(resultsObj instanceof Collection<?> results)) {
                return List.of();
            }

            List<CustomerRef> refs = new ArrayList<>();
            for (Object rowObj : results) {
                if (!(rowObj instanceof Map<?, ?> rowMap)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) rowMap;
                String partyId = extract(row, "partyId");
                if (!StringUtils.hasText(partyId)) {
                    continue;
                }
                UUID id = UUID.fromString(partyId.trim());
                String displayName = Objects.requireNonNullElse(
                        firstNonBlank(extract(row, "displayName"), extract(row, "legalName"), "customer-" + id),
                        "customer-" + id);
                refs.add(new CustomerRef(id, displayName));
            }
            return refs;
        } catch (Exception ex) {
            log.debug("Unable to search customers by name '{}': {}", name, ex.getMessage());
            return List.of();
        }
    }

    private @Nullable String extract(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value != null ? value.toString() : null;
    }

    private static @Nullable UUID parseUuidOrNull(@Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
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

    public record CustomerContact(
            @Nullable String name, @Nullable String phoneNumber) {
        public static @NonNull CustomerContact empty() {
            return new CustomerContact("", null);
        }
    }

    public record CustomerRef(
            @NonNull UUID customerId, @NonNull String customerName) {}
}
