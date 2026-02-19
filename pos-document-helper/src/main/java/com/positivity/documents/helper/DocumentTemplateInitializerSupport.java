package com.positivity.documents.helper;

import com.positivity.documents.helper.exception.TemplateRegistrationException;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Support utility for registering document templates with pos-documents service.
 * 
 * <p>Provides centralized logic for template registration with error handling,
 * retry support, and logging. Mirrors EventTypeInitializerSupport from pos-events.
 * 
 * <p>Usage:
 * <pre>
 * DocumentTemplateInitializerSupport support = new DocumentTemplateInitializerSupport("pos-workorder");
 * support.registerTemplates(templates, this::registerTemplate);
 * </pre>
 * 
 * Issue: ADR-0020
 */
public class DocumentTemplateInitializerSupport {
    
    private static final Logger log = LoggerFactory.getLogger(DocumentTemplateInitializerSupport.class);
    
    private final String serviceName;
    
    /**
     * Create a new template initializer support instance.
     * 
     * @param serviceName the name of the calling service (for logging)
     */
    public DocumentTemplateInitializerSupport(@NonNull String serviceName) {
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName cannot be null");
    }
    
    /**
     * Register a collection of templates using the provided registration function.
     * Uses upsert semantics - creates if not exists, updates if exists.
     * 
     * @param templates the templates to register
     * @param registrationFunction the function to call for each registration (typically an HTTP PUT)
     * @return the number of successfully registered templates
     * @throws TemplateRegistrationException if all registrations fail
     */
    public int registerTemplates(
            @NonNull Collection<TemplateRegistration> templates,
            @NonNull Consumer<TemplateRegistration> registrationFunction) {
        
        Objects.requireNonNull(templates, "templates cannot be null");
        Objects.requireNonNull(registrationFunction, "registrationFunction cannot be null");
        
        if (templates.isEmpty()) {
            log.info("[{}] No templates to register", serviceName);
            return 0;
        }
        
        log.info("[{}] Registering {} document templates with pos-documents...", serviceName, templates.size());
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        
        for (TemplateRegistration template : templates) {
            try {
                registrationFunction.accept(template);
                successCount.incrementAndGet();
                log.debug("[{}] Successfully registered template: {}", serviceName, template.getTemplateId());
            } catch (Exception e) {
                failCount.incrementAndGet();
                log.warn("[{}] Failed to register template '{}': {}",
                        serviceName, template.getTemplateId(), e.getMessage());
            }
        }
        
        if (failCount.get() > 0) {
            log.warn("[{}] Template registration completed with {} failures out of {} attempts",
                    serviceName, failCount.get(), templates.size());
        }
        
        if (successCount.get() == 0) {
            String errorMsg = String.format(
                    "All %d template registrations failed for service %s",
                    templates.size(), serviceName);
            throw new TemplateRegistrationException("ALL_TEMPLATES", errorMsg);
        }
        
        log.info("[{}] Successfully registered {}/{} document templates",
                serviceName, successCount.get(), templates.size());
        
        return successCount.get();
    }
    
    /**
     * Validate template registration data before registration.
     * 
     * @param registration the template to validate
     * @throws IllegalArgumentException if validation fails
     */
    public void validateTemplate(@NonNull TemplateRegistration registration) {
        Objects.requireNonNull(registration, "registration cannot be null");
        
        if (registration.getTemplateId() == null || registration.getTemplateId().isBlank()) {
            throw new IllegalArgumentException("Template ID cannot be null or blank");
        }
        
        if (!registration.getTemplateId().matches("[A-Z0-9_-]+")) {
            throw new IllegalArgumentException(
                    "Template ID must contain only uppercase letters, numbers, underscores, and hyphens: "
                            + registration.getTemplateId());
        }
        
        if (registration.getContent() == null || registration.getContent().isBlank()) {
            throw new IllegalArgumentException("Template content cannot be null or blank");
        }
        
        if (registration.getVersion() == null || registration.getVersion().isBlank()) {
            throw new IllegalArgumentException("Template version cannot be null or blank");
        }
        
        if (registration.getOwner() == null || registration.getOwner().isBlank()) {
            throw new IllegalArgumentException("Template owner cannot be null or blank");
        }
    }
}
