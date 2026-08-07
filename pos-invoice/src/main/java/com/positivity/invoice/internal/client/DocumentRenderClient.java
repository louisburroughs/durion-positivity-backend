package com.positivity.invoice.internal.client;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Renders invoice artifacts to PDF via the internal pos-documents service.
 *
 * <p>pos-documents is internal-only (no gateway route); resolved via the {@code documents} Eureka
 * service id through the load-balanced builder (#641). The render endpoint is guarded by
 * {@code documents:render}; this client asserts the trusted internal {@code X-User} /
 * {@code X-Authorities} headers itself (gateway header format, but set by the caller — the
 * gateway is not on this path; see the tax/location client pattern).
 */
@Component
public class DocumentRenderClient {

    private static final Logger log = LoggerFactory.getLogger(DocumentRenderClient.class);

    private final RestClient restClient;

    public DocumentRenderClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.documents.service-id:documents}") String serviceId,
            @Value("${invoice.documents.base-path:/v1/documents}") String basePath) {
        this.restClient =
                restClientBuilder.baseUrl("http://" + serviceId + basePath).build();
    }

    /**
     * Render a document to PDF.
     *
     * @param templateId the pos-documents template to apply
     * @param contentJson the document data as a JSON string
     * @return the rendered PDF bytes
     */
    @NonNull
    public byte[] renderPdf(@NonNull String templateId, @NonNull String contentJson) {
        if (log.isDebugEnabled()) {
            log.debug("Rendering PDF via pos-documents using template {}", templateId);
        }
        byte[] pdf = restClient
                .post()
                .uri("/render")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_PDF)
                .header("X-User", "pos-invoice")
                .header("X-Authorities", "documents:render")
                .body(Map.of("format", "JSON", "templateId", templateId, "content", contentJson))
                .retrieve()
                .body(byte[].class);

        if (pdf == null || pdf.length == 0) {
            throw new IllegalStateException("pos-documents returned an empty PDF for template " + templateId);
        }
        return pdf;
    }
}
