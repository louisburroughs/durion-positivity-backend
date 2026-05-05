package com.positivity.inventory.internal.client;

import com.positivity.security.common.GatewaySecurityConstants;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Client for validating storage locations against pos-location source-of-truth.
 */
@Component
public class StorageLocationValidationClient {

    private static final Logger log = LoggerFactory.getLogger(StorageLocationValidationClient.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final RestClient restClient;

    public StorageLocationValidationClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${gateway.url:http://api-gateway}") String gatewayUrl) {
        this.restClient = restClientBuilder
                .baseUrl(gatewayUrl + "/location/v1/storage-locations")
                .build();
    }

    @NonNull
    public StorageLocationValidation getStorageLocationValidation(@NonNull String storageLocationId) {
        UUID parsedId;
        try {
            parsedId = UUID.fromString(storageLocationId);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("destinationLocationId must be a valid UUID", ex);
        }

        String authorizationHeader = resolveAuthorizationHeader();

        StorageLocationValidation response = restClient
                .get()
                .uri("/{storageLocationId}/validation", parsedId)
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                .retrieve()
                .body(StorageLocationValidation.class);

        if (response == null) {
            log.warn("Storage location validation returned null payload for location {}", storageLocationId);
            throw new IllegalStateException("Location validation service returned no data");
        }

        return response;
    }

    private String resolveAuthorizationHeader() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes requestAttributes)) {
            throw new IllegalStateException("Missing HTTP request context for token forwarding");
        }

        HttpServletRequest request = requestAttributes.getRequest();
        String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorizationHeader) && authorizationHeader.startsWith(BEARER_PREFIX)) {
            return authorizationHeader;
        }

        String gatewayTokenHeader = request.getHeader(GatewaySecurityConstants.HEADER_TOKEN);
        if (StringUtils.hasText(gatewayTokenHeader)) {
            return gatewayTokenHeader.startsWith(BEARER_PREFIX)
                    ? gatewayTokenHeader
                    : BEARER_PREFIX + gatewayTokenHeader;
        }

        throw new IllegalStateException("Missing bearer token for storage location validation request");
    }

    public static class StorageLocationValidation {
        private UUID storageLocationId;
        private UUID siteId;
        private boolean exists;
        private boolean active;
        private Integer maxUnitCapacity;

        public UUID getStorageLocationId() {
            return storageLocationId;
        }

        public void setStorageLocationId(UUID storageLocationId) {
            this.storageLocationId = storageLocationId;
        }

        public UUID getSiteId() {
            return siteId;
        }

        public void setSiteId(UUID siteId) {
            this.siteId = siteId;
        }

        public boolean isExists() {
            return exists;
        }

        public void setExists(boolean exists) {
            this.exists = exists;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        public Integer getMaxUnitCapacity() {
            return maxUnitCapacity;
        }

        public void setMaxUnitCapacity(Integer maxUnitCapacity) {
            this.maxUnitCapacity = maxUnitCapacity;
        }
    }
}
