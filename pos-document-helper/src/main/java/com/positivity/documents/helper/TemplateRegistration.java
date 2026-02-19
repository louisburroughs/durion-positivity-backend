package com.positivity.documents.helper;

import lombok.Builder;
import lombok.Data;
import org.jspecify.annotations.NonNull;

/**
 * Registration request for HTML document templates with pos-documents service.
 * 
 * <p>Modules register templates at startup via PUT /api/documents/templates/{templateId}.
 * Templates are versioned and can be re-registered on service restart to ensure freshness.
 * 
 * <p>Mirrors the event registration pattern established in pos-events (ADR-0020).
 * 
 * Issue: ADR-0020
 */
@Data
@Builder
public class TemplateRegistration {
    
    /**
     * Unique identifier for the template (e.g., "INVOICE_STANDARD", "ESTIMATE_DETAILED").
     * Must be alphanumeric with underscores and hyphens only.
     */
    @NonNull
    private String templateId;
    
    /**
     * HTML template content with Handlebars-style syntax.
     * Supports variable substitution, conditionals, and iteration.
     */
    @NonNull
    private String content;
    
    /**
     * Semantic version of the template (e.g., "1.0.0", "2.1.3").
     * Follow semver conventions for breaking changes.
     */
    @NonNull
    private String version;
    
    /**
     * Human-readable description of the template purpose.
     */
    @NonNull
    private String description;
    
    /**
     * Module or service name that owns this template.
     */
    @NonNull
    private String owner;
    
    /**
     * Optional metadata for template categorization and search.
     */
    private String category;
    
    /**
     * Whether this template is active and available for rendering.
     * Defaults to true.
     */
    @Builder.Default
    private boolean active = true;
}
