package com.positivity.inventory.internal.client;

import com.positivity.security.common.GatewaySecurityConstants;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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
public class WorkorderValidationClient {

    private static final Logger log = LoggerFactory.getLogger(WorkorderValidationClient.class);

    private final RestClient restClient;

    public WorkorderValidationClient(
            RestClient.Builder restClientBuilder,
            @Value("${gateway.url:http://localhost:8080}") String gatewayUrl) {
        this.restClient = restClientBuilder
                .baseUrl(gatewayUrl + "/workorder/v1/workorders")
                .build();
    }

    @NonNull
    public WorkorderLineValidation getWorkorderLineValidation(
            @NonNull String workorderId,
            @NonNull String workorderLineId) {
        if (!StringUtils.hasText(workorderLineId)) {
            throw new IllegalArgumentException("workorderLineId is required");
        }
        UUID workorderUuid = parseUuid(workorderId, "workorderId");

        WorkorderDetailResponse response = restClient.get()
                .uri("/{workorderId}/detail", workorderUuid)
                .headers(this::applySecurityHeaders)
                .retrieve()
                .body(WorkorderDetailResponse.class);

        if (response == null || !StringUtils.hasText(response.getStatus())) {
            throw new IllegalStateException("Workorder detail API returned no status for workorderId " + workorderId);
        }

        UUID workorderLineUuid = parseUuid(workorderLineId, "workorderLineId");
        WorkorderPartResponse matchedLine = findPartLine(response.getParts(), workorderLineUuid)
                .orElseThrow(() -> new IllegalArgumentException(
                        "workorderLineId " + workorderLineId + " not found on workorder " + workorderId));

        if (matchedLine.getProductEntityId() == null) {
            throw new IllegalStateException(
                    "workorderLineId " + workorderLineId + " has no productEntityId on workorder " + workorderId);
        }

        return new WorkorderLineValidation(response.getStatus(), matchedLine.getProductEntityId().toString());
    }

    private Optional<WorkorderPartResponse> findPartLine(List<WorkorderPartResponse> parts, UUID lineId) {
        if (parts == null || parts.isEmpty()) {
            log.warn("Workorder detail returned no parts while validating line {}", lineId);
            return Optional.empty();
        }
        return parts.stream()
                .filter(part -> lineId.equals(part.getId()))
                .findFirst();
    }

    private UUID parseUuid(String value, String fieldName) {
        try {
            return UUID.fromString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException(fieldName + " must be a valid UUID", ex);
        }
    }

    private void applySecurityHeaders(HttpHeaders headers) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes requestAttributes)) {
            throw new IllegalStateException("Missing HTTP request context for token forwarding");
        }

        HttpServletRequest request = requestAttributes.getRequest();
        copyHeaderIfPresent(request, headers, HttpHeaders.AUTHORIZATION);
        copyHeaderIfPresent(request, headers, GatewaySecurityConstants.HEADER_TOKEN);
        copyHeaderIfPresent(request, headers, GatewaySecurityConstants.HEADER_USER);
        copyHeaderIfPresent(request, headers, GatewaySecurityConstants.HEADER_USER_ID);
        copyHeaderIfPresent(request, headers, GatewaySecurityConstants.HEADER_AUTHORITIES);

        if (headers.isEmpty()) {
            throw new IllegalStateException("No security headers available for workorder validation request");
        }
    }

    private void copyHeaderIfPresent(HttpServletRequest request, HttpHeaders headers, String headerName) {
        String value = request.getHeader(headerName);
        if (StringUtils.hasText(value)) {
            headers.set(headerName, value);
        }
    }

    public record WorkorderLineValidation(String status, String demandedProductId) {
    }

    public static class WorkorderDetailResponse {
        private String status;
        private List<WorkorderPartResponse> parts;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public List<WorkorderPartResponse> getParts() {
            return parts;
        }

        public void setParts(List<WorkorderPartResponse> parts) {
            this.parts = parts;
        }
    }

    public static class WorkorderPartResponse {
        private UUID id;
        private UUID productEntityId;

        public UUID getId() {
            return id;
        }

        public void setId(UUID id) {
            this.id = id;
        }

        public UUID getProductEntityId() {
            return productEntityId;
        }

        public void setProductEntityId(UUID productEntityId) {
            this.productEntityId = productEntityId;
        }
    }
}
