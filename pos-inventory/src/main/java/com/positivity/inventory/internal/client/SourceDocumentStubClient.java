package com.positivity.inventory.internal.client;

import com.positivity.inventory.internal.enums.SourceDocumentType;
import java.math.BigDecimal;
import java.util.List;
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
            @Value("${pos.inventory.receiving.stub.path:/stub/v1/source-documents/{sourceDocumentType}/{sourceDocumentId}/lines}")
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
        if (!enabled) {
            return List.of();
        }

        try {
            SourceDocumentLinesResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(pathTemplate)
                            .queryParam("service", sourceService)
                            .build(sourceDocumentType.name(), sourceDocumentId))
                    .retrieve()
                    .body(SourceDocumentLinesResponse.class);

            if (response == null || response.getLines() == null) {
                return List.of();
            }
            return response.getLines();
        } catch (Exception ex) {
            log.warn(
                    "Stub source-document lookup failed for service={}, type={}, id={}: {}",
                    sourceService,
                    sourceDocumentType,
                    sourceDocumentId,
                    ex.getMessage());
            return List.of();
        }
    }

    @Data
    public static class SourceDocumentLinesResponse {
        private String sourceDocumentId;
        private String sourceDocumentType;
        private String sourceService;
        private List<SourceDocumentLineDto> lines;
    }

    @Data
    public static class SourceDocumentLineDto {
        private String sourceLineId;
        private String productId;
        private BigDecimal expectedQuantity;
        private String unitOfMeasure;
    }
}
