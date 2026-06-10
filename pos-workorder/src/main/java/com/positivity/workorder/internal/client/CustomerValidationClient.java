package com.positivity.workorder.internal.client;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class CustomerValidationClient {

    private final RestClient restClient;

    public CustomerValidationClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.customer.service-id:customer}") String serviceId) {
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
    }

    public boolean checkRequirementsMet(@NonNull UUID customerId) {
        try {
            return Boolean.TRUE.equals(restClient
                    .get()
                    .uri("/v1/customers/{id}/requirements-met", customerId)
                    .header("X-User", "pos-workorder")
                    .header("X-Authorities", "crm:party:view")
                    .retrieve()
                    .body(Boolean.class));
        } catch (Exception e) {
            log.error("Failed to check customer requirements for {}", customerId, e);
            return false;
        }
    }
}
