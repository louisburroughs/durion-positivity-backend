package com.positivity.documents;

import com.positivity.documents.exception.DocumentRenderException;
import com.positivity.documents.exception.TemplateRegistrationException;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Objects;

/**
 * Client for interacting with the pos-documents service.
 * Provides high-level methods for template registration and document rendering
 * with built-in retry logic and error handling.
 * 
 * <p>Example usage:
 * <pre>{@code
 * DocumentServiceClient client = DocumentServiceClient.builder()
 *     .baseUrl("http://pos-documents:8080")
 *     .maxRetries(3)
 *     .build();
 * 
 * // Register a template
 * client.registerTemplate(TemplateRegistration.htmlTemplate(
 *     "INVOICE_TEMPLATE",
 *     "Customer invoice template",
 *     invoiceHtml
 * ));
 * 
 * // Render a document
 * RenderResponse response = client.renderDocument(
 *     RenderRequest.pdf("INVOICE_TEMPLATE", invoiceData)
 * );
 * }</pre>
 *
 * Issue: ADR-0020
 */
public class DocumentServiceClient {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceClient.class);

    private final RestClient restClient;
    private final Retry retry;

    /**
     * Create a new document service client.
     *
     * @param restClient configured RestClient for pos-documents
     * @param retry resilience4j retry configuration
     */
    public DocumentServiceClient(@NonNull RestClient restClient, @NonNull Retry retry) {
        this.restClient = Objects.requireNonNull(restClient, "restClient cannot be null");
        this.retry = Objects.requireNonNull(retry, "retry cannot be null");
    }

    /**
     * Register a template with pos-documents service.
     * Uses PUT for upsert semantics (create or update).
     *
     * @param registration template registration details
     * @throws TemplateRegistrationException if registration fails after retries
     */
    public void registerTemplate(@NonNull TemplateRegistration registration) {
        Objects.requireNonNull(registration, "registration cannot be null");
        Objects.requireNonNull(registration.getTemplateId(), "templateId cannot be null");

        log.debug("Registering template: {}", registration.getTemplateId());

        try {
            Retry.decorateRunnable(retry, () -> {
                try {
                    restClient.put()
                            .uri("/api/documents/templates/{templateId}", registration.getTemplateId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(registration)
                            .retrieve()
                            .toBodilessEntity();
                } catch (Exception e) {
                    log.warn("Template registration attempt failed for {}: {}", 
                            registration.getTemplateId(), e.getMessage());
                    throw e;
                }
            }).run();

            log.info("Successfully registered template: {}", registration.getTemplateId());

        } catch (Exception e) {
            String msg = String.format("Failed to register template %s after retries: %s",
                    registration.getTemplateId(), e.getMessage());
            log.error(msg, e);
            throw new TemplateRegistrationException(msg, e, registration.getTemplateId());
        }
    }

    /**
     * Render a document using pos-documents service.
     *
     * @param request render request with template ID and data
     * @return rendered document response
     * @throws DocumentRenderException if rendering fails after retries
     */
    public RenderResponse renderDocument(@NonNull RenderRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(request.getTemplateId(), "templateId cannot be null");
        Objects.requireNonNull(request.getFormat(), "format cannot be null");

        log.debug("Rendering document: templateId={}, format={}", 
                request.getTemplateId(), request.getFormat());

        try {
            ResponseEntity<byte[]> response = Retry.decorateSupplier(retry, () -> {
                try {
                    return restClient.post()
                            .uri("/api/documents/render")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(request)
                            .retrieve()
                            .toEntity(byte[].class);
                } catch (Exception e) {
                    log.warn("Document render attempt failed for template {}: {}", 
                            request.getTemplateId(), e.getMessage());
                    throw e;
                }
            }).get();

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                throw new DocumentRenderException(
                        "Invalid response from pos-documents service",
                        request.getTemplateId(),
                        request.getFormat().name()
                );
            }

            byte[] content = response.getBody();
            String contentType = response.getHeaders().getContentType() != null
                    ? response.getHeaders().getContentType().toString()
                    : "application/octet-stream";

            log.info("Successfully rendered document: templateId={}, format={}, size={}",
                    request.getTemplateId(), request.getFormat(), content.length);

            return RenderResponse.builder()
                    .content(content)
                    .contentType(contentType)
                    .sizeBytes(content.length)
                    .filename(request.getFilename())
                    .templateId(request.getTemplateId())
                    .format(request.getFormat())
                    .build();

        } catch (Exception e) {
            String msg = String.format("Failed to render document with template %s (format=%s) after retries: %s",
                    request.getTemplateId(), request.getFormat(), e.getMessage());
            log.error(msg, e);
            throw new DocumentRenderException(msg, e, request.getTemplateId(), request.getFormat().name());
        }
    }

    /**
     * Create a new builder for DocumentServiceClient.
     *
     * @return builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for DocumentServiceClient with sensible defaults.
     */
    public static class Builder {
        private String baseUrl = "http://pos-documents:8080";
        private int maxRetries = 3;
        private Duration waitDuration = Duration.ofMillis(500);

        /**
         * Set the base URL for pos-documents service.
         *
         * @param baseUrl base URL (e.g., http://pos-documents:8080)
         * @return this builder
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Set the maximum number of retry attempts.
         *
         * @param maxRetries max retries (default: 3)
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Set the wait duration between retries.
         *
         * @param waitDuration duration to wait (default: 500ms)
         * @return this builder
         */
        public Builder waitDuration(Duration waitDuration) {
            this.waitDuration = waitDuration;
            return this;
        }

        /**
         * Build the DocumentServiceClient instance.
         *
         * @return configured client
         */
        public DocumentServiceClient build() {
            RestClient restClient = RestClient.builder()
                    .baseUrl(baseUrl)
                    .build();

            RetryConfig retryConfig = RetryConfig.custom()
                    .maxAttempts(maxRetries)
                    .waitDuration(waitDuration)
                    .build();

            Retry retry = Retry.of("pos-documents-client", retryConfig);

            return new DocumentServiceClient(restClient, retry);
        }
    }
}
