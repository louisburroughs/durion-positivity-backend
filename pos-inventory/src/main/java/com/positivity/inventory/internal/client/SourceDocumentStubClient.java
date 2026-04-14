package com.positivity.inventory.internal.client;

import com.positivity.inventory.internal.enums.SourceDocumentType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import lombok.Data;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SourceDocumentStubClient {

    private static final Logger log = LoggerFactory.getLogger(SourceDocumentStubClient.class);

    private final RestClient restClient;
    private final boolean enabled;
    private final String pathTemplate;

    public SourceDocumentStubClient(
            RestClient.Builder restClientBuilder,
            @Value("${pos.inventory.receiving.stub.enabled:false}") boolean enabled,
            @Value("${pos.inventory.receiving.stub.base-url:http://localhost:8080}") String baseUrl,
            @Value(
                            "${pos.inventory.receiving.stub.path:/stub/v1/source-documents/{sourceDocumentType}/{sourceDocumentId}/lines}")
                    String pathTemplate) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.enabled = enabled;
        this.pathTemplate = pathTemplate;
    }

    @NonNull
    public List<SourceDocumentLineDto> fetchLines(
            @NonNull String sourceService,
            @NonNull SourceDocumentType sourceDocumentType,
            @NonNull String sourceDocumentId) {
        SourceDocumentLinesResponse response = fetchDocument(sourceService, sourceDocumentType, sourceDocumentId);
        if (response.getLines() == null) {
            return List.of();
        }
        return response.getLines();
    }

    @NonNull
    public SourceDocumentLinesResponse fetchDocument(
            @NonNull String sourceService,
            @NonNull SourceDocumentType sourceDocumentType,
            @NonNull String sourceDocumentId) {
        if (!enabled) {
            return emptyResponse(sourceDocumentId, sourceDocumentType, sourceService);
        }

        try {
            SourceDocumentLinesResponse response = restClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path(pathTemplate)
                            .queryParam("service", sourceService)
                            .build(sourceDocumentType.name(), sourceDocumentId))
                    .retrieve()
                    .body(SourceDocumentLinesResponse.class);

            if (response == null) {
                return emptyResponse(sourceDocumentId, sourceDocumentType, sourceService);
            }
            return response;
        } catch (Exception ex) {
            log.warn(
                    "Stub source-document lookup failed for service={}, type={}, id={}: {}",
                    sourceService,
                    sourceDocumentType,
                    sourceDocumentId,
                    ex.getMessage());
            return emptyResponse(sourceDocumentId, sourceDocumentType, sourceService);
        }
    }

    private SourceDocumentLinesResponse emptyResponse(
            String sourceDocumentId, SourceDocumentType sourceDocumentType, String sourceService) {
        SourceDocumentLinesResponse response = new SourceDocumentLinesResponse();
        response.setSourceDocumentId(sourceDocumentId);
        response.setSourceDocumentType(sourceDocumentType.name());
        response.setSourceService(sourceService);
        response.setLines(List.of());
        return response;
    }

    @Data
    public static class SourceDocumentLinesResponse {
        private String sourceDocumentId;
        private String sourceDocumentType;
        private String sourceService;
        private String status;
        private Boolean alreadyReceived;
        private List<SourceDocumentLineDto> lines;

        public boolean indicatesAlreadyReceived() {
            if (Boolean.TRUE.equals(alreadyReceived)) {
                return true;
            }
            if (status == null || status.isBlank()) {
                return false;
            }
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            return "RECEIVED".equals(normalized) || "COMPLETED".equals(normalized) || "CLOSED".equals(normalized);
        }
    }

    @Data
    public static class SourceDocumentLineDto {
        private String sourceLineId;
        private String productId;
        private BigDecimal expectedQuantity;
        private String unitOfMeasure;
    }
}
