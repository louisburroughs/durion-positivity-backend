package com.positivity.documents.helper;

import com.positivity.documents.helper.exception.DocumentRenderException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Client for interacting with pos-documents service.
 * 
 * <p>Provides high-level methods for:
 * <ul>
 *   <li>Registering document templates
 *   <li>Rendering documents from templates
 *   <li>Error handling with retries and circuit breaker
 * </ul>
 * 
 * <p>Usage:
 * <pre>
 * DocumentClient client = new DocumentClient(restClient, "http://pos-documents:8080");
 * byte[] pdf = client.renderDocument(renderRequest);
 * </pre>
 * 
 * Issue: ADR-0020
 */
public class DocumentClient {
    
    private static final Logger log = LoggerFactory.getLogger(DocumentClient.class);
    
    private final RestClient restClient;
    private final String baseUrl;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    
    /**
     * Create a new document client.
     * 
     * @param restClient Spring RestClient for HTTP calls
     * @param baseUrl base URL of pos-documents service (e.g., "http://pos-documents:8080")
     */
    public DocumentClient(@NonNull RestClient restClient, @NonNull String baseUrl) {
        this.restClient = Objects.requireNonNull(restClient, "restClient cannot be null");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl cannot be null");
        
        // Configure retry: max 3 attempts, exponential backoff
        this.retry = Retry.of("documentClient", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(500))
                .retryExceptions(RestClientException.class)
                .build());
        
        // Configure circuit breaker: open after 5 failures, half-open after 30s
        this.circuitBreaker = CircuitBreaker.of("documentClient", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .slidingWindowSize(10)
                .build());
        
        // Log circuit breaker state changes
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("Document client circuit breaker state changed: {}", event));
    }
    
    /**
     * Register a document template with pos-documents service.
     * 
     * @param registration the template to register
     * @throws com.positivity.documents.helper.exception.TemplateRegistrationException if registration fails
     */
    public void registerTemplate(@NonNull TemplateRegistration registration) {
        Objects.requireNonNull(registration, "registration cannot be null");
        
        Supplier<Void> registrationCall = () -> {
            try {
                restClient.put()
                        .uri(baseUrl + "/api/documents/templates/{id}", registration.getTemplateId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(registration)
                        .retrieve()
                        .toBodilessEntity();
                
                log.debug("Successfully registered template: {}", registration.getTemplateId());
                return null;
            } catch (Exception e) {
                log.error("Template registration failed for {}: {}", registration.getTemplateId(), e.getMessage());
                throw new com.positivity.documents.helper.exception.TemplateRegistrationException(
                        registration.getTemplateId(),
                        "HTTP PUT failed",
                        e);
            }
        };
        
        // Apply retry and circuit breaker
        Supplier<Void> decoratedCall = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, registrationCall));
        
        decoratedCall.get();
    }
    
    /**
     * Render a document via pos-documents service.
     * 
     * @param request the rendering request
     * @return PDF bytes
     * @throws DocumentRenderException if rendering fails
     */
    @NonNull
    public byte[] renderDocument(@NonNull RenderRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        
        Supplier<byte[]> renderCall = () -> {
            try {
                var response = restClient.post()
                        .uri(baseUrl + "/api/documents/render")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .toEntity(byte[].class);
                
                if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                    throw new DocumentRenderException(
                            request.getTemplateId(),
                            "Unexpected response status: " + response.getStatusCode());
                }
                
                log.debug("Successfully rendered document with template: {}", request.getTemplateId());
                return response.getBody();
            } catch (DocumentRenderException e) {
                throw e;
            } catch (Exception e) {
                log.error("Document rendering failed for template {}: {}", request.getTemplateId(), e.getMessage());
                throw new DocumentRenderException(
                        request.getTemplateId(),
                        "HTTP POST failed",
                        e);
            }
        };
        
        // Apply retry and circuit breaker
        Supplier<byte[]> decoratedCall = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, renderCall));
        
        return decoratedCall.get();
    }
    
    /**
     * Get the current circuit breaker state.
     * 
     * @return circuit breaker state (CLOSED, OPEN, HALF_OPEN)
     */
    public CircuitBreaker.State getCircuitBreakerState() {
        return circuitBreaker.getState();
    }
    
    /**
     * Get retry metrics.
     * 
     * @return retry metrics
     */
    public Retry.Metrics getRetryMetrics() {
        return retry.getMetrics();
    }
}
