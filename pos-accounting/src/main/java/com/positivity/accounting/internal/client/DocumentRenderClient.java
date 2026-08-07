package com.positivity.accounting.internal.client;

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
 * Renders financial-report artifacts to PDF via the internal pos-documents service
 * (ADR-0020: centralized document creation).
 *
 * <p>pos-documents is internal-only (no gateway route); resolved via the {@code documents}
 * Eureka service id through the load-balanced builder (#641). The render endpoint is guarded by
 * {@code documents:render}; this client asserts the trusted internal {@code X-User} /
 * {@code X-Authorities} headers itself (gateway header format, but set by the caller — the
 * gateway is not on this path; see the pos-invoice {@code DocumentRenderClient} pattern).
 */
@Component
public class DocumentRenderClient {

    private static final Logger log = LoggerFactory.getLogger(DocumentRenderClient.class);

    private final RestClient restClient;

    public DocumentRenderClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.documents.service-id:documents}") String serviceId,
            @Value("${accounting.documents.base-path:/v1/documents}") String basePath) {
        this.restClient =
                restClientBuilder.baseUrl("http://" + serviceId + basePath).build();
    }

    /**
     * Render CSV report content to a PDF document.
     *
     * <p>pos-documents converts the CSV payload to an HTML table and binds it into the
     * given template before converting to PDF.
     *
     * @param templateId the pos-documents template to apply (blank falls back to the
     *                   service default template)
     * @param csvContent the report data as a CSV string
     * @return the rendered PDF bytes
     */
    @NonNull
    public byte[] renderPdfFromCsv(@NonNull String templateId, @NonNull String csvContent) {
        if (log.isDebugEnabled()) {
            log.debug("Rendering PDF via pos-documents using template {}", templateId);
        }
        byte[] pdf = restClient
                .post()
                .uri("/render")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_PDF)
                .header("X-User", "pos-accounting")
                .header("X-Authorities", "documents:render")
                .body(Map.of("format", "CSV", "templateId", templateId, "content", csvContent))
                .retrieve()
                .body(byte[].class);

        if (pdf == null || pdf.length == 0) {
            throw new IllegalStateException("pos-documents returned an empty PDF for template " + templateId);
        }
        return pdf;
    }
}
