package com.positivity.location.internal.client;

import com.positivity.security.common.GatewaySecurityConstants;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Client for reading location-level inventory facts from pos-inventory.
 */
@Component
public class LocationInventoryInquiryClient {

    private static final String BEARER_PREFIX = "Bearer ";

    private final RestClient restClient;

    public LocationInventoryInquiryClient(
            RestClient.Builder restClientBuilder,
            @Value("${gateway.url:http://localhost:8080}") String gatewayUrl) {
        this.restClient = restClientBuilder
                .baseUrl(gatewayUrl + "/inventory/v1/inventory/locations")
                .build();
    }

    public int getOnHandQuantity(@NonNull UUID storageLocationId) {
        LocationInventoryInquiryResponse response = restClient.get()
                .uri("/{storageLocationId}/inventory-inquiry", storageLocationId)
                .header(HttpHeaders.AUTHORIZATION, resolveAuthorizationHeader())
                .retrieve()
                .body(LocationInventoryInquiryResponse.class);

        if (response == null) {
            throw new IllegalStateException("Inventory inquiry returned no payload");
        }
        return response.getOnHandQuantity();
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

        throw new IllegalStateException("Missing bearer token for inventory inquiry request");
    }

    private static class LocationInventoryInquiryResponse {
        private int onHandQuantity;

        public int getOnHandQuantity() {
            return onHandQuantity;
        }

        public void setOnHandQuantity(int onHandQuantity) {
            this.onHandQuantity = onHandQuantity;
        }
    }
}
