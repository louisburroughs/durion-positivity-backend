package com.positivity.documents.helper;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Base class for document template initialization at service startup.
 * 
 * <p>Subclass this in each module that needs to register document templates.
 * Override {@link #loadTemplates()} to provide module-specific templates.
 * 
 * <p>Example usage:
 * <pre>
 * &#64;Component
 * public class WorkorderTemplateInitializer extends DocumentTemplateInitializer {
 *     &#64;Override
 *     protected List&lt;TemplateRegistration&gt; loadTemplates() {
 *         return WorkorderDocumentTemplates.all();
 *     }
 * }
 * </pre>
 * 
 * Issue: ADR-0020
 */
public abstract class DocumentTemplateInitializer implements ApplicationRunner {
    
    private static final Logger log = LoggerFactory.getLogger(DocumentTemplateInitializer.class);
    
    private final DocumentClient documentClient;
    private final DocumentTemplateInitializerSupport initializerSupport;
    private final String serviceName;
    
    /**
     * Create a new document template initializer.
     * 
     * @param restClientBuilder Spring RestClient.Builder for HTTP calls
     * @param documentServiceBaseUrl base URL of pos-documents service
     * @param serviceName name of the calling service
     */
    protected DocumentTemplateInitializer(
            RestClient.Builder restClientBuilder,
            @Value("${pos.documents.base-url:http://pos-documents:8080}") String documentServiceBaseUrl,
            @NonNull String serviceName) {
        
        RestClient restClient = restClientBuilder
                .baseUrl(documentServiceBaseUrl)
                .build();
        
        this.documentClient = new DocumentClient(restClient, documentServiceBaseUrl);
        this.initializerSupport = new DocumentTemplateInitializerSupport(serviceName);
        this.serviceName = serviceName;
    }
    
    @Override
    public void run(ApplicationArguments args) {
        try {
            List<TemplateRegistration> templates = loadTemplates();
            
            if (templates == null || templates.isEmpty()) {
                log.info("[{}] No document templates to register", serviceName);
                return;
            }
            
            // Validate all templates before registration
            for (TemplateRegistration template : templates) {
                initializerSupport.validateTemplate(template);
            }
            
            // Register templates using the document client
            int registered = initializerSupport.registerTemplates(templates, documentClient::registerTemplate);
            
            log.info("[{}] Document template initialization complete: {}/{} templates registered",
                    serviceName, registered, templates.size());
            
        } catch (Exception e) {
            // Log error but don't fail startup - allows service to start even if pos-documents is unavailable
            log.error("[{}] Document template initialization failed: {}. Service will start but document rendering may not work.",
                    serviceName, e.getMessage(), e);
        }
    }
    
    /**
     * Load templates to register with pos-documents.
     * Override this method in subclasses to provide module-specific templates.
     * 
     * @return list of templates to register
     */
    @NonNull
    protected abstract List<TemplateRegistration> loadTemplates();
    
    /**
     * Get the document client for on-demand rendering.
     * 
     * @return document client
     */
    protected DocumentClient getDocumentClient() {
        return documentClient;
    }
}
