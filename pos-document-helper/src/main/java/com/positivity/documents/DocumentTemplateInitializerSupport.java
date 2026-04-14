package com.positivity.documents;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Support class for registering document templates with the pos-documents
 * service at startup.
 *
 * <p>
 * This class is dependency-agnostic and uses a functional approach. The actual
 * HTTP
 * client implementation is provided by the consuming module via a
 * {@code Consumer}.
 *
 * <p>
 * Example usage with RestClient:
 *
 * <pre>
 * {
 *     &#64;code
 *     &#64;Component
 *     public class OrderDocumentTemplateInitializer implements ApplicationRunner {
 *
 *         private final RestClient restClient;
 *         private final DocumentTemplateInitializerSupport initializerSupport;
 *
 *         public OrderDocumentTemplateInitializer(
 *                 RestClient.Builder restClientBuilder,
 *                 &#64;Value("${pos.documents.base-url}") String documentsBaseUrl) {
 *             this.restClient = restClientBuilder
 *                     .baseUrl(documentsBaseUrl + "/v1/documents/templates")
 *                     .build();
 *             this.initializerSupport = new DocumentTemplateInitializerSupport("pos-order");
 *         }
 *
 *         @Override
 *         public void run(ApplicationArguments args) {
 *             initializerSupport.registerTemplates(
 *                     OrderDocumentTemplates.all(),
 *                     this::registerViaHttp);
 *         }
 *
 *         private void registerViaHttp(TemplateRegistration registration) {
 *             restClient.put()
 *                     .uri("/{templateId}", registration.getTemplateId())
 *                     .contentType(MediaType.APPLICATION_JSON)
 *                     .body(registration)
 *                     .retrieve()
 *                     .toBodilessEntity();
 *         }
 *     }
 * }
 * </pre>
 *
 * Issue: ADR-0020
 */
public class DocumentTemplateInitializerSupport {

    private static final Logger log = LoggerFactory.getLogger(DocumentTemplateInitializerSupport.class);

    private final String serviceName;

    /**
     * Create a new initializer support instance.
     *
     * @param serviceName the name of the calling service (for logging)
     */
    public DocumentTemplateInitializerSupport(@NonNull String serviceName) {
        this.serviceName = serviceName;
    }

    /**
     * Register a collection of document templates using the provided registration
     * function.
     * Uses upsert semantics - creates if not exists, updates if exists.
     *
     * @param templates            the templates to register
     * @param registrationFunction the function to call for each registration
     *                             (typically an HTTP PUT)
     * @return the number of successfully registered templates
     */
    public int registerTemplates(
            @NonNull Collection<TemplateRegistration> templates,
            @NonNull Consumer<TemplateRegistration> registrationFunction) {

        Objects.requireNonNull(templates, "templates cannot be null");
        Objects.requireNonNull(registrationFunction, "registrationFunction cannot be null");

        log.info("[{}] Registering {} document templates with pos-documents...", serviceName, templates.size());

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (TemplateRegistration registration : templates) {
            try {
                registrationFunction.accept(registration);
                successCount.incrementAndGet();
                log.debug("[{}] Successfully registered template: {}", serviceName, registration.getTemplateId());
            } catch (Exception e) {
                failCount.incrementAndGet();
                log.warn(
                        "[{}] Failed to register template: {} - {}",
                        serviceName,
                        registration.getTemplateId(),
                        e.getMessage());
            }
        }

        int total = templates.size();
        int success = successCount.get();
        int failed = failCount.get();

        if (failed == 0) {
            log.info("[{}] Successfully registered all {} templates", serviceName, success);
        } else {
            log.warn(
                    "[{}] Template registration complete: {} succeeded, {} failed out of {} total",
                    serviceName,
                    success,
                    failed,
                    total);
        }

        return success;
    }
}
