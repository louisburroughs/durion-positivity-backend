package com.positivity.documents;

import java.util.Map;
import lombok.Builder;
import lombok.Data;
import org.jspecify.annotations.NonNull;

/**
 * Request DTO for rendering a document via pos-documents service.
 *
 * <p>Example usage:
 * <pre>{@code
 * RenderRequest request = RenderRequest.builder()
 *     .format(DocumentFormat.PDF)
 *     .templateId("INVOICE_TEMPLATE")
 *     .content(Map.of(
 *         "invoiceNumber", "INV-12345",
 *         "customerName", "Acme Corp",
 *         "items", itemsList
 *     ))
 *     .build();
 * }</pre>
 *
 * Issue: ADR-0020
 */
@Data
@Builder
public class RenderRequest {

    /** Document format to render (PDF, HTML, etc.) */
    @NonNull
    private DocumentFormat format;

    /** Template identifier registered with pos-documents */
    @NonNull
    private String templateId;

    /** Data to bind to template placeholders */
    @NonNull
    private Map<String, Object> content;

    /** Optional filename for the rendered document */
    private String filename;

    /**
     * Create a PDF render request.
     *
     * @param templateId template identifier
     * @param content data for template binding
     * @return builder for further customization
     */
    public static RenderRequestBuilder pdf(String templateId, Map<String, Object> content) {
        return RenderRequest.builder()
                .format(DocumentFormat.PDF)
                .templateId(templateId)
                .content(content);
    }

    /**
     * Create an HTML render request.
     *
     * @param templateId template identifier
     * @param content data for template binding
     * @return builder for further customization
     */
    public static RenderRequestBuilder html(String templateId, Map<String, Object> content) {
        return RenderRequest.builder()
                .format(DocumentFormat.HTML)
                .templateId(templateId)
                .content(content);
    }
}
