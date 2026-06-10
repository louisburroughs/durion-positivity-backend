package com.positivity.workorder.internal.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client for rendering PDFs via pos-documents service.
 *
 * <p>
 * Calls the {@code POST /v1/documents/render} endpoint which accepts
 * markdown content and returns a rendered PDF byte array.
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentClient {

    private final RestClient documentServiceRestClient;

    /**
     * Render markdown content as a PDF document.
     *
     * @param markdownContent the markdown content to render
     * @param templateId      optional template ID (null uses default template)
     * @return PDF content as byte array
     * @throws DocumentRenderException if rendering fails
     */
    @NonNull
    public byte[] renderPdf(@NonNull String markdownContent, String templateId) {
        log.debug(
                "Requesting PDF render from pos-documents, content length={}, templateId={}",
                markdownContent.length(),
                templateId);

        try {
            var request = new RenderRequestDto("MARKDOWN", markdownContent, templateId);

            byte[] pdfBytes = documentServiceRestClient
                    .post()
                    .uri("/v1/documents/render")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-User", "pos-workorder")
                    .header("X-Authorities", "documents:render")
                    .body(request)
                    .retrieve()
                    .body(byte[].class);

            if (pdfBytes == null || pdfBytes.length == 0) {
                throw new DocumentRenderException("pos-documents returned empty PDF response");
            }

            log.info("PDF rendered successfully, size={} bytes", pdfBytes.length);
            return pdfBytes;
        } catch (DocumentRenderException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to render PDF via pos-documents: {}", ex.getMessage(), ex);
            throw new DocumentRenderException("PDF rendering failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Render markdown content as a PDF using the default template.
     *
     * @param markdownContent the markdown content to render
     * @return PDF content as byte array
     */
    @NonNull
    public byte[] renderPdf(@NonNull String markdownContent) {
        return renderPdf(markdownContent, null);
    }

    /**
     * Request DTO matching pos-documents RenderRequest structure.
     * Uses String for format to avoid cross-module enum dependency.
     */
    private record RenderRequestDto(String format, String content, String templateId) {}

    /**
     * Exception thrown when document rendering fails.
     */
    public static class DocumentRenderException extends RuntimeException {
        public DocumentRenderException(String message) {
            super(message);
        }

        public DocumentRenderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
