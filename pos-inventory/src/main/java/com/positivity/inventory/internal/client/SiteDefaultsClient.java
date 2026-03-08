package com.positivity.inventory.internal.client;

import com.positivity.security.common.GatewaySecurityConstants;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class SiteDefaultsClient {

    private static final Logger log = LoggerFactory.getLogger(SiteDefaultsClient.class);

    private final RestClient restClient;

    public SiteDefaultsClient(
            RestClient.Builder restClientBuilder,
            @Value("${gateway.url:http://localhost:8080}") String gatewayUrl) {
        this.restClient = restClientBuilder
                .baseUrl(gatewayUrl + "/location/v1/locations")
                .build();
    }

    @NonNull
    public Optional<UUID> getDefaultStagingLocationId(@NonNull UUID siteId) {
        try {
            SiteDefaultsResponse response = restClient.get()
                    .uri("/{siteId}/defaults", siteId)
                    .headers(this::applySecurityHeaders)
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

    private void applySecurityHeaders(HttpHeaders headers) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes requestAttributes)) {
            return;
        }

        HttpServletRequest request = requestAttributes.getRequest();
        copyHeaderIfPresent(request, headers, HttpHeaders.AUTHORIZATION);
        copyHeaderIfPresent(request, headers, GatewaySecurityConstants.HEADER_TOKEN);
        copyHeaderIfPresent(request, headers, GatewaySecurityConstants.HEADER_USER);
        copyHeaderIfPresent(request, headers, GatewaySecurityConstants.HEADER_AUTHORITIES);
    }

    private void copyHeaderIfPresent(HttpServletRequest request, HttpHeaders headers, String headerName) {
        String value = request.getHeader(headerName);
        if (StringUtils.hasText(value)) {
            headers.set(headerName, value);
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
