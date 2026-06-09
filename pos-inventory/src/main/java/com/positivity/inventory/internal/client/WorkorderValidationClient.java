package com.positivity.inventory.internal.client;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class WorkorderValidationClient {

    private static final Logger log = LoggerFactory.getLogger(WorkorderValidationClient.class);

    private final RestClient restClient;

    public WorkorderValidationClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.workorder.service-id:workorder}") String workorderServiceId) {
        this.restClient = restClientBuilder.baseUrl("http://" + workorderServiceId).build();
    }

    @NonNull
    public WorkorderLineValidation getWorkorderLineValidation(
            @NonNull String workorderId, @NonNull String workorderLineId) {
        if (!StringUtils.hasText(workorderLineId)) {
            throw new IllegalArgumentException("workorderLineId is required");
        }
        UUID workorderUuid = parseUuid(workorderId, "workorderId");

        WorkorderDetailResponse response = restClient
                .get()
                .uri("/v1/workorders/{workorderId}/detail", workorderUuid)
                .header("X-User", "pos-inventory")
                .header("X-Authorities", "workorder:workorder:view")
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

        return new WorkorderLineValidation(
                response.getStatus(), matchedLine.getProductEntityId().toString());
    }

    private Optional<WorkorderPartResponse> findPartLine(List<WorkorderPartResponse> parts, UUID lineId) {
        if (parts == null || parts.isEmpty()) {
            log.warn("Workorder detail returned no parts while validating line {}", lineId);
            return Optional.empty();
        }
        return parts.stream().filter(part -> lineId.equals(part.getId())).findFirst();
    }

    private UUID parseUuid(String value, String fieldName) {
        try {
            return UUID.fromString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException(fieldName + " must be a valid UUID", ex);
        }
    }

    public record WorkorderLineValidation(String status, String demandedProductId) {}

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
