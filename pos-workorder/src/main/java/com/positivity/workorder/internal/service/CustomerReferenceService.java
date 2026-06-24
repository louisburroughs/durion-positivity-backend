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

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = customerRestClient
                    .get()
                    .uri("/v1/crm/{customerId}", customerId)
                    .header("X-User", "pos-workorder")
                    .header("X-Authorities", "crm:party:view")
                    .retrieve()
                    .body(Map.class);

            String fallbackName = "customer-" + customerId;
            if (body == null || body.isEmpty()) {
                return new CustomerContact(fallbackName, null);
            }

            Map<String, Object> payload = unwrapData(body);
            // The /v1/crm/{id} representation returns firstName/lastName (lastName carries the
            // legal/organization name for commercial parties); the browse representation returns
            // displayName/legalName. Cover both so resolution does not fall back to "customer-<id>".
            String name = firstNonBlank(
                    extract(payload, "customerName"),
                    extract(payload, "displayName"),
                    extract(payload, "legalName"),
                    extract(payload, "name"),
                    composeName(extract(payload, "firstName"), extract(payload, "lastName")),
                    fallbackName);
            String phone = firstNonBlank(
                    extract(payload, "phoneNumber"), extract(payload, "phone"), extract(payload, "mobilePhone"));
            return new CustomerContact(name, phone);
        } catch (Exception ex) {
            log.debug("Unable to resolve customer reference for {}: {}", customerId, ex.getMessage());
            return new CustomerContact("customer-" + customerId, null);
        }
    }

    public @NonNull Map<UUID, CustomerContact> resolveAll(@NonNull Collection<UUID> customerIds) {
        Map<UUID, CustomerContact> resolved = new LinkedHashMap<>();
        for (UUID customerId : customerIds) {
            if (customerId == null || resolved.containsKey(customerId)) {
                continue;
            }
            resolved.put(customerId, resolve(customerId));
        }
        return resolved;
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
                String displayName =
                        firstNonBlank(extract(row, "displayName"), extract(row, "legalName"), "customer-" + id);
                refs.add(new CustomerRef(id, displayName));
            }
            return refs;
        } catch (Exception ex) {
            log.debug("Unable to search customers by name '{}': {}", name, ex.getMessage());
            return List.of();
        }
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

    /**
     * Compose a display name from first/last parts. For commercial parties {@code lastName}
     * already carries the full legal name (and {@code firstName} is a prefix of it), so the
     * longer value is returned to avoid duplication; for individuals the two parts are joined.
     */
    private @Nullable String composeName(@Nullable String firstName, @Nullable String lastName) {
        boolean hasFirst = StringUtils.hasText(firstName);
        boolean hasLast = StringUtils.hasText(lastName);
        if (!hasFirst && !hasLast) {
            return null;
        }
        if (!hasFirst) {
            return lastName.trim();
        }
        if (!hasLast) {
            return firstName.trim();
        }
        String f = firstName.trim();
        String l = lastName.trim();
        if (l.contains(f)) {
            return l;
        }
        if (f.contains(l)) {
            return f;
        }
        return f + " " + l;
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
