package com.positivity.workorder.internal.service;

import java.util.Collection;
import java.util.LinkedHashMap;
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
                    .uri("/v1/customers/{customerId}", customerId)
                    .header("X-User", "pos-workorder")
                    .header("X-Authorities", "crm:party:view")
                    .retrieve()
                    .body(Map.class);

            String fallbackName = "customer-" + customerId;
            if (body == null || body.isEmpty()) {
                return new CustomerContact(fallbackName, null);
            }

            Map<String, Object> payload = unwrapData(body);
            String name = firstNonBlank(
                    extract(payload, "customerName"),
                    extract(payload, "displayName"),
                    extract(payload, "name"),
                    extract(payload, "legalName"),
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

    public record CustomerContact(
            @Nullable String name, @Nullable String phoneNumber) {
        public static @NonNull CustomerContact empty() {
            return new CustomerContact("", null);
        }
    }
}
