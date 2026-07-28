package com.positivity.marketing.internal.client;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Catalog reference validation against pos-catalog (Story #1148).
 *
 * <p>A campaign's {@code catalogFocusRef} is what the message points customers at. Confirming
 * it resolves before scheduling stops a send whose call to action leads nowhere.
 */
@Slf4j
@Component
public class CatalogClient {

    public record CatalogItemSummary(String reference, String name, boolean active) {}

    private final RestClient restClient;
    private final String basePath;

    public CatalogClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.catalog.service-id:catalog}") String serviceId,
            @Value("${pos.catalog.base-path:/v1}") String basePath) {
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
        this.basePath = basePath;
    }

    /** Whether the reference resolves to an active catalog item. Unreachable catalog reads as false. */
    public boolean isReferenceResolvable(@NonNull String reference) {
        try {
            CatalogItemSummary item = restClient
                    .get()
                    .uri(basePath + "/catalog/items/{reference}", reference)
                    .retrieve()
                    .body(CatalogItemSummary.class);
            return item != null && item.active();
        } catch (RestClientException ex) {
            log.warn("Unable to validate catalog reference {} against pos-catalog: {}", reference, ex.getMessage());
            return false;
        }
    }
}
