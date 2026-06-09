package com.positivity.inventory.internal.client;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SiteDefaultsClient {

    private static final Logger log = LoggerFactory.getLogger(SiteDefaultsClient.class);

    private final RestClient restClient;

    public SiteDefaultsClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.location.service-id:location}") String locationServiceId) {
        this.restClient = restClientBuilder.baseUrl("http://" + locationServiceId).build();
    }

    @NonNull
    public Optional<UUID> getDefaultStagingLocationId(@NonNull UUID siteId) {
        try {
            SiteDefaultsResponse response = restClient
                    .get()
                    .uri("/v1/locations/{siteId}/defaults", siteId)
                    .header("X-User", "pos-inventory")
                    .header("X-Authorities", "location:read")
                    .retrieve()
                    .body(SiteDefaultsResponse.class);

            if (response == null || response.getDefaultStagingLocationId() == null) {
                return Optional.empty();
            }
            return Optional.of(response.getDefaultStagingLocationId());
        } catch (Exception ex) {
            log.warn("Failed to resolve default staging location for site {}: {}", siteId, ex.getMessage());
            return Optional.empty();
        }
    }

    public static class SiteDefaultsResponse {
        private UUID siteId;
        private UUID defaultStagingLocationId;
        private UUID defaultQuarantineLocationId;

        public UUID getSiteId() {
            return siteId;
        }

        public void setSiteId(UUID siteId) {
            this.siteId = siteId;
        }

        public UUID getDefaultStagingLocationId() {
            return defaultStagingLocationId;
        }

        public void setDefaultStagingLocationId(UUID defaultStagingLocationId) {
            this.defaultStagingLocationId = defaultStagingLocationId;
        }

        public UUID getDefaultQuarantineLocationId() {
            return defaultQuarantineLocationId;
        }

        public void setDefaultQuarantineLocationId(UUID defaultQuarantineLocationId) {
            this.defaultQuarantineLocationId = defaultQuarantineLocationId;
        }
    }
}
